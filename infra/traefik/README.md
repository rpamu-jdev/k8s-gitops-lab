# Traefik ingress controller manifests

Vendored copies of what's actually applied to this cluster (see the
"Install an ingress controller" section of
[../../docs/k8s-setup.md](../../docs/k8s-setup.md) for the full writeup).
Kept here so a cluster rebuild doesn't depend on GitHub raw URLs staying
available/reachable.

- `crds.yaml` — Traefik's CRDs (IngressRoute, Middleware, etc.), fetched
  from upstream `traefik/traefik` v3.7 tag, unmodified
- `rbac.yaml` — ClusterRole/ClusterRoleBinding, fetched from the same tag,
  unmodified (expects ServiceAccount `traefik-ingress-controller` in the
  `default` namespace — matched in `deploy.yaml` rather than edited here)
- `deploy.yaml` — hand-written ServiceAccount/Deployment/Service/
  IngressClass, version pinned to `traefik:v3.7.13`

## Apply

```bash
kubectl apply -f crds.yaml
kubectl apply -f rbac.yaml
kubectl apply -f deploy.yaml
kubectl wait --for=condition=Ready pod -l app=traefik --timeout=120s
kubectl get svc traefik   # note the NodePorts
```

## Bump the version

Update the image tag in `deploy.yaml`, and re-fetch `crds.yaml`/`rbac.yaml`
from the matching upstream tag if it's a major/minor bump (CRDs occasionally
change between minors).
