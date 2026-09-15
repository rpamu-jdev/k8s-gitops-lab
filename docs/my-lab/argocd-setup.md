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

## Status

- [x] Argo CD installed (`v2.13.2`), all core components healthy
- [x] Served plain HTTP (`server.insecure`), routed through Traefik at
      `argocd.staging.test`, no port, matching every other hostname here
- [x] Gitea repo credentials added as an Argo CD repository `Secret`
- [x] `hello-camel-service` `Application` created, auto-sync
      (`prune`+`selfHeal`) on
- [x] Verified true GitOps loop: git push → auto-sync → live cluster
      change, zero manual `kubectl apply`
- [ ] Admin password still the auto-generated initial one — rotate before
      treating this as anything beyond a lab
- [ ] No `Application` yet points at a bumped **image tag** end-to-end
      (the ConfigMap test proved the sync mechanism; the next real release
      should also bump `deployment.yaml`'s image tag to close the loop
      with Tekton's tag-triggered builds)
