# k8s-gitops-lab

Personal homelab notes and configs for a 2-VM KVM/libvirt Kubernetes cluster,
with Tekton for CI and Argo CD for GitOps CD, deploying a sample Java app
end-to-end.

**This is the source/CI repo** — application code, Dockerfile, and the
Tekton pipeline that builds and pushes its image. The Kubernetes deploy
manifests Argo CD actually syncs from live in a **separate repo**,
[k8s-gitops-manifests](http://10.137.160.1:3000/rpamu/k8s-gitops-manifests),
deliberately kept apart so nothing this repo's pipeline does can ever
touch what Argo CD watches.

## Contents

- [docs/vm-setup.md](docs/vm-setup.md) — generic libvirt/KVM host setup,
  network, and VM creation steps (reusable on any Ubuntu/Debian host)
- [docs/k8s-setup.md](docs/k8s-setup.md) — generic kubespray-based Kubernetes
  install steps (including the Traefik ingress controller, treated as a
  required part of standing up the cluster, not optional), plus a
  troubleshooting section for flaky-network symptoms
- [docs/gitea-setup.md](docs/gitea-setup.md) — local Gitea (git server +
  built-in container registry) running on the host, plus the containerd
  `certs.d` trust config so the cluster can pull from it
- [infra/traefik/](infra/traefik/) — the actual Traefik manifests applied
  (CRDs, RBAC, Deployment/Service/IngressClass), vendored rather than
  fetched from GitHub each time
- [docs/tekton-setup.md](docs/tekton-setup.md) — Tekton Pipelines install,
  the local-path-provisioner StorageClass it needs, a generic build+push-only
  pipeline (no app baked in, no deploy step by design), and Tekton Triggers
  for automatic builds on **tag** push (with a Gitea webhook)
- [ci/tekton/](ci/tekton/) — the actual Tasks/Pipeline/PipelineRun/RBAC/
  Triggers manifests for `hello-camel-service`'s build+push pipeline
- [docs/dashboards-setup.md](docs/dashboards-setup.md) — Tekton Dashboard
  and Kubernetes Dashboard, each on its own hostname via Traefik (not raw
  NodePorts), with a deliberately read-only login for the k8s one
- [infra/dashboards/](infra/dashboards/) — the RBAC and IngressRoute
  manifests for both dashboards
- [docs/argocd-setup.md](docs/argocd-setup.md) — Argo CD install, serving
  plain HTTP behind Traefik, Gitea repo credentials, the generic
  `Application` shape (one per app, `syncPolicy.automated` with
  `prune`+`selfHeal`), and Argo CD Image Updater so a Tekton-pushed tag
  gets deployed with no manual git edit
- [infra/argocd/](infra/argocd/) — Argo CD's own routing (`IngressRoute`
  for its UI at `argocd.staging.test`)
- [ci/argocd/](ci/argocd/) — the `Application` manifest that deploys
  `hello-camel-service` from the separate
  [k8s-gitops-manifests](http://10.137.160.1:3000/rpamu/k8s-gitops-manifests)
  repo's `apps/hello-camel-service/` — git push (to that repo, not this
  one), not `kubectl apply`, is how this app gets deployed now
- [docs/my-lab/](docs/my-lab/) — this lab's actual concrete setup (real IPs,
  hostnames, MACs, file layout, gotchas hit, current status), one file per
  topic mirroring `docs/`
  - [docs/my-lab/vm-setup.md](docs/my-lab/vm-setup.md) — VM/network specifics
  - [docs/my-lab/k8s-setup.md](docs/my-lab/k8s-setup.md) — what actually
    happened installing Kubernetes on these VMs
  - [docs/my-lab/gitea-setup.md](docs/my-lab/gitea-setup.md) — Gitea's actual
    address, credentials location, and the registry consolidation done
  - [docs/my-lab/hello-camel-service-deploy.md](docs/my-lab/hello-camel-service-deploy.md)
    — building the image with Kaniko, deploying, and verifying IngressRoute
    access
  - [docs/my-lab/tekton-setup.md](docs/my-lab/tekton-setup.md) — Tekton
    install, the source-from-Gitea decision, the switch to a generic
    build+push-only pipeline triggered by tag pushes, and the real bugs
    hit setting it up
  - [docs/my-lab/dashboards-setup.md](docs/my-lab/dashboards-setup.md) —
    both dashboards' actual hostnames, the read-only login token, and why
    hostnames instead of the app-style path convention here
  - [docs/my-lab/argocd-setup.md](docs/my-lab/argocd-setup.md) — Argo CD
    install, the `hello-camel-service` `Application`, splitting manifests
    into their own repo, Image Updater setup, and a full real test
    (`git tag && git push` alone deploying a new version end to end)
- [apps/java-app/](apps/java-app/) — `hello-camel-service`: Java 17 + Spring
  Boot 4 + Apache Camel 4 REST API (`/sample/api/hello`, `/sample/api/version`),
  configured via `GREETING_PREFIX`/`APP_ENVIRONMENT` env vars, reachable
  in-cluster at `api.staging.test/sample/*` (deploy manifests are in the
  separate `k8s-gitops-manifests` repo, not here — see `ci/argocd/`)

## Ingress convention

Backend APIs in this lab share one host, `api.staging.test`, distinguished
by path prefix rather than each getting its own hostname — a new app adds
a new route rule to its `IngressRoute` (Traefik's native CRD, used instead
of a plain `Ingress`) rather than a new host. Each app owns its full
external path itself (`hello-camel-service`'s base path is `/sample/api`)
rather than relying on a path-stripping `Middleware` at the ingress layer.

The admin dashboards, the Tekton webhook endpoint, and the Argo CD UI are
the exception: each gets its **own hostname** (`tekton.staging.test`,
`dashboard.staging.test`, `webhook.staging.test`, `argocd.staging.test`)
instead of a path under `api.staging.test`, since none of them are apps
built with a configurable base path — a path prefix would break their
asset loading, and the webhook endpoint is an operational integration
point rather than a backend API. Same Traefik entrypoint, same "DNS
instead of a raw port number" goal either way.

## Topology

| VM | vCPU | RAM | Disk | Role | IP |
|---|---|---|---|---|---|
| k8s-master | 2 | 4GB | 20GB | control plane + etcd | 10.137.160.147 |
| k8s-node | 4 | 6GB | 30GB | worker (Tekton/Argo CD workloads) | 10.137.160.148 |

Gitea (git server + container registry) runs directly on the **host**, not a
VM, at `10.137.160.1:3000` (the `k8slab` network's gateway address) — see
[docs/gitea-setup.md](docs/gitea-setup.md) for why.
