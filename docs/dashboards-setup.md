# Tekton and Kubernetes dashboards

Both dashboards are single-page apps, so they get **their own hostnames**
routed through Traefik — rather than a path prefix under the lab's shared
`api.staging.test` convention used for backend APIs. A path prefix would risk
breaking their asset loading (neither ships built with a configurable base
path/base-href), while a dedicated `Host` match avoids that entirely and
keeps them off raw NodePort numbers.

## 1. Install Tekton Dashboard

```bash
kubectl apply -f https://github.com/tektoncd/dashboard/releases/download/<version>/release.yaml
```

Installs into the `tekton-pipelines` namespace, `ClusterIP` Service on port
`9097` by default.

## 2. Install Kubernetes Dashboard

The current Dashboard major version (7.x) requires Helm. This lab uses the
older, still-maintained single-manifest v2.x line instead — simpler,
consistent with how everything else here is installed:

```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes/dashboard/v2.7.0/aio/deploy/recommended.yaml
```

Installs into the `kubernetes-dashboard` namespace. Serves **HTTPS
internally** with a self-signed cert (`ClusterIP` Service, port `443`) —
matters for step 4 below.

### Login access: pick a privilege level deliberately

The Dashboard needs a `ServiceAccount` + token to log in. **Don't default
to `cluster-admin`** — a leaked or over-broadly-scoped dashboard token is
equivalent to full cluster control. For a dashboard mainly used to look at
things, the built-in `view` `ClusterRole` (read-only, every namespace) is
the better default:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: dashboard-viewer
  namespace: kubernetes-dashboard
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: dashboard-viewer
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: view
subjects:
  - kind: ServiceAccount
    name: dashboard-viewer
    namespace: kubernetes-dashboard
```

```bash
kubectl -n kubernetes-dashboard create token dashboard-viewer --duration=87600h
```

Grant write access only if you specifically need to create/edit/delete
resources through the UI, and scope it as narrowly as the task requires —
not straight to `cluster-admin`.

## 3. Route both through Traefik with dedicated hostnames

Kubernetes Dashboard needs a `ServersTransport` telling Traefik to accept
its self-signed cert when proxying to it (Traefik itself still serves
plain HTTP to the browser — the HTTPS hop is only Traefik→backend):

```yaml
apiVersion: traefik.io/v1alpha1
kind: ServersTransport
metadata:
  name: k8s-dashboard-transport
  namespace: kubernetes-dashboard
spec:
  insecureSkipVerify: true
---
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: tekton-dashboard
  namespace: tekton-pipelines
spec:
  entryPoints:
    - web
  routes:
    - match: Host(`tekton.<your-domain>`)
      kind: Rule
      services:
        - name: tekton-dashboard
          port: 9097
---
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: kubernetes-dashboard
  namespace: kubernetes-dashboard
spec:
  entryPoints:
    - web
  routes:
    - match: Host(`dashboard.<your-domain>`)
      kind: Rule
      services:
        - name: kubernetes-dashboard
          port: 443
          scheme: https
          serversTransport: k8s-dashboard-transport
```

Both Services stay `ClusterIP` — Traefik is now the single entry point for
everything, so there's no need to also expose each dashboard individually.

## 4. Point the hostnames somewhere

Same mechanism as `api.staging.test` — add `/etc/hosts` entries (needs
`sudo`, not something to script blindly). If Traefik is running with
`hostNetwork` (see [k8s-setup.md](k8s-setup.md)), point at the specific
node it's pinned to and drop the port entirely:

```
<traefik-node-ip>  tekton.<your-domain> dashboard.<your-domain>
```

```bash
curl -H "Host: tekton.<your-domain>" http://<traefik-node-ip>/
curl -H "Host: dashboard.<your-domain>" http://<traefik-node-ip>/
```

With a `NodePort`-exposed Traefik instead, any node IP works but needs the
port: `http://<any-node-ip>:<traefik-web-nodeport>/`.

## Verify

Both should return actual HTML (`200`), and Traefik's own router API
(port `8080`, same host) should list both routers as `enabled`:

```bash
curl http://<traefik-node-ip>:8080/api/http/routers | python3 -m json.tool
```
