# Tekton: build + deploy pipeline

Generic steps to install [Tekton Pipelines](https://tekton.dev/) and wire up
a build-and-deploy pipeline for an app in this lab, using Kaniko (the same
tool used for the manual builds earlier) and a self-hosted git server
(Gitea) as the source.

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

Three self-written `Task`s rather than pulling from Tekton Hub — keeps the
pipeline fully self-contained, with no runtime dependency on Hub's resolver
reaching out over the network during a build:

- **git-clone** — a plain `git clone` in an `alpine/git` container into the
  shared workspace
- **kaniko-build** — builds and pushes with Kaniko, mounting the registry
  secret at `/kaniko/.docker/config.json` via a `Secret` volume with an
  `items` remap (the secret's key is `.dockerconfigjson`, Kaniko expects
  the file literally named `config.json`)
- **deploy** — `kubectl set image` + `kubectl rollout status`, running as a
  dedicated `ServiceAccount` scoped to just `get`/`patch` on `Deployments`
  in its namespace

See [../ci/tekton/](../ci/tekton/) for the actual manifests.

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

Chains the three Tasks with `runAfter`, passing the built image reference
from `build-and-push` through to `deploy`, sharing one workspace (backed by
a PVC, via `volumeClaimTemplate` on the `PipelineRun`) between all three:

See [../ci/tekton/pipeline.yaml](../ci/tekton/pipeline.yaml).

## 5. Run it

```bash
kubectl create -f ci/tekton/pipelinerun.yaml   # generateName, so re-runnable
kubectl get pipelinerun -w
```

Or target the `serviceAccountName` per-task (not the whole run) via
`taskRunSpecs` on the `PipelineRun`, so only the `deploy` step gets the
elevated permissions:

```yaml
taskRunSpecs:
  - pipelineTaskName: deploy
    serviceAccountName: tekton-deployer
```

## Watching progress / debugging

```bash
kubectl get pipelinerun,taskrun
kubectl logs -f -l tekton.dev/pipelineTask=<task-name>,tekton.dev/pipelineRun=<run-name> --all-containers
```

A `TaskRun` stuck on `Pending` with reason `TaskRunImagePullFailed` almost
always means a bad image tag in that `Task` — check
`kubectl get taskrun <name> -o jsonpath='{.status.conditions[0].message}'`
for the exact pull error rather than guessing.

## Automatic builds on push (Tekton Triggers + a git-server webhook)

Fires the same `Pipeline` automatically whenever the git server delivers a
push webhook, instead of running `kubectl create -f pipelinerun.yaml` by
hand every time.

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
`TriggerTemplate` uses them to stamp out a `PipelineRun`; `EventListener`
ties a binding+template together behind an interceptor that filters which
events actually fire it.

```yaml
apiVersion: triggers.tekton.dev/v1beta1
kind: TriggerBinding
metadata:
  name: git-push-binding
spec:
  params:
    - name: git-repo-url
      value: $(body.repository.clone_url)
    - name: short-sha
      value: $(extensions.short_sha)
```

**Gotcha:** values computed by an interceptor's `overlays` (like a
truncated commit SHA) land in a **top-level `extensions` field**, sibling
to `body`/`header` — reference them as `$(extensions.<key>)`, *not*
`$(body.extensions.<key>)`. The interceptor's own logs
(`kubectl -n tekton-pipelines logs -l app.kubernetes.io/component=interceptors`)
show exactly what it computed if a binding silently comes up empty.

```yaml
apiVersion: triggers.tekton.dev/v1beta1
kind: TriggerTemplate
metadata:
  name: build-deploy-trigger-template
spec:
  params:
    - name: git-repo-url
    - name: short-sha
  resourcetemplates:
    - apiVersion: tekton.dev/v1
      kind: PipelineRun
      metadata:
        generateName: <pipeline-name>-auto-
      spec:
        pipelineRef:
          name: <pipeline-name>
        params:
          - name: git-url
            value: $(tt.params.git-repo-url)
          - name: git-revision
            value: main
          - name: image-tag
            value: $(tt.params.short-sha)
        workspaces:
          - name: source
            volumeClaimTemplate:
              spec:
                accessModes: ["ReadWriteOnce"]
                resources: { requests: { storage: 1Gi } }
```

Tagging the built image with the commit's short SHA (rather than reusing a
fixed version string) means every automatic build produces a distinct,
traceable tag and a real rollout — a repeated fixed tag wouldn't actually
change anything on `kubectl set image`.

```yaml
apiVersion: triggers.tekton.dev/v1beta1
kind: EventListener
metadata:
  name: git-webhook
spec:
  serviceAccountName: el-webhook
  triggers:
    - name: push-main
      interceptors:
        - ref:
            name: "cel"
          params:
            - name: "filter"
              value: >
                header.match('X-Gitea-Event', 'push') &&
                body.ref == 'refs/heads/main'
            - name: "overlays"
              value:
                - key: short_sha
                  expression: "body.after.truncate(7)"
      bindings:
        - ref: git-push-binding
      template:
        ref: build-deploy-trigger-template
```

`truncate` is a Tekton-provided CEL extension function (not standard CEL) —
exactly the tool for shortening a commit SHA for use as an image tag.

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
`push` as the trigger event (optionally scoped to a branch filter).

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

### Test without waiting for a real push

Gitea has a built-in "send a test delivery" endpoint that fires a
synthetic-but-realistic push event (real repo, real latest commit) at the
webhook — useful for iterating on the `EventListener`/binding/template
without needing an actual commit each time:

```bash
curl -X POST "http://<gitea-host>/api/v1/repos/<owner>/<repo>/hooks/<id>/tests" \
  -H "Authorization: token <token>"
```

Then watch for a new `PipelineRun`:

```bash
kubectl get pipelinerun --sort-by=.metadata.creationTimestamp
```

## Notes / limitations

- No webhook signature verification — Tekton Triggers ships interceptors
  for GitHub/GitLab/Bitbucket/Slack signature schemes, but not Gitea's.
  Filtering here is by event type + branch only (via the `cel`
  interceptor), which is acceptable when the webhook endpoint is reachable
  only from a private network, not the open internet.
- The build context is fetched fresh every run (`--depth 1` shallow
  clone) — no build caching between runs beyond what's already resident
  in each ephemeral build pod's own layers (none, since each `TaskRun`
  gets a fresh Kaniko pod).
- **Health-probe timings that worked on a freshly-installed cluster can
  stop working as more workloads share the same node.** An app that
  started in ~6s when it was the only thing running can take 40s+ once
  Traefik, dashboards, Tekton's controllers, and Triggers are all
  competing for the same CPU/memory — tight `livenessProbe` windows tuned
  against the earlier number will crash-loop a perfectly fine app. Give
  probes real margin rather than the tightest number that happened to work
  once.
