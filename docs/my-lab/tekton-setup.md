# My Lab: Tekton build + push pipeline

Concrete record following [../tekton-setup.md](../tekton-setup.md).

**Current state: build+push only, triggered by tag pushes.** The pipeline
originally also deployed (see the history below) — deliberately removed;
Tekton's job here stops at pushing an image. See "Switched to build+push
only, tag-triggered" further down for what changed and why.

## Source: pushed to Gitea, not GitHub

The pipeline clones from this lab's own Gitea instance
(`http://10.137.160.1:3000/rpamu/k8s-gitops-lab.git`, public repo, no clone
auth needed) rather than GitHub — chosen specifically to keep pipeline runs
independent of the host's occasionally-flaky mobile-hotspot DNS/network
(see [vm-setup.md](vm-setup.md)'s gotchas). Pushed once via:

```bash
git remote add gitea http://rpamu:<token>@10.137.160.1:3000/rpamu/k8s-gitops-lab.git
git push gitea main
```

## Installed

- **Tekton Pipelines v1.16.0** — all pods `1/1 Running` in `tekton-pipelines`
  (`tekton-pipelines-controller`, `tekton-events-controller`,
  `tekton-pipelines-webhook`) plus `tekton-pipelines-remote-resolvers` in
  `tekton-pipelines-resolvers`
- **local-path-provisioner v0.0.37** — needed first, since kubespray ships
  no default `StorageClass` and Tekton's shared workspace between
  `git-clone` → `kaniko-build` → `deploy` (three separate pods) needs a
  real `PersistentVolumeClaim`, not an `emptyDir`. Set as the default
  `StorageClass`.
- Registry push secret `gitea-registry-creds` (`default` namespace),
  built from the same Gitea admin token used for manual pushes earlier.
- `tekton-deployer` `ServiceAccount` + `Role` (`get`/`list`/`watch`/
  `patch`/`update` on `Deployments`, `get`/`list`/`watch` on `Pods`, both
  scoped to `default`) — used only by the `deploy` `Task`, via
  `taskRunSpecs` on the `PipelineRun`.

Manifests in [../../ci/tekton/](../../ci/tekton/).

## What went wrong the first run (one real bug found)

The `deploy` `Task` originally used `bitnami/kubectl:1.31` — that tag
doesn't exist (Bitnami's kubectl images aren't tagged with bare minor
versions). The pull failed with:

```
unexpected media type text/html ... not found
```

— Docker Hub returning an HTML 404 page where an image manifest was
expected, surfaced by containerd as a confusing "media type" error rather
than a plain "not found." Fixed by switching to `bitnami/kubectl:latest`
(verified it actually pulls before changing the Task). Re-ran just the
`deploy` step in isolation as a standalone `TaskRun` first (cheaper than
re-running the whole ~6.5 minute Kaniko build to test a one-line fix),
confirmed it worked, then ran the full `Pipeline` clean end-to-end to be
sure.

## Verified working end-to-end

Full pipeline run (`fetch-source` → `build-and-push` → `deploy`), clean,
no manual steps in between:

```
NAME                                     SUCCEEDED   REASON
hello-camel-service-build-deploy-nnm8z   True        Succeeded
```

All three `TaskRuns` `True/Succeeded`. Total time ~8.5 minutes (dominated
by Kaniko's `mvn dependency:go-offline` against a cold `.m2` cache — same
as the manual builds; nothing here is Tekton-specific overhead).

Confirmed the deployed pod was actually reached, not just that the
`Task`s reported success (Traefik was still `NodePort`-exposed at the
time — see [k8s-setup.md](k8s-setup.md) for the later switch to
`hostNetwork`, which dropped the port number entirely):

```bash
curl -H "Host: api.staging.test" http://10.137.160.147:32185/sample/api/hello
curl -H "Host: api.staging.test" http://10.137.160.147:32185/sample/api/version
# -> version: "1.1.0", environment: "k8s-lab" (from the live ConfigMap)
```

## Automatic builds on push

Following [../tekton-setup.md](../tekton-setup.md)'s Triggers section.
Installed **Tekton Triggers v0.37.0** (core + interceptors), both healthy
in `tekton-pipelines`.

Resources in [../../ci/tekton/](../../ci/tekton/) (`trigger-rbac.yaml`,
`trigger-binding.yaml`, `trigger-template.yaml`, `eventlistener.yaml`,
`webhook-ingressroute.yaml`) — the `TriggerTemplate` reuses the same
`hello-camel-service-build-deploy` `Pipeline` used for manual runs, tagging
the built image with the pushed commit's short SHA instead of a fixed
version string.

Webhook endpoint exposed at `http://webhook.staging.test/` (own hostname,
same as the dashboards — see [dashboards-setup.md](dashboards-setup.md)).
The webhook secret token lives only in the applied `Secret` and in Gitea's
webhook config — **not committed anywhere** in this repo.

### Two real bugs hit setting this up

1. **Gitea refused to deliver the webhook at all**, failing server-side
   (visible in `journalctl -u gitea`, not anywhere in the cluster) with:
   ```
   webhook can only call allowed HTTP servers (check your webhook.ALLOWED_HOST_LIST setting)
   ```
   Gitea's SSRF protection blocks webhook targets on private/loopback
   ranges by default — and `webhook.staging.test` resolves to
   `10.137.160.148`, a private address. Fixed in `~/gitea/app.ini`:
   ```ini
   [webhook]
   ALLOWED_HOST_LIST = private,loopback
   ```
   followed by `sudo systemctl restart gitea`.

2. **The `TriggerBinding` came up with an empty `short-sha`** even though
   the `cel` interceptor's own logs
   (`kubectl -n tekton-pipelines logs -l app.kubernetes.io/component=interceptors`)
   clearly showed it computing the value correctly
   (`Extensions:map[short_sha:69aa601]`). The binding referenced
   `$(body.extensions.short_sha)` — wrong: interceptor extensions land in
   a **top-level `extensions` field**, not nested under `body`. Fixed by
   changing the binding to `$(extensions.short_sha)`.

### Verified working end-to-end

Tested via Gitea's built-in test-delivery endpoint (realistic synthetic
push event using the actual latest commit) rather than waiting for a real
push:

```bash
curl -X POST "http://localhost:3000/api/v1/repos/rpamu/k8s-gitops-lab/hooks/1/tests" \
  -H "Authorization: token <token>"
```

Produced a real `PipelineRun` (`hello-camel-service-auto-4mpnc`) with no
manual step in between. `fetch-source` and `build-and-push` succeeded;
image `69aa601` (the actual short SHA of the triggering commit) confirmed
pushed to the registry:

```bash
curl -H "Authorization: token <token>" http://10.137.160.1:3000/api/v1/packages/rpamu
# -> includes {"name": "hello-camel-service", "version": "69aa601"}
```

### A third issue, unrelated to Triggers itself

The `deploy` `TaskRun` from that automatic run failed on rollout timeout —
not a Triggers/webhook problem, but the app's own probe timings no longer
matching reality. `hello-camel-service` now takes 40s+ to start rather than
the ~6s it took on a freshly-installed cluster, because Traefik, both
dashboards, Tekton's controllers, and Triggers are all now sharing the
same node's CPU. The `livenessProbe`'s `initialDelaySeconds: 20` +
3-failure default gave it only ~50s before being killed — a crash loop,
confirmed via `kubectl logs --previous` showing the app fully starting
("Started HelloServiceApplication") right as it received a graceful
shutdown signal. Fixed by widening both probes in
`apps/java-app/k8s/deployment.yaml` (`livenessProbe.initialDelaySeconds:
60`, `failureThreshold: 6`; `readinessProbe.failureThreshold: 12`) — real
margin instead of the tightest number that happened to work once, when the
node was quieter.

Later confirmed with an actual `git push` (not just the test-delivery
endpoint) — pushing the commit documenting all of this triggered a real
`PipelineRun` with zero manual steps, which built, pushed, and deployed
that exact commit's image (tag matched the push's own short SHA), and the
widened probes held up cleanly (no crash loop, `0` restarts).

## Switched to build+push only, tag-triggered

The pipeline above (`hello-camel-service-build-deploy`) built, pushed,
*and* deployed on every push to `main`. Changed to match how this lab
actually wants to work: **Tekton builds and pushes only; deployment is a
separate concern** (Argo CD, set up later — see
[argocd-setup.md](argocd-setup.md) — manual `kubectl` in the meantime),
and **a build only happens when a tag is pushed** — not on every ordinary
commit — with the image version coming from the tag itself.

- **`Pipeline` renamed and genericized**: `hello-camel-service-build-deploy`
  → `java-app-build-push`. Dropped the `deploy` `Task` from the chain
  entirely (the `Task` definition still exists in
  [../../ci/tekton/task-deploy.yaml](../../ci/tekton/task-deploy.yaml),
  just unused). Lifted `context-subdir` and the image's repo path out of
  the `build-and-push` step's hardcoded values into `Pipeline`-level
  params with no default — nothing in `pipeline.yaml` or the two `Task`s
  names `hello-camel-service` anymore. A second Java app in this repo
  reuses the exact same `Pipeline`/`Task`s with different param values.
- **Trigger switched from branch pushes to tag pushes**: the `cel`
  filter changed from `body.ref == 'refs/heads/main'` to
  `body.ref.startsWith('refs/tags/')`, and the overlay that used to
  truncate the commit SHA now extracts the tag name instead
  (`body.ref.split('/')[2]`) — renamed `TriggerBinding`/`TriggerTemplate`
  to `gitea-tag-push-binding`/`hello-camel-service-tag-trigger-template`
  to match.
- **Gitea webhook's `branch_filter` changed from `main` to `*`** — a
  `branch_filter` scoped to a branch name silently swallows tag pushes
  entirely (they aren't on any branch), so it has to be wide open and let
  the `cel` interceptor do the actual filtering instead.

### Verified: real tag push, build-only

```bash
git tag 1.1.1
git push gitea 1.1.1
```

Produced `hello-camel-service-build-6ds65` — note the new naming
(`-build-`, not `-auto-`), confirming the updated `TriggerTemplate` was in
effect. Params on the resulting `PipelineRun` confirmed the tag flowed
through correctly:

```json
[{"name":"git-revision","value":"1.1.1"},
 {"name":"image","value":"10.137.160.1:3000/rpamu/hello-camel-service:1.1.1"}]
```

Only two `TaskRun`s existed (`fetch-source`, `build-and-push`) — no
`deploy`. Both succeeded, image `1.1.1` confirmed pushed to the registry,
and — the actual point of this change — the live `Deployment` still showed
the *previous* image the whole time:

```bash
kubectl get deployment hello-camel-service -o jsonpath='{.spec.template.spec.containers[0].image}'
# -> 10.137.160.1:3000/rpamu/hello-camel-service:77daf51 (unchanged)
```

Test tag and its build (`1.1.1`) were cleaned up afterward — a throwaway
verification tag, not a real release. Gitea delivered the webhook a second
time (delayed/duplicate delivery, arriving after the tag was already
deleted), producing one extra failed `PipelineRun` — `git clone` correctly
failed since `1.1.1` no longer existed by then. Not a bug in this setup,
just an artifact of deleting the test tag quickly after pushing it;
cleaned up manually rather than waiting for the daily pruner.

## Persisting `~/.m2` across builds

Every build was starting Maven's local repo from empty — a from-scratch
`dependency:go-offline` inside the Kaniko build stage, ~9 minutes end to
end the first time (see
[hello-camel-service-deploy.md](hello-camel-service-deploy.md)). Added a
long-lived `PersistentVolumeClaim` (`ci/tekton/pvc-maven-cache.yaml`,
`maven-m2-cache`, 5Gi, `local-path`), mounted at `/root/.m2` in
`kaniko-build` as an optional `maven-cache` workspace — since Kaniko runs
`RUN` commands directly against the pod's real root filesystem (not a
nested Docker daemon), a real PVC mount there is genuinely visible to
`mvn`, and unlike `source` (a fresh `volumeClaimTemplate` per run) it's
bound by claim name, so its contents actually survive between builds.

Hit a real bug getting this working: a `TaskRun` binding two different
PVC-backed workspaces (`source` + `maven-cache`) failed immediately with
`[User error] more than one PersistentVolumeClaim is bound` — Tekton's
"Affinity Assistant" (co-schedules a `Task`'s pod onto whichever node an
`RWO` PVC is already bound to, for multi-node clusters) doesn't support
more than one PVC-backed workspace per `Task`. This lab has exactly one
worker node, so Affinity Assistant buys nothing here — tried disabling it
via the documented `disable-affinity-assistant: "true"` feature flag,
which had **no effect**; turned out this Tekton version's `feature-flags`
ConfigMap already had `coschedule: workspaces` set (the flag that
superseded `disable-affinity-assistant`), which silently overrode it.
Setting `coschedule: disabled` instead, plus restarting
`tekton-pipelines-controller` (the flag doesn't take effect on next
reconcile, only next controller start), fixed it:

```bash
kubectl -n tekton-pipelines patch configmap feature-flags --type merge \
  -p '{"data":{"coschedule":"disabled"}}'
kubectl -n tekton-pipelines rollout restart deployment tekton-pipelines-controller
```

Measured directly with two back-to-back manual `PipelineRun`s (same
`pom.xml`, so it's purely the cache effect, not "fewer dependencies"):
`build-and-push` `TaskRun` duration went from **4m10s (cold, empty
`.m2`)** to **2m01s (warm, cache already populated)** — a ~2x speedup,
with the remainder mostly base-image pulls and the JRE runtime-stage copy,
which the `.m2` cache doesn't touch.

## Cleaning up old PipelineRuns

With automatic triggers now producing a new `PipelineRun` on every push,
they'd accumulate forever without something pruning them (see
[../tekton-setup.md](../tekton-setup.md)). Installed a daily `CronJob`
(`ci/tekton/prune-cronjob.yaml`) keeping the 5 most recent, with its own
narrowly-scoped `tekton-pruner` ServiceAccount (`get`/`list`/`delete` on
`pipelineruns` only — not reusing the `deploy` Task's `ServiceAccount`).

Tested on demand rather than waiting for the 03:00 schedule:
```bash
kubectl create job --from=cronjob/tekton-pipelinerun-pruner prune-test-1
kubectl logs job/prune-test-1
# -> "Nothing to prune (5 or fewer PipelineRuns)." — correct, only 1 existed
kubectl delete job prune-test-1
```

## Status

- [x] Tekton Pipelines installed and healthy
- [x] local-path-provisioner installed, set as default StorageClass
- [x] Repo pushed to Gitea, used as the pipeline's git source
- [x] git-clone / kaniko-build Tasks written and working, fully generic
      (no app name baked in)
- [x] Pipeline (`java-app-build-push`) chains build+push only — deploy
      removed by design
- [x] Tekton Triggers + Gitea webhook installed, switched to tag-push
      triggering, verified end-to-end with a real tag push
- [x] Tekton Dashboard installed, reachable at `tekton.staging.test` (see
      [dashboards-setup.md](dashboards-setup.md))
- [x] Probe timings widened to tolerate node-wide resource contention —
      superseded by a `startupProbe`-based fix once the earlier fixed
      delays started flaking again under heavier contention; see
      [hello-camel-service-deploy.md](hello-camel-service-deploy.md)
- [x] Daily pruning CronJob for old PipelineRuns, keeping the last 5
- [x] Persistent `~/.m2` cache for Maven builds (see
      [tekton-setup.md](../tekton-setup.md) and above) — verified via a
      much faster second build with the same `pom.xml`
- [x] Argo CD deploys `hello-camel-service` automatically from git, and
      Argo CD Image Updater closes the last gap — a Tekton-pushed tag
      gets committed as an image-tag bump automatically, no manual step
      anywhere from `git tag` to a running pod; see
      [argocd-setup.md](argocd-setup.md)
