# Ingress on a bare kubeadm/kubespray cluster (Traefik)

kubespray does **not** install an ingress controller by default — `Ingress`
resources sit inert until one is running. This uses
[Traefik](https://traefik.io/) installed via plain manifests (no Helm),
since Traefik natively watches standard Kubernetes `Ingress` objects — its
own `IngressRoute` CRD is only needed for Traefik-specific features
(weighted routing, custom middlewares, etc.), not for basic host-based
routing.

(This lab initially used ingress-nginx; see the bottom of this doc for why
it was swapped out.)

## 1. Install the CRDs and RBAC

```bash
curl -sL -o traefik-crds.yaml \
  https://raw.githubusercontent.com/traefik/traefik/v3.7/docs/content/reference/dynamic-configuration/kubernetes-crd-definition-v1.yml
curl -sL -o traefik-rbac.yaml \
  https://raw.githubusercontent.com/traefik/traefik/v3.7/docs/content/reference/dynamic-configuration/kubernetes-crd-rbac.yml

kubectl apply -f traefik-crds.yaml
kubectl apply -f traefik-rbac.yaml
```

The RBAC manifest expects a `ServiceAccount` named `traefik-ingress-controller`
in the `default` namespace — match that in your Deployment rather than
editing the fetched YAML.

## 2. Deploy Traefik

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: traefik-ingress-controller
  namespace: default
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: traefik
  namespace: default
  labels:
    app: traefik
spec:
  replicas: 1
  selector:
    matchLabels:
      app: traefik
  template:
    metadata:
      labels:
        app: traefik
    spec:
      serviceAccountName: traefik-ingress-controller
      containers:
        - name: traefik
          image: traefik:v3.7.13
          args:
            - --entrypoints.web.address=:80
            - --entrypoints.websecure.address=:443
            - --providers.kubernetescrd
            - --providers.kubernetesingress
            - --providers.kubernetesingress.ingressclass=traefik
            - --api.dashboard=true
            - --api.insecure=true   # dashboard with no auth - lab only
            - --ping=true            # needed for the /ping health endpoint
          ports:
            - name: web
              containerPort: 80
            - name: websecure
              containerPort: 443
            - name: dashboard
              containerPort: 8080
          readinessProbe:
            httpGet: { path: /ping, port: 8080 }
            initialDelaySeconds: 5
          livenessProbe:
            httpGet: { path: /ping, port: 8080 }
            initialDelaySeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: traefik
  namespace: default
spec:
  type: NodePort
  selector:
    app: traefik
  ports:
    - name: web
      port: 80
      targetPort: web
    - name: websecure
      port: 443
      targetPort: websecure
    - name: dashboard
      port: 8080
      targetPort: dashboard
---
apiVersion: networking.k8s.io/v1
kind: IngressClass
metadata:
  name: traefik
spec:
  controller: traefik.io/ingress-controller
```

`type: NodePort` (not `LoadBalancer`) since there's no cloud load balancer
provisioner on a bare kubeadm cluster — same reasoning as ingress-nginx's
"baremetal" provider variant.

```bash
kubectl apply -f traefik-deploy.yaml
kubectl wait --for=condition=Ready pod -l app=traefik --timeout=120s
kubectl get svc traefik   # note the NodePorts
```

## 3. Point a hostname at it

Same as any Ingress: routing is host-header based.

**Option A — `/etc/hosts`** (works for browser access too):
```
<any-node-ip>  hello.lab.local
```
Then browse to `http://hello.lab.local:<web-nodeport>/`.

**Option B — explicit `Host` header with curl**:
```bash
curl -H "Host: hello.lab.local" http://<any-node-ip>:<web-nodeport>/api/hello
```

Either node IP works — `NodePort` listens on every node regardless of which
one the Traefik pod landed on.

## 4. Point your `Ingress` at Traefik

Standard `Ingress` object, just `ingressClassName: traefik`:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: <name>
  namespace: <namespace>
spec:
  ingressClassName: traefik
  rules:
    - host: <your-lab-hostname>
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: <service-name>
                port:
                  number: <service-port>
```

## Debugging via the dashboard/API

With `--api.insecure=true`, Traefik's own API is reachable on the
`dashboard` NodePort with no auth — useful for confirming a route actually
registered:

```bash
curl http://<any-node-ip>:<dashboard-nodeport>/api/http/routers | python3 -m json.tool
```

Look for a router named `<ingress-name>-<namespace>-<host>@kubernetes` with
the expected `Host(...)` rule. If it's missing, check `ingressClassName`
matches exactly and that Traefik's pod logs don't show an RBAC/watch error.

## Why this replaced ingress-nginx

This lab initially ran ingress-nginx (its "baremetal" static manifest,
similarly NodePort-based). Switched to Traefik for its lighter footprint
and built-in dashboard/API being handy for a lab. Swapping was
low-friction specifically *because* Traefik supports plain `Ingress`
objects — only `ingressClassName` needed to change on the existing
`Ingress` resource, no rewrite to a controller-specific CRD required.

## Notes / limitations

- No TLS configured — plain HTTP, fine for a private lab network.
- `--api.insecure=true` exposes the dashboard/API with zero authentication —
  acceptable only because this is on an isolated lab network with no
  external exposure.
- Single replica; bump it if testing HA behavior matters.
- A `503`/connection-refused from a route means the backing Service has no
  ready endpoints yet, not necessarily an Ingress misconfiguration — check
  `kubectl get pods` for the target Deployment first.
