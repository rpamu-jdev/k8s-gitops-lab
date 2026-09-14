# Tekton and Kubernetes dashboards

See [../../docs/dashboards-setup.md](../../docs/dashboards-setup.md) for
the full writeup and
[../../docs/my-lab/dashboards-setup.md](../../docs/my-lab/dashboards-setup.md)
for this lab's concrete access details.

## Apply

The two dashboards themselves come from upstream release manifests
(not vendored here — see the generic doc for the exact `kubectl apply -f
<url>` commands, one per dashboard). This directory holds only what's
specific to this lab's setup:

```bash
kubectl apply -f rbac-viewer.yaml     # read-only login for k8s dashboard
kubectl apply -f ingressroute.yaml    # routes both dashboards through Traefik
```

## Get a login token for the Kubernetes Dashboard

```bash
kubectl -n kubernetes-dashboard create token dashboard-viewer --duration=87600h
```

## Files

- `rbac-viewer.yaml` — `dashboard-viewer` ServiceAccount bound to the
  built-in `view` ClusterRole (read-only, all namespaces) — deliberately
  not `cluster-admin`
- `ingressroute.yaml` — Traefik `IngressRoute`s for both dashboards
  (`tekton.staging.test`, `dashboard.staging.test`) plus the `ServersTransport`
  needed for the Kubernetes Dashboard's self-signed-cert HTTPS backend
