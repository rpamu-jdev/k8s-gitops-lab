# Argo CD

See [../../docs/argocd-setup.md](../../docs/argocd-setup.md) for the full
writeup and
[../../docs/my-lab/argocd-setup.md](../../docs/my-lab/argocd-setup.md) for
this lab's concrete access details.

Argo CD itself comes from upstream release manifests (not vendored here —
see the generic doc for the pinned-version `kubectl apply -f <url>`
command). This directory holds only what's specific to this lab:

```bash
kubectl apply -f ingressroute.yaml   # routes the UI through Traefik at argocd.staging.test
```

The Gitea repo-credentials `Secret` is **not committed here** (contains a
token) — created directly on the cluster, see the generic doc. Same for
the initial admin password (read from `argocd-initial-admin-secret`, not
stored anywhere).

The `Application` resource that actually deploys `hello-camel-service`
lives in [../../ci/argocd/](../../ci/argocd/), parallel to how
`ci/tekton/` holds that app's CI pipeline instance — this directory is
Argo CD's own install/routing, that directory is what Argo CD deploys.

## Files

- `ingressroute.yaml` — Traefik `IngressRoute` for the Argo CD UI/API
  (`argocd.staging.test`), plain HTTP (see the generic doc for why
  `argocd-server` is set to `--insecure`, unlike the Kubernetes Dashboard)
