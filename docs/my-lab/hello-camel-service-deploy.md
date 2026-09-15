# My Lab: hello-camel-service build + deploy + ingress

Concrete record of getting the sample app from source to a browser-reachable
URL in the cluster.

## Build & push (original manual version, since automated by Tekton)

Before Tekton was set up (see [tekton-setup.md](tekton-setup.md)), the
image was built and pushed manually using **Kaniko** run directly via
`nerdctl` on `k8s-node` — chosen specifically because it's the same tool
the eventual Tekton pipeline uses, so this doubled as a dry run. Kept here
as a record of the manual process and the real bug found along the way;
day-to-day builds now go through the pipeline instead
(`kubectl create -f ci/tekton/pipelinerun.yaml`).

```bash
# docker config.json with Gitea registry auth, built from the admin token:
echo '{"auths":{"10.137.160.1:3000":{"auth":"<base64 rpamu:token>"}}}' > /tmp/docker-config.json

# build context copied to the node (scp apps/java-app -> /tmp/java-app-ctx)

sudo nerdctl run --rm \
  -v /tmp/java-app-ctx:/workspace \
  -v /tmp/docker-config.json:/kaniko/.docker/config.json \
  gcr.io/kaniko-project/executor:latest \
  --context=dir:///workspace \
  --dockerfile=/workspace/Dockerfile \
  --destination=10.137.160.1:3000/rpamu/hello-camel-service:1.1.0 \
  --insecure-registry=10.137.160.1:3000 \
  --skip-tls-verify-registry=10.137.160.1:3000
```

**Use the registry-scoped flags** (`--insecure-registry`/
`--skip-tls-verify-registry`), not the global `--insecure`/
`--skip-tls-verify`/`--insecure-pull`. The global flags apply to *every*
registry Kaniko talks to, including Docker Hub while pulling the
`maven`/`eclipse-temurin` base images — that broke the anonymous pull with
`UNAUTHORIZED: authentication required`, even though a plain `nerdctl pull`
of the same image from the same node worked fine. Scoping the insecure
flags to just the Gitea registry (`10.137.160.1:3000`) fixed it — Docker
Hub gets its normal, unmodified auth flow, Gitea gets the insecure-HTTP
treatment it actually needs.

Took about 9 minutes end to end the first time (Kaniko's own image pull + a
from-scratch `mvn dependency:go-offline` inside the build stage, since the
build container starts with an empty `.m2` cache — Maven's `-q` flag then
makes it look "stuck" for several minutes with zero log output; it wasn't,
`nerdctl ps` showing the container still `Up` confirmed it was working).

Verified in the registry:
```bash
curl -H "Authorization: token <token>" http://10.137.160.1:3000/api/v1/packages/rpamu
# -> includes {"name": "hello-camel-service", "version": "1.1.0"}
```

## Deploy

Manifests in [../../apps/java-app/k8s/](../../apps/java-app/k8s/), applied
directly (no Argo CD yet):

```bash
kubectl apply -f configmap.yaml -f deployment.yaml -f service.yaml -f ingressroute.yaml
```

Confirmed `1/1 Running`, landed on `k8s-node`.

Initially deployed to a dedicated `hello-camel-service` namespace, later
**moved to `default`** — deleted that namespace (cascades to everything in
it) and re-applied the same manifests with `namespace: default`.

## Ingress: shared host, path-based routing, no stripping

Traefik (installed as a standard part of cluster setup — see
[k8s-setup.md](k8s-setup.md)) was already running by the time this app was
deployed.

Rather than give this app its own hostname, it's routed off a **shared
host used across all apps in this lab, `api.staging.test`**, distinguished by
path — this app owns the `/sample` prefix.

**Two approaches were tried:**

1. **First**: a plain `Ingress` + a Traefik `Middleware` that stripped the
   `/sample` prefix before forwarding, since the app's routes were under
   `/api/*`. Worked, but added a second resource (the `Middleware`) and an
   easy-to-miss coupling — the middleware-reference annotation embeds the
   `Middleware`'s *namespace*, not just its name
   (`<namespace>-<middleware-name>@kubernetescrd`), so moving either
   resource to a different namespace silently breaks the other unless both
   are updated together.

2. **Switched to**: the app itself now owns its full external path — its
   Camel REST DSL base path is `${BASE_PATH:/sample/api}` instead of
   `/api` (see [../../apps/java-app/src/main/java/com/lab/helloservice/route/HelloRoute.java](../../apps/java-app/src/main/java/com/lab/helloservice/route/HelloRoute.java)).
   Combined with Traefik's **native `IngressRoute` CRD** instead of a plain
   `Ingress`, no `Middleware`/stripping is needed at all — the incoming
   path matches the app's actual route directly:

   ```yaml
   apiVersion: traefik.io/v1alpha1
   kind: IngressRoute
   metadata:
     name: hello-camel-service
     namespace: default
   spec:
     entryPoints:
       - web
     routes:
       - match: Host(`api.staging.test`) && PathPrefix(`/sample`)
         kind: Rule
         services:
           - name: hello-camel-service
             port: 80
   ```

   One fewer resource, one fewer coupling to keep in sync. Trade-off: the
   app now has an opinion about its own external URL path (via `BASE_PATH`)
   rather than being fully agnostic — acceptable here since it's one
   env var, not hardcoded.

## Verified working end-to-end

At the time, Traefik was still `NodePort`-exposed (see
[k8s-setup.md](k8s-setup.md) for the later switch to `hostNetwork`), so
both nodes were checked to confirm `NodePort` really does listen
cluster-wide:

```bash
curl -H "Host: api.staging.test" http://10.137.160.147:32185/sample/api/hello
curl -H "Host: api.staging.test" http://10.137.160.148:32185/sample/api/hello
```

Both nodes answered identically. Response confirmed the `ConfigMap`-
supplied env var took effect — `"environment": "k8s-lab"` (the ConfigMap's
value), not `"local"` (the app's built-in default) — proving the whole
env-var → ConfigMap → pod path works, not just that the pod started.

Also confirmed the scoping is real, not coincidental:

```bash
# hitting /api/hello (the old, pre-BASE_PATH route) on the same host correctly 404s:
curl -o /dev/null -w "%{http_code}\n" -H "Host: api.staging.test" http://10.137.160.147:32185/api/hello
# -> 404

# route registered correctly via Traefik's own API:
curl http://10.137.160.147:31831/api/http/routers | python3 -c "..."
# -> default-hello-camel-service-api-staging-io-sample@kubernetes
#    Host("api.staging.test") && PathPrefix("/sample")
```

**Re-verified after the `hostNetwork` switch**, port dropped entirely:

```bash
curl -H "Host: api.staging.test" http://10.137.160.148/sample/api/hello
# -> 200, same response as before
```

## Status

- [x] Image built and pushed to Gitea registry via Kaniko (v1.1.0)
- [x] App deployed to `default` namespace, `1/1 Running`
- [x] Traefik installed and healthy
- [x] Switched from Ingress+Middleware to native IngressRoute, app owns its
      full path via `BASE_PATH`
- [x] Ingress-routed access verified from both nodes on `api.staging.test/sample`,
      env var config confirmed flowing through, route confirmed in
      Traefik's own API
- [x] `/etc/hosts` entry added on the lab host for `api.staging.test`
      (along with `tekton.`/`dashboard.` — see
      [dashboards-setup.md](dashboards-setup.md)); browser access confirmed
- [x] Build+push automated via Tekton, triggered by tag pushes — see
      [tekton-setup.md](tekton-setup.md). Deliberately build+push only:
      Tekton does not deploy anything here
- [x] Deploy automated via Argo CD instead of manual `kubectl apply`/
      `kubectl set image` — see [argocd-setup.md](argocd-setup.md). A
      change to anything under [k8s/](../../apps/java-app/k8s/) now reaches
      the cluster via `git push`, auto-synced (`prune`+`selfHeal`)
