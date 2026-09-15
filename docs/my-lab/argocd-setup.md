# My Lab: Argo CD setup

Concrete record of standing up Argo CD as this lab's CD layer, completing
the split started when Tekton was cut back to build+push only (see
[tekton-setup.md](tekton-setup.md)).

## Install

```bash
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/v2.13.2/manifests/install.yaml
```

Waited on all six Deployments (`argocd-server`, `argocd-repo-server`,
`argocd-dex-server`, `argocd-applicationset-controller`, `argocd-redis`,
`argocd-notifications-controller`) before touching anything — all came up
clean, no probe-timing issues this time (unlike `hello-camel-service`
under node contention).

## Plain HTTP, own hostname

```bash
kubectl -n argocd patch configmap argocd-cmd-params-cm --type merge \
  -p '{"data":{"server.insecure":"true"}}'
kubectl -n argocd rollout restart deployment argocd-server
```

Then [../../infra/argocd/ingressroute.yaml](../../infra/argocd/ingressroute.yaml)
routes `argocd.staging.test` → `argocd-server:80`, plain HTTP straight
through — no `ServersTransport` needed here, unlike the Kubernetes
Dashboard (which keeps its self-signed cert). Verified:

```bash
curl -H "Host: argocd.staging.test" http://10.137.160.148/
# -> 200
```

`/etc/hosts` entry added alongside the others (`api.`, `tekton.`,
`dashboard.`, now `argocd.`, all pointed at `10.137.160.148`, the node
Traefik's `hostNetwork` is pinned to).

Initial admin password:
```bash
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath='{.data.password}' | base64 -d
```

## Repo credentials

Gitea repo is private, so Argo CD needs a credentials `Secret` (not
committed — built from the same Gitea token already used for the git
remote and the registry pull secret):

```bash
kubectl apply -f - <<'EOF'
apiVersion: v1
kind: Secret
metadata:
  name: gitea-k8s-gitops-lab
  namespace: argocd
  labels:
    argocd.argoproj.io/secret-type: repository
stringData:
  type: git
  url: http://10.137.160.1:3000/rpamu/k8s-gitops-lab.git
  username: rpamu
  password: <token>
EOF
```

## Application

[../../ci/argocd/application-hello-camel-service.yaml](../../ci/argocd/application-hello-camel-service.yaml)
tracks `apps/java-app/k8s`, destination namespace `default`, auto-sync
with `prune`+`selfHeal` both on. Applied directly:

```bash
kubectl apply -f ci/argocd/application-hello-camel-service.yaml
```

Came up `OutOfSync` for a few seconds, then self-triggered a sync to
`Synced`/`Progressing`/`Healthy` — even though the live Deployment already
matched git byte-for-byte (it had been `kubectl apply`'d manually before
Argo CD existed). Argo CD's own tracking label
(`app.kubernetes.io/instance: hello-camel-service`) landed on the pod
template, which counted as a spec change and rolled the Deployment once
(old pod terminated, new one came up) — a one-time adoption cost, not a
recurring thing.

## End-to-end GitOps test

Edited [../../apps/java-app/k8s/configmap.yaml](../../apps/java-app/k8s/configmap.yaml),
`GREETING_PREFIX: "Hello"` → `"Namaste"`, committed, pushed to `gitea`
`main` — **no `kubectl apply` at all**:

```bash
git commit -am "Test Argo CD auto-sync: bump greeting prefix via ConfigMap"
git push gitea main
```

Forced an immediate reconcile instead of waiting for the default 3-minute
poll:

```bash
kubectl -n argocd annotate application hello-camel-service \
  argocd.argoproj.io/refresh=hard --overwrite
```

Confirmed the live `ConfigMap` picked up the new value with zero manual
cluster commands:

```bash
kubectl get configmap hello-camel-service-config -o jsonpath='{.data}'
# -> {"APP_ENVIRONMENT":"k8s-lab","GREETING_PREFIX":"Namaste"}
```

`ConfigMap` changes don't auto-restart pods that consume them as env vars
(expected Kubernetes behavior, not an Argo CD gap), so
`kubectl rollout restart deployment hello-camel-service` was still needed
to pick it up in a running pod — hit the same probe-timing slowness
documented in [hello-camel-service-deploy.md](hello-camel-service-deploy.md)
(`rollout status` timed out at 90s waiting on readiness, pod was actually
fine, just slow to pass its probe under node load). Confirmed end to end:

```bash
curl -H "Host: api.staging.test" http://10.137.160.148/sample/api/hello
# -> {"message": "Namaste from hello-camel-service (k8s-lab)!"}
```

Pushed the same commit to `origin` (GitHub) too, for parity — Argo CD only
watches the `gitea` remote.

## Splitting manifests into their own repo

The above test lived in `k8s-gitops-lab` itself
(`apps/java-app/k8s/`) — fine for a first working sync, but wrong
long-term: Tekton's build/push pipeline and Argo CD's sync target were
the same repo, so a future CI-side commit there could in principle race
or interfere with what Argo CD watches. Moved the manifests out to a new
repo, [k8s-gitops-manifests](http://10.137.160.1:3000/rpamu/k8s-gitops-manifests)
(created via the Gitea UI, `rpamu/k8s-gitops-manifests`), laid out as
`apps/hello-camel-service/*.yaml` — same four files
(`configmap.yaml`/`deployment.yaml`/`service.yaml`/`ingressroute.yaml`),
just relocated (and the `ConfigMap`'s comment referencing this repo's own
`ci/argocd/` fixed, since that path doesn't resolve from the new repo).

```bash
# new repo pushed with git init/add/commit/push, same rpamu Gitea token
git remote add origin http://rpamu:<token>@10.137.160.1:3000/rpamu/k8s-gitops-manifests.git
git push -u origin main
```

Argo CD needed a **second** repository-credentials `Secret` (repo
credentials are per exact URL, not shared across repos on the same
host):

```bash
kubectl apply -f - <<'EOF'
apiVersion: v1
kind: Secret
metadata:
  name: gitea-k8s-gitops-manifests
  namespace: argocd
  labels:
    argocd.argoproj.io/secret-type: repository
stringData:
  type: git
  url: http://10.137.160.1:3000/rpamu/k8s-gitops-manifests.git
  username: rpamu
  password: <token>
EOF
```

Then updated the `Application`'s `source.repoURL`/`source.path` to point
at the new repo/directory (see
[../../ci/argocd/application-hello-camel-service.yaml](../../ci/argocd/application-hello-camel-service.yaml)),
`kubectl apply`'d it, forced a refresh, and confirmed `Synced`/`Healthy`
against the new source with zero disruption to the running app (same
manifests, same content, different repo — no rollout triggered this
time, unlike the original adoption). App still answered correctly
afterward:

```bash
curl -H "Host: api.staging.test" http://10.137.160.148/sample/api/hello
# -> {"message": "Namaste from hello-camel-service (k8s-lab)!"}
```

The original `apps/java-app/k8s/` directory in `k8s-gitops-lab` was then
deleted — a second Java app added later would get its own
`apps/<app-name>/` directory in the manifests repo plus its own
`Application`, following the same pattern.

## Rotating the admin password

The `argocd-server` pod ships the `argocd` CLI itself, so no local install
or extra download was needed — hashed the new password inside the pod and
patched the secret directly:

```bash
argocd account bcrypt --password '<new-password>'   # run via: kubectl -n argocd exec deploy/argocd-server -- argocd account bcrypt --password '<new-password>'

kubectl -n argocd patch secret argocd-secret -p \
  '{"stringData": {"admin.password": "<bcrypt-hash>", "admin.passwordMtime": "<RFC3339-timestamp>"}}'

kubectl -n argocd delete secret argocd-initial-admin-secret
```

Deleting `argocd-initial-admin-secret` is Argo CD's own documented
cleanup step once a real password is set — the bootstrap secret otherwise
sticks around holding the old auto-generated password indefinitely.
Verified the new password logs in via `argocd login
argocd-server.argocd.svc.cluster.local:80 --plaintext`. New password
handed to the lab owner directly, not stored in this repo.

## Closing the loop: Argo CD Image Updater

Up to this point, deploying a newly-built image tag still meant manually
editing `deployment.yaml` in the manifests repo — the last gap between
"Tekton pushed an image" and "it's actually running." Installed
[Argo CD Image Updater](../argocd-setup.md) (`v0.15.1`) to remove that
step entirely.

First added a minimal `kustomization.yaml` to
`apps/hello-camel-service/` in `k8s-gitops-manifests` (see
[../argocd-setup.md](../argocd-setup.md) for why this is the mechanism
Image Updater needs) — pushed, then force-refreshed the `Application` and
confirmed Argo CD auto-detected it as a Kustomize source and stayed
`Synced`/`Healthy` with zero disruption (same image tag, just a different
render path).

Installed Image Updater:
```bash
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj-labs/argocd-image-updater/v0.15.1/manifests/install.yaml
```

Configured it to reach Gitea's registry — plain HTTP, so `insecure: true`
was required, and its RBAC only reads Secrets in the `argocd` namespace,
so the existing `default`-namespace `gitea-registry-creds`
`dockerconfigjson` Secret needed a duplicate in `argocd` too:

```bash
kubectl -n argocd create secret generic gitea-registry-creds \
  --type=kubernetes.io/dockerconfigjson \
  --from-literal=.dockerconfigjson='<same content as the default-namespace one>'
```
```yaml
# argocd-image-updater-config ConfigMap, registries.conf key
registries:
  - name: gitea
    api_url: http://10.137.160.1:3000
    prefix: 10.137.160.1:3000
    ping: false
    insecure: true
    credentials: pullsecret:argocd/gitea-registry-creds
```

Annotated the `Application` (see
[../../ci/argocd/application-hello-camel-service.yaml](../../ci/argocd/application-hello-camel-service.yaml)):
`image-list: hcs=10.137.160.1:3000/rpamu/hello-camel-service`,
`hcs.update-strategy: semver`,
`hcs.allow-tags: regexp:^[0-9]+\.[0-9]+\.[0-9]+$` (so it only ever picks
up genuine release-looking tags, never a stray SHA or `latest`), and
`write-back-method: git`.

**Worked on the very first poll cycle, no extra fixing needed** — within
2 minutes of annotating, the log showed:
```
Setting new image to 10.137.160.1:3000/rpamu/hello-camel-service:1.1.1
Committing 1 parameter update(s) for application hello-camel-service
git push origin main
Successfully updated the live application spec
```
It found `1.1.1` (an image already sitting in the registry from earlier
webhook testing) as the highest semver tag, and wrote
`apps/hello-camel-service/.argocd-source-hello-camel-service.yaml`
(a parameter-override file Argo CD's Kustomize renderer layers on top of
the base `kustomization.yaml` automatically — the base file itself was
never touched) into `k8s-gitops-manifests`, committed and pushed as
`argocd-image-updater <noreply@argoproj.io>`. A forced Argo CD refresh
picked that commit up and rolled the Deployment to `1.1.1`.

### Full real end-to-end test

To prove the *entire* chain — not just Image Updater's half — ran a real
release with nothing but a tag push:

```bash
git tag 1.2.0
git push gitea 1.2.0
```

Watched it happen unattended:
1. Gitea webhook fired the Tekton `EventListener` (same tag-push trigger
   from [tekton-setup.md](tekton-setup.md)) — a `PipelineRun` started
   within seconds, and finished `Succeeded` with `1.2.0` pushed to the
   registry.
2. Argo CD Image Updater's next poll cycle picked up `1.2.0` (now the
   highest semver tag), committed the override to `k8s-gitops-manifests`.
3. Forced an Argo CD refresh (would otherwise happen automatically within
   the ~3-minute poll) — synced, rolled the Deployment.
4. Confirmed live:
   ```bash
   kubectl get pod -l app=hello-camel-service \
     -o jsonpath='{.items[0].spec.containers[0].image}'
   # -> 10.137.160.1:3000/rpamu/hello-camel-service:1.2.0
   curl -H "Host: api.staging.test" http://10.137.160.148/sample/api/hello
   # -> {"message": "Namaste from hello-camel-service (k8s-lab)!"}
   ```

Also visible in the Argo CD UI mid-rollout: a brief run of
`Unhealthy`/liveness-probe-failed events on the new pod (same
slow-startup-under-node-load symptom documented in
[hello-camel-service-deploy.md](hello-camel-service-deploy.md) —
transient, not a real failure) before settling to `Healthy`.

**End result: `git tag 1.2.0 && git push gitea 1.2.0` is now the entire
release process.** No manual manifest edit, no `kubectl apply`, no
picking an image in the Argo CD UI (which isn't a workflow Argo CD
supports anyway — it has no "browse the registry and deploy this one"
UI; the only correct way to change what's deployed is still a git
commit, which is exactly what Image Updater automates).

## Status

- [x] Argo CD installed (`v2.13.2`), all core components healthy
- [x] Served plain HTTP (`server.insecure`), routed through Traefik at
      `argocd.staging.test`, no port, matching every other hostname here
- [x] Gitea repo credentials added as an Argo CD repository `Secret`
      (one per repo: `k8s-gitops-lab` and `k8s-gitops-manifests`)
- [x] `hello-camel-service` `Application` created, auto-sync
      (`prune`+`selfHeal`) on
- [x] Verified true GitOps loop: git push → auto-sync → live cluster
      change, zero manual `kubectl apply`
- [x] Admin password rotated off the auto-generated initial one, bootstrap
      secret deleted
- [x] Deploy manifests split into their own repo
      (`k8s-gitops-manifests`), separate from source/CI — `Application`
      repointed, verified `Synced`/`Healthy` with no disruption
- [x] Argo CD Image Updater installed and wired up (`semver` strategy,
      `git` write-back) — the image-tag bump into `deployment.yaml`'s
      effective value is now automatic
- [x] Full release loop verified for real: `git tag && git push` alone
      (no manual deploy step of any kind) took a new version from source
      to running pod — Tekton build → registry → Image Updater commit →
      Argo CD sync, unattended end to end
