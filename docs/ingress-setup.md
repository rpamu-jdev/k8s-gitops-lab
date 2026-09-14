# Ingress on a bare kubeadm/kubespray cluster

kubespray does **not** install an ingress controller by default — `Ingress`
resources sit inert until one is running. This sets up
[ingress-nginx](https://github.com/kubernetes/ingress-nginx) using its
"baremetal" manifest, which is the right choice for a kubeadm cluster with no
cloud load balancer (unlike EKS/GKE, there's nothing to provision an external
`LoadBalancer` IP here).

## 1. Install the controller

```bash
curl -s -o ingress-nginx.yaml \
  https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/baremetal/deploy.yaml
kubectl apply -f ingress-nginx.yaml
```

This creates the `ingress-nginx` namespace, the controller Deployment, and a
`NodePort` Service (the baremetal provider variant uses `NodePort` since
there's no external LB to bind a `LoadBalancer` Service to).

Wait for the controller pod to be ready:

```bash
kubectl -n ingress-nginx wait --for=condition=Ready pod \
  -l app.kubernetes.io/component=controller --timeout=120s
```

## 2. Find the NodePorts

```bash
kubectl -n ingress-nginx get svc ingress-nginx-controller
```

Two ports matter: the one mapped to container port `80` (HTTP) and `443`
(HTTPS). They're randomly assigned in the 30000-32767 range unless pinned.

## 3. Point a hostname at it

`Ingress` routing is host-header based, so `curl`/the browser needs to send
a `Host` header matching what's in the `Ingress` resource's `spec.rules`.
Two ways to do that for a lab domain that doesn't really exist in DNS:

**Option A — `/etc/hosts`** (works for browser access too):
```
<any-node-ip>  hello.lab.local
```
Then browse to `http://hello.lab.local:<http-nodeport>/`.

**Option B — explicit `Host` header with curl**, no `/etc/hosts` edit needed:
```bash
curl -H "Host: hello.lab.local" http://<any-node-ip>:<http-nodeport>/api/hello
```

Either node IP works — `NodePort` Services listen on **every** node
regardless of which node the ingress controller pod actually landed on
(kube-proxy handles the redirect).

## 4. Point the Ingress resource at your service

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: <name>
  namespace: <namespace>
spec:
  ingressClassName: nginx
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

`ingressClassName: nginx` is required — without it, a cluster with multiple
ingress controllers wouldn't know which one should serve the resource (and
recent ingress-nginx versions ignore `Ingress` objects with no class at all).

## Notes / limitations

- No TLS configured here — plain HTTP, fine for a lab on a private network.
- The controller runs as a single replica by default on the baremetal
  manifest; for this lab's node count that's appropriate (bump
  `replicas` in its Deployment if testing HA behavior matters).
- If a Service's Deployment isn't `Ready` yet, the Ingress will return a
  `503` — that's ingress-nginx correctly reporting no healthy backends, not
  an ingress misconfiguration.
