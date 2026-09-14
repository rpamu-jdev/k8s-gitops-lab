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
    probes split, wired as k8s probes — see [k8s/](k8s/))
- **Config via env vars** (all optional, sensible defaults if unset):
  - `GREETING_PREFIX` — default `Hello`
  - `APP_ENVIRONMENT` — default `local`; set to e.g. `k8s-lab` via the
    Kubernetes `ConfigMap` in [k8s/configmap.yaml](k8s/configmap.yaml)
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
one-off Kaniko run, now automated by a Tekton pipeline
(`kubectl create -f ../../ci/tekton/pipelinerun.yaml`) that clones from
Gitea, builds+pushes with Kaniko, and rolls the Deployment to the new
image. See [../../docs/tekton-setup.md](../../docs/tekton-setup.md).

## Deploy to Kubernetes

See [k8s/](k8s/) for the manifests (ConfigMap, Deployment, Service,
IngressRoute) and [k8s/README.md](k8s/README.md) for how to apply them and
roll out config changes. The `IngressRoute` requires the Traefik controller
(installed as a standard part of cluster setup — see
[../../docs/k8s-setup.md](../../docs/k8s-setup.md)).

In the cluster, this app is reachable under `api.staging.test/sample/*` — a
shared host used across all apps in this lab, with each app given its own
path prefix rather than its own hostname. Unlike a typical path-based
ingress setup, there's no prefix-stripping `Middleware` involved: the app's
`BASE_PATH` (`/sample/api` by default) already matches the full external
path, so Traefik forwards the request unchanged.
