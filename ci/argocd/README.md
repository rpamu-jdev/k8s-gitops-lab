# Argo CD Applications (used here for hello-camel-service)

Parallel to [../tekton/](../tekton/): that directory is the CI instance
for this app (build+push, tag-triggered), this directory is the CD
instance (deploy, git-triggered). See
[../../docs/argocd-setup.md](../../docs/argocd-setup.md) for the generic
Argo CD writeup and
[../../docs/my-lab/argocd-setup.md](../../docs/my-lab/argocd-setup.md) for
this lab's concrete setup.

## Apply

```bash
kubectl apply -f application-hello-camel-service.yaml
```

A second Java app would get its own `Application` manifest here, pointing
`spec.source.path` at that app's own `apps/<app-name>/k8s` directory —
nothing in Argo CD's own install (see [../../infra/argocd/](../../infra/argocd/))
needs to change.

## Files

- `application-hello-camel-service.yaml` — Argo CD `Application` tracking
  [../../apps/java-app/k8s](../../apps/java-app/k8s), auto-sync with
  `prune`+`selfHeal` both on
