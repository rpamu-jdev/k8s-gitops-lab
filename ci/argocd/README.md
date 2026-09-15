# Argo CD Applications (used here for hello-camel-service)

Parallel to [../tekton/](../tekton/): that directory is the CI instance
for this app (build+push, tag-triggered), this directory is the CD
instance (deploy, git-triggered). See
[../../docs/argocd-setup.md](../../docs/argocd-setup.md) for the generic
Argo CD writeup and
[../../docs/my-lab/argocd-setup.md](../../docs/my-lab/argocd-setup.md) for
this lab's concrete setup.

The manifests this `Application` deploys don't live in this repo at all —
they're in a separate
[k8s-gitops-manifests](http://10.137.160.1:3000/rpamu/k8s-gitops-manifests)
repo (`apps/hello-camel-service/`), so Argo CD's sync target is never the
same repo Tekton builds from. Needs its own repo-credentials `Secret` in
`argocd` (same pattern as the one for this repo — see
[../../docs/argocd-setup.md](../../docs/argocd-setup.md) — just pointed
at the manifests repo's URL instead).

## Apply

```bash
kubectl apply -f application-hello-camel-service.yaml
```

A second Java app would get its own `Application` manifest here, pointing
`spec.source.repoURL`/`path` at that app's own directory in
`k8s-gitops-manifests` — nothing in Argo CD's own install (see
[../../infra/argocd/](../../infra/argocd/)) needs to change.

This `Application`'s annotations also drive **Argo CD Image Updater**
(see [../../docs/argocd-setup.md](../../docs/argocd-setup.md)) — it
watches the registry, and on a new semver-looking tag, commits an image
override straight to `k8s-gitops-manifests` itself. Combined with
Tekton's tag-triggered builds, `git tag <version> && git push <remote>
<version>` on this repo is the entire release process: nothing to edit
by hand, nothing to pick in the Argo CD UI.

## Files

- `application-hello-camel-service.yaml` — Argo CD `Application` tracking
  `apps/hello-camel-service/` in the `k8s-gitops-manifests` repo,
  auto-sync with `prune`+`selfHeal` both on, annotated for Image Updater
  (`update-strategy: semver`, `write-back-method: git`)
