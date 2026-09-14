# Traefik ingress controller manifests

Vendored copies of what's actually applied to this cluster (see the
"Install an ingress controller" section of
[../../docs/k8s-setup.md](../../docs/k8s-setup.md) for the full writeup).
Kept here so a cluster rebuild doesn't depend on GitHub raw URLs staying
available/reachable.

- `crds.yaml` — Traefik's CRDs (IngressRoute, Middleware, etc.), fetched
  from upstream `traefik/traefik` v3.7 tag, unmodified
- `rbac.yaml` — ClusterRole/ClusterRoleBinding, fetched from the same tag;
  the `ClusterRoleBinding`'s subject namespace was changed from the
  upstream default (`default`) to `kube-system`, to match where Traefik
  actually runs here (alongside the cluster's other infra components)
- `deploy.yaml` — hand-written ServiceAccount/Deployment/Service/
  IngressClass, all in `kube-system`, version pinned to `traefik:v3.7.13`.
  Runs on `hostNetwork` (pinned to `k8s-node` via `nodeSelector`) so
  everything reaches it with **no port number** — see
  [../../docs/k8s-setup.md](../../docs/k8s-setup.md) for the `NodePort`
  alternative if you'd rather have every node answer at the cost of a
  port number in every URL.

## Apply

```bash
kubectl apply -f crds.yaml
kubectl apply -f rbac.yaml
kubectl apply -f deploy.yaml
kubectl -n kube-system wait --for=condition=Ready pod -l app=traefik --timeout=120s
kubectl -n kube-system get pods -l app=traefik -o wide   # confirm it's on k8s-node
```

## Bump the version

Update the image tag in `deploy.yaml`, and re-fetch `crds.yaml`/`rbac.yaml`
from the matching upstream tag if it's a major/minor bump (CRDs occasionally
change between minors).
