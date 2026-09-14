# hello-camel-service

Sample REST API used as the end-to-end deployment target for this lab's
Tekton pipeline + Argo CD setup.

- **Stack**: Java 17, Spring Boot 4.1.1, Apache Camel 4.22.0 (REST DSL over
  the `platform-http` component, so it runs on Spring's embedded server with
  no separate servlet mapping)
- **Endpoints**:
  - `GET /api/hello` → `{"message": "<prefix> from <name> (<environment>)!"}`
  - `GET /api/version` → `{"name", "version", "environment", "javaVersion", "camelVersion"}`
  - `GET /actuator/health` → Spring Boot Actuator health (liveness/readiness
    probes split, wired as k8s probes — see [k8s/](k8s/))
- **Config via env vars** (both optional, sensible defaults if unset):
  - `GREETING_PREFIX` — default `Hello`
  - `APP_ENVIRONMENT` — default `local`; set to e.g. `k8s-lab` via the
    Kubernetes `ConfigMap` in [k8s/configmap.yaml](k8s/configmap.yaml)

## Build & run locally

```bash
mvn clean package
java -jar target/hello-camel-service.jar
```

```bash
curl http://localhost:8080/api/hello
curl http://localhost:8080/api/version
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

In the lab, this was built and pushed to the local Gitea registry via
Kaniko run directly on a node (`10.137.160.1:3000/rpamu/hello-camel-service`)
— see [../../docs/gitea-setup.md](../../docs/gitea-setup.md). A Tekton
pipeline will automate this step once that's set up (see
`docs/tekton-setup.md`, not yet written).

## Deploy to Kubernetes

See [k8s/](k8s/) for the manifests (Namespace, ConfigMap, Deployment,
Service, Ingress) and [k8s/README.md](k8s/README.md) for how to apply them
and roll out config changes. Ingress requires the Traefik controller
(installed as a standard part of cluster setup — see
[../../docs/k8s-setup.md](../../docs/k8s-setup.md)).
