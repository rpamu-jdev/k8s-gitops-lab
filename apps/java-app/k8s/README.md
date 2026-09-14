# Kubernetes manifests for hello-camel-service

Plain manifests (no Helm/Kustomize yet — Argo CD will likely want these
templated or organized differently once that's set up). Deployed into the
**`default`** namespace (no dedicated namespace for this app). Apply in
order:

```bash
kubectl apply -f configmap.yaml
kubectl apply -f deployment.yaml
kubectl apply -f service.yaml
kubectl apply -f ingressroute.yaml
```

## What each does

- **configmap.yaml** — the env vars from the app's `GREETING_PREFIX` /
  `APP_ENVIRONMENT` config (see [../README.md](../README.md)); edit these to
  see the running app's `/sample/api/hello` and `/sample/api/version`
  responses change after a rollout restart
- **deployment.yaml** — single replica, references the image at
  `10.137.160.1:3000/rpamu/hello-camel-service:1.1.0` (this lab's Gitea
  registry), wires Actuator's split liveness/readiness endpoints as k8s
  probes
- **service.yaml** — ClusterIP, port 80 → container port 8080
- **ingressroute.yaml** — Traefik-native `IngressRoute` (not a plain
  `Ingress`) routing `api.staging.test/sample` to the service; requires
  Traefik (see [../../../docs/k8s-setup.md](../../../docs/k8s-setup.md)).
  `api.staging.test` is a **shared host across all apps** in this lab — each
  app gets its own path prefix under it (this app: `/sample`) instead of
  its own hostname, so a new app adds a new route rule rather than a new
  host. No `Middleware`/path-stripping needed here: the app's own
  `BASE_PATH` (`/sample/api`, see [../README.md](../README.md)) already
  matches the full external path, so the request forwards unchanged.

## Rolling out a config change

```bash
kubectl edit configmap hello-camel-service-config
kubectl rollout restart deployment hello-camel-service
```

ConfigMap changes aren't picked up by already-running pods automatically —
a rollout restart is needed to re-read the env vars at container start.

## Verify

```bash
kubectl get deployment,pod -l app=hello-camel-service
kubectl get ingressroute hello-camel-service
curl -H "Host: api.staging.test" http://<traefik-node-ip>/sample/api/hello
```

(Only the Deployment/Pod carry the `app=hello-camel-service` label; the
`IngressRoute` is looked up by name instead since it's all in `default`
alongside other cluster resources now.)
