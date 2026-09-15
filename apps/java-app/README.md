# hello-camel-service

Sample REST API used as the end-to-end deployment target for this lab's
Tekton pipeline + Argo CD setup.

- **Stack**: Java 17, Spring Boot 4.1.1, Apache Camel 4.22.0 (REST DSL over
  the `platform-http` component, so it runs on Spring's embedded server with
  no separate servlet mapping)
- **Endpoints**:
  - `GET /sample/api/hello` → `{"message": "<prefix> from <name> (<environment>)!"}`
  - `GET /sample/api/version` → `{"name", "version", "environment", "javaVersion", "camelVersion"}`
  - `GET /actuator/health` → Spring Boot Actuator health (liveness/readiness
    probes split, wired as k8s probes — see the deploy manifests, now in
    the separate [k8s-gitops-manifests](http://10.137.160.1:3000/rpamu/k8s-gitops-manifests)
    repo)
- **Config via env vars** (all optional, sensible defaults if unset):
  - `GREETING_PREFIX` — default `Hello`
  - `APP_ENVIRONMENT` — default `local`; set to e.g. `k8s-lab` via the
    Kubernetes `ConfigMap` in `k8s-gitops-manifests`'s
    `apps/hello-camel-service/configmap.yaml`
  - `BASE_PATH` — default `/sample/api`; the app owns this full path
    itself rather than having a prefix stripped by the ingress layer, so
    it must match whatever path the cluster's `IngressRoute` sends here

## Build & run locally

```bash
mvn clean package
java -jar target/hello-camel-service.jar
```

```bash
curl http://localhost:8080/sample/api/hello
curl http://localhost:8080/sample/api/version
```

Try it with the env vars set:

```bash
GREETING_PREFIX="Namaste" APP_ENVIRONMENT="dev" java -jar target/hello-camel-service.jar
```

## Test

```bash
mvn test
```

## Container image

```bash
docker build -t hello-camel-service:local .
docker run -p 8080:8080 hello-camel-service:local
```

In the lab, this is built and pushed to the local Gitea registry
(`10.137.160.1:3000/rpamu/hello-camel-service`) — first done manually via a
one-off Kaniko run, now automated by a Tekton pipeline that clones from
Gitea and builds+pushes with Kaniko whenever a **tag** is pushed, using the
tag name as the image version (`git tag 1.2.0 && git push gitea 1.2.0`
builds and pushes `...hello-camel-service:1.2.0`). Deliberately
**build+push only** — Tekton doesn't deploy anything; run
`kubectl create -f ../../ci/tekton/pipelinerun.yaml` for a one-off manual
build instead. See [../../docs/tekton-setup.md](../../docs/tekton-setup.md).

## Deploy to Kubernetes

The Kubernetes manifests (ConfigMap, Deployment, Service, IngressRoute)
live in a **separate repo**,
[k8s-gitops-manifests](http://10.137.160.1:3000/rpamu/k8s-gitops-manifests)
(`apps/hello-camel-service/`) — not here alongside the source, and not in
`k8s-gitops-lab` at all. This keeps Tekton's build/push repo (this one)
cleanly separate from the repo Argo CD actually watches for deploys, so a
CI-triggered commit here can never touch what Argo CD syncs.

Deploys via **Argo CD**, not `kubectl apply` — an `Application` (see
[../../ci/argocd/](../../ci/argocd/) in this repo) watches that other
repo's directory and syncs any committed change straight to the cluster
(`prune`+`selfHeal` both on, so deleting a manifest there deletes the
object too, and a manual `kubectl edit` against a tracked resource gets
reverted). The `IngressRoute` requires the Traefik controller (installed
as a standard part of cluster setup — see
[../../docs/k8s-setup.md](../../docs/k8s-setup.md)). See
[../../docs/argocd-setup.md](../../docs/argocd-setup.md) for the full
Argo CD writeup.

In the cluster, this app is reachable under `api.staging.test/sample/*` — a
shared host used across all apps in this lab, with each app given its own
path prefix rather than its own hostname. Unlike a typical path-based
ingress setup, there's no prefix-stripping `Middleware` involved: the app's
`BASE_PATH` (`/sample/api` by default) already matches the full external
path, so Traefik forwards the request unchanged.
