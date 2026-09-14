# hello-camel-service

Sample REST API used as the end-to-end deployment target for this lab's
Tekton pipeline + Argo CD setup.

- **Stack**: Java 17, Spring Boot 4.1.1, Apache Camel 4.22.0 (REST DSL over
  the `platform-http` component, so it runs on Spring's embedded server with
  no separate servlet mapping)
- **Endpoints**:
  - `GET /api/hello` → `{"message": "Hello from hello-camel-service!"}`
  - `GET /api/version` → `{"name", "version", "javaVersion", "camelVersion"}`
  - `GET /actuator/health` → Spring Boot Actuator health (liveness/readiness
    probes split, ready for Kubernetes probe wiring later)

## Build & run locally

```bash
mvn clean package
java -jar target/hello-camel-service.jar
```

```bash
curl http://localhost:8080/api/hello
curl http://localhost:8080/api/version
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

In the lab, this gets built and pushed by a Tekton pipeline to the local
Gitea registry instead of a local `docker build` — see
[../../docs/tekton-setup.md](../../docs/tekton-setup.md) once that's written.
