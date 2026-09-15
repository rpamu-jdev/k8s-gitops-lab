# Tekton: generic build+push pipeline (used here for hello-camel-service)

Build+push only, deliberately — no deployment happens from here. See
[../../docs/tekton-setup.md](../../docs/tekton-setup.md) for the full
writeup and [../../docs/my-lab/tekton-setup.md](../../docs/my-lab/tekton-setup.md)
for this lab's concrete setup.

`pipeline.yaml` and both `Task`s are **app-agnostic** — nothing in them
names `hello-camel-service`. This directory's `pipelinerun.yaml`,
`trigger-template.yaml`, and `eventlistener.yaml` are the instance that
*does* target this one app; a second Java app in the repo would get its
own copies of those three with different `context-subdir`/`image` values,
reusing the same `pipeline.yaml` and `Task`s unchanged.

## Prerequisites (not in this repo — created directly on the cluster)

1. Tekton Pipelines **and Tekton Triggers** installed (matched versions —
   see [../../docs/tekton-setup.md](../../docs/tekton-setup.md)), a default
   `StorageClass` present.
2. A `dockerconfigjson` Secret named `gitea-registry-creds` in `default`,
   with push credentials for the registry referenced in `pipeline.yaml`'s
   `registry` param default. **Not committed here** — create it directly:

   ```bash
   AUTH=$(echo -n "<user>:<token>" | base64 -w0)
   kubectl create secret generic gitea-registry-creds \
     --type=kubernetes.io/dockerconfigjson \
     --from-literal=.dockerconfigjson="{\"auths\":{\"10.137.160.1:3000\":{\"auth\":\"$AUTH\"}}}"
   ```
3. For automatic builds: a `Secret` named `gitea-webhook-secret` in
   `default` holding the shared token configured on the Gitea webhook
   side. **Also not committed here**:

   ```bash
   kubectl create secret generic gitea-webhook-secret \
     --from-literal=secretToken="$(openssl rand -hex 20)"
   ```

## Apply

```bash
kubectl apply -f task-git-clone.yaml
kubectl apply -f task-kaniko-build.yaml
kubectl apply -f pipeline.yaml
```

(`rbac.yaml` and `task-deploy.yaml` are kept from an earlier iteration
that included deployment — not applied/used anymore, see "Files" below.)

## Run manually

```bash
kubectl create -f pipelinerun.yaml   # generateName - safe to re-run
kubectl get pipelinerun -w
```

`pipelinerun.yaml` supplies the app-specific params (`context-subdir`,
`image`, etc.) that the generic `Pipeline` has no defaults for. Edit the
`image` value (or the whole file) for a different version.

## Automatic builds on tag push

```bash
kubectl apply -f trigger-binding.yaml
kubectl apply -f trigger-template.yaml
kubectl apply -f eventlistener.yaml
kubectl apply -f webhook-ingressroute.yaml
```

(`trigger-rbac.yaml`'s `el-webhook` ServiceAccount is a prerequisite for
the `EventListener` itself, still in active use — unlike `rbac.yaml`.)

Then configure a **tag** push webhook on the git server pointing at the
`IngressRoute`'s hostname, with the same secret token as the
`gitea-webhook-secret` `Secret`, and **no branch filter** (or `*`) — a
branch-scoped filter silently blocks tag pushes entirely. Cut a release
with:

```bash
git tag 1.2.0
git push <remote> 1.2.0
```

See [../../docs/tekton-setup.md](../../docs/tekton-setup.md) for the real
gotchas hit here (an SSRF-protection setting blocking the webhook
delivery, where interceptor-computed values actually live in the event
payload, and why the Gitea webhook's branch filter has to stay wide open).

## Cleaning up old PipelineRuns

Nothing in Tekton prunes completed `PipelineRun`/`TaskRun` objects (or
their pods) on its own — left alone, they accumulate forever.
`prune-cronjob.yaml` runs daily, keeping only the 5 most recent
`PipelineRun`s and deleting the rest; deleting a `PipelineRun` cascades
(via `ownerReferences`) to its `TaskRun`s and their pods automatically:

```bash
kubectl apply -f prune-cronjob.yaml
```

Test it on demand rather than waiting for the schedule:

```bash
kubectl create job --from=cronjob/tekton-pipelinerun-pruner prune-test-1
kubectl logs job/prune-test-1
kubectl delete job prune-test-1
```

## Files

**In active use:**
- `task-git-clone.yaml`, `task-kaniko-build.yaml` — the two build steps,
  fully generic, self-written rather than pulled from Tekton Hub
- `pipeline.yaml` — generic `java-app-build-push`: chains the two Tasks
  with a shared PVC workspace, build+push only
- `pipelinerun.yaml` — manual trigger, targeting `hello-camel-service`
  specifically
- `trigger-rbac.yaml` — `el-webhook` ServiceAccount for the `EventListener`
- `trigger-binding.yaml`, `trigger-template.yaml`, `eventlistener.yaml` —
  the tag-push auto-trigger for `hello-camel-service`: extract the pushed
  tag name, stamp out a `PipelineRun` using it as the image version, only
  fire on `refs/tags/*` pushes
- `webhook-ingressroute.yaml` — routes the `EventListener`'s Service to
  its own hostname
- `prune-cronjob.yaml` — `tekton-pruner` ServiceAccount/Role/RoleBinding +
  a daily `CronJob` keeping only the last 5 `PipelineRun`s

**Kept but currently unused** (from before deployment was removed from
this pipeline):
- `rbac.yaml` — `tekton-deployer` ServiceAccount/Role/RoleBinding
- `task-deploy.yaml` — `kubectl set image` + rollout wait
