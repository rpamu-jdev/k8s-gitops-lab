# Tekton pipeline for hello-camel-service

See [../../docs/tekton-setup.md](../../docs/tekton-setup.md) for the full
writeup and [../../docs/my-lab/tekton-setup.md](../../docs/my-lab/tekton-setup.md)
for this lab's concrete setup.

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
kubectl apply -f rbac.yaml
kubectl apply -f task-git-clone.yaml
kubectl apply -f task-kaniko-build.yaml
kubectl apply -f task-deploy.yaml
kubectl apply -f pipeline.yaml
```

## Run manually

```bash
kubectl create -f pipelinerun.yaml   # generateName - safe to re-run
kubectl get pipelinerun -w
```

Bump `image-tag` in `pipelinerun.yaml` (or override at the command line
with `kubectl create -f pipelinerun.yaml --dry-run=client -o yaml | ...` /
just edit the file) for a new build.

## Automatic builds on push to `main`

```bash
kubectl apply -f trigger-rbac.yaml
kubectl apply -f trigger-binding.yaml
kubectl apply -f trigger-template.yaml
kubectl apply -f eventlistener.yaml
kubectl apply -f webhook-ingressroute.yaml
```

Then configure a push webhook on the git server pointing at the
`IngressRoute`'s hostname, with the same secret token as the
`gitea-webhook-secret` `Secret`. See
[../../docs/tekton-setup.md](../../docs/tekton-setup.md) for the two real
gotchas hit here (an SSRF-protection setting blocking the webhook
delivery, and where interceptor-computed values actually live in the
event payload).

## Files

- `rbac.yaml` — `tekton-deployer` ServiceAccount/Role/RoleBinding, used
  only by the `deploy` Task
- `task-git-clone.yaml`, `task-kaniko-build.yaml`, `task-deploy.yaml` —
  the three build steps, self-written rather than pulled from Tekton Hub
- `pipeline.yaml` — chains the three Tasks with a shared PVC workspace
- `pipelinerun.yaml` — manual trigger template
- `trigger-rbac.yaml` — `el-webhook` ServiceAccount for the `EventListener`
- `trigger-binding.yaml`, `trigger-template.yaml`, `eventlistener.yaml` —
  the automatic-trigger pipeline: extract fields from the push payload,
  stamp out a `PipelineRun`, filter which events fire it
- `webhook-ingressroute.yaml` — routes the `EventListener`'s Service to
  its own hostname
