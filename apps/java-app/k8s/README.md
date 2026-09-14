# Kubernetes manifests for hello-camel-service

Plain manifests (no Helm/Kustomize yet — Argo CD will likely want these
templated or organized differently once that's set up). Apply in order:

```bash
kubectl apply -f namespace.yaml
kubectl apply -f configmap.yaml
kubectl apply -f deployment.yaml
kubectl apply -f service.yaml
kubectl apply -f ingress.yaml
```

`namespace.yaml` has to go first — everything else targets the
`hello-camel-service` namespace and will fail if it doesn't exist yet.

## What each does

- **namespace.yaml** — dedicated `hello-camel-service` namespace
- **configmap.yaml** — the env vars from the app's `GREETING_PREFIX` /
  `APP_ENVIRONMENT` config (see [../README.md](../README.md)); edit these to
  see the running app's `/api/hello` and `/api/version` responses change
  after a rollout restart
- **deployment.yaml** — single replica, references the image at
  `10.137.160.1:3000/rpamu/hello-camel-service:1.0.0` (this lab's Gitea
  registry), wires Actuator's split liveness/readiness endpoints as k8s
  probes
- **service.yaml** — ClusterIP, port 80 → container port 8080
- **ingress.yaml** — routes `hello.lab.local` to the service; requires
  Traefik (see [../../../docs/k8s-setup.md](../../../docs/k8s-setup.md))

## Rolling out a config change

```bash
kubectl -n hello-camel-service edit configmap hello-camel-service-config
kubectl -n hello-camel-service rollout restart deployment hello-camel-service
```

ConfigMap changes aren't picked up by already-running pods automatically —
a rollout restart is needed to re-read the env vars at container start.

## Verify

```bash
kubectl -n hello-camel-service get pods,svc,ingress
curl -H "Host: hello.lab.local" http://<any-node-ip>:<ingress-http-nodeport>/api/hello
```
