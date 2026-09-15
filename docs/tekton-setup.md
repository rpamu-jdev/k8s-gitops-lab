# Tekton: generic build + push pipeline for Java apps

Generic steps to install [Tekton Pipelines](https://tekton.dev/) and wire up
a **build-and-push-only** pipeline, using Kaniko (the same tool used for the
manual builds earlier) and a self-hosted git server (Gitea) as the source.

**Tekton's job here stops at pushing the image.** It does not deploy
anything — no `kubectl set image`, no touching a `Deployment`. Deployment
is Argo CD's job (see [argocd-setup.md](argocd-setup.md)) — specifically
Argo CD Image Updater, which watches the registry this pipeline pushes to
and deploys the new tag automatically. This keeps the boundary clean:
Tekton = CI (build/test/push an artifact), Argo CD = CD (get that
artifact running).

The `Pipeline`/`Task`s are **generic across every Java app in the repo** —
nothing in them names a specific app. Which repo, which subdirectory, and
what image reference to push are all passed in as params by whoever
triggers a run (a manual `PipelineRun`, or a `Trigger`'s `TriggerTemplate`).
Adding a second Java app means a new `PipelineRun`/`TriggerTemplate`
instance with different param values — not a new `Pipeline`.

## Prerequisites

- A container registry the cluster can push to and pull from (see
  [gitea-setup.md](gitea-setup.md))
- **A default `StorageClass`.** Tekton's workspaces need a real
  `PersistentVolumeClaim` shared between the separate pods each `Task`
  runs as — a kubeadm cluster has none by default. This lab uses Rancher's
  lightweight [local-path-provisioner](https://github.com/rancher/local-path-provisioner)
  (the same one k3s bundles internally):

  ```bash
  curl -sL -o local-path-storage.yaml \
    https://raw.githubusercontent.com/rancher/local-path-provisioner/v0.0.37/deploy/local-path-storage.yaml
  kubectl apply -f local-path-storage.yaml
  kubectl patch storageclass local-path \
    -p '{"metadata": {"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}'
  kubectl get storageclass   # should show local-path (default)
  ```

## 1. Install Tekton Pipelines

```bash
curl -sL -o tekton-release.yaml \
  https://github.com/tektoncd/pipeline/releases/download/<version>/release.yaml
kubectl apply -f tekton-release.yaml
kubectl get pods -n tekton-pipelines   # wait for all 1/1 Running
```

This installs the `Task`/`Pipeline`/`TaskRun`/`PipelineRun` CRDs and the
controllers that reconcile them — no dashboard, no triggers (those are
separate installs, not needed for manually-triggered pipelines).

## 2. Registry push credentials

Whatever registry the build pushes to, the push step needs credentials as
a `dockerconfigjson` Secret, mounted directly into the build step (not via
`imagePullSecrets`, which is a different mechanism for *pulling*):

```bash
AUTH=$(echo -n "<user>:<token>" | base64 -w0)
kubectl create secret generic registry-creds \
  --type=kubernetes.io/dockerconfigjson \
  --from-literal=.dockerconfigjson="{\"auths\":{\"<registry-host>:<port>\":{\"auth\":\"$AUTH\"}}}"
```

## 3. Write the Tasks

Two self-written `Task`s rather than pulling from Tekton Hub — keeps the
pipeline fully self-contained, with no runtime dependency on Hub's resolver
reaching out over the network during a build. Both are app-agnostic:

- **git-clone** — a plain `git clone` in an `alpine/git` container into the
  shared workspace. Params: `url`, `revision` (a branch, tag, or any git
  ref).
- **kaniko-build** — builds and pushes with Kaniko, mounting the registry
  secret at `/kaniko/.docker/config.json` via a `Secret` volume with an
  `items` remap (the secret's key is `.dockerconfigjson`, Kaniko expects
  the file literally named `config.json`). Params: `context-subdir` (which
  directory in the cloned repo has the `Dockerfile`), `image` (full
  destination reference), `registry` (host:port needing the insecure-HTTP
  treatment below).

See [../ci/tekton/](../ci/tekton/) for the actual manifests. (A `deploy`
`Task` also exists there from an earlier iteration — kept for possible
future reuse, but **not wired into the `Pipeline`**.)

### A real gotcha: don't use global insecure flags with Kaniko

If your registry needs `--insecure`/`--skip-tls-verify` (plain HTTP, no
TLS — common for a lab registry), use the **registry-scoped** flags instead
of the global ones:

```
--insecure-registry=<registry-host>:<port>
--skip-tls-verify-registry=<registry-host>:<port>
```

The global `--insecure`/`--skip-tls-verify`/`--insecure-pull` flags apply
to **every** registry Kaniko talks to — including Docker Hub while pulling
your Dockerfile's base images — and broke anonymous Docker Hub pulls with
`UNAUTHORIZED: authentication required` in this lab, even though a plain
`pull` of the same image worked fine outside Kaniko. Scoping the insecure
flags to just the one registry that actually needs them avoids this
entirely.

## 4. Write the Pipeline

Chains the two Tasks with `runAfter`, sharing a `source` workspace (backed
by a PVC, via `volumeClaimTemplate` on the `PipelineRun` — a fresh one per
run) between them, plus an optional `maven-cache` workspace (see below)
passed straight through to `kaniko-build`. Every app-specific value (git
URL, revision, Dockerfile subdirectory, image reference) is a
`Pipeline`-level param with no default baked in for the app-specific
ones — the caller must supply them:

See [../ci/tekton/pipeline.yaml](../ci/tekton/pipeline.yaml).

### Persisting `~/.m2` across builds

Without this, every build starts Maven's local repository empty — a
from-scratch `mvn dependency:go-offline` inside the Kaniko build stage,
which took ~9 minutes the first time (see
[my-lab/hello-camel-service-deploy.md](my-lab/hello-camel-service-deploy.md)).
Kaniko runs a Dockerfile's `RUN` commands directly against the pod's real
root filesystem (it's not a nested Docker daemon), so mounting a
**long-lived** PVC at `/root/.m2` in the `kaniko-build` Task makes that
cache genuinely persist between runs — repeat builds only re-download
dependencies that actually changed.

This needs its own PVC, created **once**, separate from the `source`
workspace (which is a fresh `volumeClaimTemplate` per `PipelineRun` by
design — each build gets a clean clone):

See [../ci/tekton/pvc-maven-cache.yaml](../ci/tekton/pvc-maven-cache.yaml).

```bash
kubectl apply -f ci/tekton/pvc-maven-cache.yaml
```

Both the `Task` and `Pipeline` declare this as an `optional: true`
workspace, so a non-Maven app's `PipelineRun`/`TriggerTemplate` can simply
not bind it. A `PipelineRun` that does bind it uses
`persistentVolumeClaim: {claimName: maven-m2-cache}` (an existing PVC by
name), not `volumeClaimTemplate` (a new one per run) — that distinction is
what makes the cache actually persistent.

**Gotcha**: with more than one PVC-backed workspace on a single `Task`,
Tekton's "Affinity Assistant" (a feature that co-schedules a `Task`'s pod
onto whichever node an `RWO` PVC is already bound to, for multi-node
clusters) refuses the `TaskRun` outright with `more than one
PersistentVolumeClaim is bound`. This lab has exactly one worker node, so
Affinity Assistant serves no purpose anyway — disabled it cluster-wide.
The legacy flag for this, `disable-affinity-assistant`, is **superseded**
by `coschedule` in current Tekton — setting only the old one had no
effect (the `coschedule: workspaces` default silently won), so it's the
new key that actually needs the change:

```bash
kubectl -n tekton-pipelines patch configmap feature-flags --type merge \
  -p '{"data":{"coschedule":"disabled"}}'
kubectl -n tekton-pipelines rollout restart deployment tekton-pipelines-controller
```

The ConfigMap patch alone isn't enough — the controller only picks up the
new flag value on the next restart, not on next reconcile.

## 5. Run it manually

```bash
kubectl create -f ci/tekton/pipelinerun.yaml   # generateName, so re-runnable
kubectl get pipelinerun -w
```

A manual `PipelineRun` supplies the app-specific params
(`context-subdir`, `image`, etc.) that the generic `Pipeline` itself
doesn't have defaults for — see
[../ci/tekton/pipelinerun.yaml](../ci/tekton/pipelinerun.yaml) for a
worked example targeting one specific app.

## Watching progress / debugging

```bash
kubectl get pipelinerun,taskrun
kubectl logs -f -l tekton.dev/pipelineTask=<task-name>,tekton.dev/pipelineRun=<run-name> --all-containers
```

A `TaskRun` stuck on `Pending` with reason `TaskRunImagePullFailed` almost
always means a bad image tag in that `Task` — check
`kubectl get taskrun <name> -o jsonpath='{.status.conditions[0].message}'`
for the exact pull error rather than guessing.

## Automatic builds on tag push (Tekton Triggers + a git-server webhook)

Fires the same build-only `Pipeline` automatically whenever a **tag** is
pushed to the git server — not on every ordinary commit. The pushed tag
name becomes the image version, so cutting a release is just
`git tag 1.2.0 && git push <remote> 1.2.0`.

### Install Tekton Triggers

Needs a version matched to your Pipelines version (check the Triggers
release notes — e.g. Triggers v0.37.x pairs with Pipelines v1.15/v1.16.x).
Two manifests: the core controller/webhook, and the interceptors (the
`cel`/`github`/`gitlab`/etc. request-filtering plugins):

```bash
kubectl apply -f https://github.com/tektoncd/triggers/releases/download/<version>/release.yaml
kubectl apply -f https://github.com/tektoncd/triggers/releases/download/<version>/interceptors.yaml
kubectl get pods -n tekton-pipelines   # wait for the new triggers-* pods, 1/1 Running
```

### RBAC for the EventListener

The `EventListener` pod needs its own `ServiceAccount`, bound to the
`ClusterRole`s Triggers ships (`tekton-triggers-eventlistener-roles`,
`tekton-triggers-eventlistener-clusterroles`) so it can create
`PipelineRun`s:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: el-webhook
  namespace: <namespace>
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: el-webhook-eventlistener-roles
subjects:
  - kind: ServiceAccount
    name: el-webhook
    namespace: <namespace>
roleRef:
  kind: ClusterRole
  name: tekton-triggers-eventlistener-roles
  apiGroup: rbac.authorization.k8s.io
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: el-webhook-eventlistener-clusterroles
subjects:
  - kind: ServiceAccount
    name: el-webhook
    namespace: <namespace>
roleRef:
  kind: ClusterRole
  name: tekton-triggers-eventlistener-clusterroles
  apiGroup: rbac.authorization.k8s.io
```

### TriggerBinding, TriggerTemplate, EventListener

`TriggerBinding` pulls fields out of the push webhook's JSON body;
`TriggerTemplate` uses them to stamp out a `PipelineRun` (still supplying
the app-specific params the generic `Pipeline` needs); `EventListener`
ties a binding+template together behind an interceptor that filters which
events actually fire it — here, tag pushes only.

```yaml
apiVersion: triggers.tekton.dev/v1beta1
kind: TriggerBinding
metadata:
  name: git-tag-push-binding
spec:
  params:
    - name: git-repo-url
      value: $(body.repository.clone_url)
    - name: version
      value: $(extensions.tag_name)
```

**Gotcha:** values computed by an interceptor's `overlays` (like the
extracted tag name here) land in a **top-level `extensions` field**,
sibling to `body`/`header` — reference them as `$(extensions.<key>)`, *not*
`$(body.extensions.<key>)`. The interceptor's own logs
(`kubectl -n tekton-pipelines logs -l app.kubernetes.io/component=interceptors`)
show exactly what it computed if a binding silently comes up empty.

```yaml
apiVersion: triggers.tekton.dev/v1beta1
kind: TriggerTemplate
metadata:
  name: <app-name>-tag-trigger-template
spec:
  params:
    - name: git-repo-url
    - name: version
  resourcetemplates:
    - apiVersion: tekton.dev/v1
      kind: PipelineRun
      metadata:
        generateName: <app-name>-build-
      spec:
        pipelineRef:
          name: java-app-build-push
        params:
          - name: git-url
            value: $(tt.params.git-repo-url)
          - name: git-revision
            value: $(tt.params.version)
          - name: context-subdir
            value: <path-to-this-app-in-the-repo>
          - name: image
            value: <registry>/<app-name>:$(tt.params.version)
        workspaces:
          - name: source
            volumeClaimTemplate:
              spec:
                accessModes: ["ReadWriteOnce"]
                resources: { requests: { storage: 1Gi } }
```

Using the pushed tag as both `git-revision` (clone exactly that tag) and
the image tag keeps the built artifact's version traceable straight back
to the git ref that produced it — pushing tag `1.2.0` builds and pushes
`.../app:1.2.0`, nothing computed or guessed.

The `context-subdir`/`image` values here are specific to one app — a
second Java app in the same repo needs its own `TriggerTemplate` (and
usually its own `Trigger` entry, or filter the tag name itself, e.g.
requiring a `<app-name>-` prefix) with different values for those two
fields, still pointing at the same generic `java-app-build-push` `Pipeline`.

```yaml
apiVersion: triggers.tekton.dev/v1beta1
kind: EventListener
metadata:
  name: git-webhook
spec:
  serviceAccountName: el-webhook
  triggers:
    - name: tag-push
      interceptors:
        - ref:
            name: "cel"
          params:
            - name: "filter"
              value: >
                header.match('X-Gitea-Event', 'push') &&
                body.ref.startsWith('refs/tags/')
            - name: "overlays"
              value:
                - key: tag_name
                  expression: "body.ref.split('/')[2]"
      bindings:
        - ref: git-tag-push-binding
      template:
        ref: <app-name>-tag-trigger-template
```

`split` is a Tekton-provided CEL extension function (not standard CEL) —
`body.ref` for a tag push looks like `refs/tags/1.2.0`, so
`.split('/')[2]` pulls out just `1.2.0`. (This assumes tag names don't
contain `/` themselves — true for ordinary version tags.)

The `EventListener` controller auto-creates a `Service` named
`el-<eventlistener-name>` on port `8080`. Route it to a hostname the same
way as anything else (see [k8s-setup.md](k8s-setup.md)'s ingress section):

```yaml
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: git-webhook
spec:
  entryPoints:
    - web
  routes:
    - match: Host(`webhook.<your-domain>`)
      kind: Rule
      services:
        - name: el-<eventlistener-name>
          port: 8080
```

### Configure the webhook on the git server

Gitea (this lab's server): repo → Settings → Webhooks → Add Webhook →
Gitea, with the URL pointing at the hostname above, a shared secret, and
`push` as the trigger event. **Don't scope the branch filter to a branch
name** (e.g. `main`) — tag pushes aren't on any branch, so a branch filter
will silently swallow every tag push. Leave it as `*` (all refs) and let
the `cel` interceptor do the actual filtering.

**Gotcha:** if the git server and the webhook target are both on a private
network (typical for a lab), the server's own SSRF protection may refuse
to deliver the webhook at all — Gitea specifically has an
`ALLOWED_HOST_LIST` setting in `[webhook]` that defaults to blocking
private/loopback address ranges. The failure shows up server-side (check
the git server's own logs, not the cluster) as something like:

```
webhook can only call allowed HTTP servers (check your webhook.ALLOWED_HOST_LIST setting)
```

Fix in Gitea's `app.ini`:

```ini
[webhook]
ALLOWED_HOST_LIST = private,loopback
```

...then restart Gitea. Not needed at all if your git server and cluster
aren't both on private IPs.

### Test without waiting for a real tag push

Gitea has a built-in "send a test delivery" endpoint — but it always
simulates a push to the **default branch**, not a tag push, so it's only
useful for iterating on binding/template wiring in general, not for
testing the tag-specific filter end-to-end:

```bash
curl -X POST "http://<gitea-host>/api/v1/repos/<owner>/<repo>/hooks/<id>/tests" \
  -H "Authorization: token <token>"
```

To actually exercise the tag-push path, push a real (disposable) tag:

```bash
git tag 0.0.0-test
git push <remote> 0.0.0-test
# ... verify, then:
git tag -d 0.0.0-test
git push <remote> :refs/tags/0.0.0-test
```

Then watch for a new `PipelineRun`:

```bash
kubectl get pipelinerun --sort-by=.metadata.creationTimestamp
```

## Cleaning up old PipelineRuns

Nothing in Tekton prunes completed `PipelineRun`/`TaskRun` objects (or
their pods) automatically — with automatic triggers now producing a new
one on every tag push, they accumulate indefinitely if nothing cleans them
up. A `PipelineRun`'s `TaskRun`s and their pods are owned by it
(`ownerReferences`), so deleting the `PipelineRun` cascades to all of them
— the only thing needed is something that periodically deletes old
`PipelineRun`s.

A `CronJob` running `kubectl` (rather than reaching for a dedicated pruner
component) keeps this simple:

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: pipelinerun-pruner
spec:
  schedule: "0 3 * * *"
  jobTemplate:
    spec:
      template:
        spec:
          serviceAccountName: <sa-with-list-and-delete-on-pipelineruns>
          restartPolicy: OnFailure
          containers:
            - name: prune
              image: bitnami/kubectl:latest
              command:
                - /bin/bash
                - -c
                - |
                  set -euo pipefail
                  OLD=$(kubectl get pipelinerun \
                    -o jsonpath='{range .items[*]}{.metadata.creationTimestamp}{" "}{.metadata.name}{"\n"}{end}' \
                    | sort | head -n -5 | awk '{print $2}')
                  [ -z "$OLD" ] && echo "nothing to prune" || echo "$OLD" | xargs -r kubectl delete pipelinerun
```

`sort` (ascending, oldest first) + `head -n -5` (print everything *except*
the last 5 lines) keeps the 5 newest and deletes the rest — adjust the `-5`
for a different retention count, or swap the whole expression for a
time-based cutoff (e.g. anything with a `creationTimestamp` older than 24h)
if count-based retention isn't what you want.

Needs its own `ServiceAccount`/`Role` scoped to just
`get`/`list`/`delete` on `pipelineruns`.

Test on demand rather than waiting for the schedule:

```bash
kubectl create job --from=cronjob/<cronjob-name> prune-test-1
kubectl logs job/prune-test-1
kubectl delete job prune-test-1
```

## Notes / limitations

- **Build+push only, deliberately.** No `deploy` step in the `Pipeline` —
  getting a newly-pushed image actually running is Argo CD's job (see
  [argocd-setup.md](argocd-setup.md)), specifically Argo CD Image
  Updater, which watches the registry this pipeline pushes to and
  deploys automatically. Tekton itself never touches the cluster.
- No webhook signature verification — Tekton Triggers ships interceptors
  for GitHub/GitLab/Bitbucket/Slack signature schemes, but not Gitea's.
  Filtering here is by event type + ref shape only (via the `cel`
  interceptor), which is acceptable when the webhook endpoint is reachable
  only from a private network, not the open internet.
- The build context (source code) is fetched fresh every run (`--depth 1`
  shallow clone) — each `TaskRun` gets a fresh Kaniko pod, so there's no
  Docker layer cache between runs. Dependency downloads specifically
  *are* cached across runs though, via a persistent `~/.m2` PVC — see
  "Speeding up Maven builds" above.
- **Health-probe timings that worked on a freshly-installed cluster can
  stop working as more workloads share the same node.** An app that
  started in ~6s when it was the only thing running grew to 40s+, then
  94-108s, as Traefik, dashboards, Tekton, Triggers, Argo CD, and Image
  Updater all piled onto the same node. A fixed `initialDelaySeconds`
  tuned against an earlier number eventually crash-loops a perfectly
  fine app. The durable fix is a `startupProbe` (gates liveness/readiness
  until the app is actually up, with real tolerance) rather than
  chasing a bigger fixed delay each time — see
  [my-lab/hello-camel-service-deploy.md](my-lab/hello-camel-service-deploy.md).
