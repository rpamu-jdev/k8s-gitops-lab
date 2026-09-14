# k8s-gitops-lab

Personal homelab notes and configs for a 2-VM KVM/libvirt Kubernetes cluster,
with Tekton for CI and Argo CD for GitOps CD, deploying a sample Java app
end-to-end.

## Contents

- [docs/vm-setup.md](docs/vm-setup.md) — generic libvirt/KVM host setup,
  network, and VM creation steps (reusable on any Ubuntu/Debian host)
- [docs/k8s-setup.md](docs/k8s-setup.md) — generic kubespray-based Kubernetes
  install steps, plus a troubleshooting section for flaky-network symptoms
- [docs/gitea-setup.md](docs/gitea-setup.md) — local Gitea (git server +
  built-in container registry) running on the host, plus the containerd
  `certs.d` trust config so the cluster can pull from it
- [docs/ingress-setup.md](docs/ingress-setup.md) — installing ingress-nginx
  on a bare kubeadm/kubespray cluster (not shipped by default), NodePort
  access, and how `Ingress` host routing works without real DNS
- `docs/tekton-setup.md` — Tekton Pipelines install and pipeline definitions
  (coming soon)
- `docs/argocd-setup.md` — Argo CD install and app-of-apps config (coming
  soon)
- [docs/my-lab/](docs/my-lab/) — this lab's actual concrete setup (real IPs,
  hostnames, MACs, file layout, gotchas hit, current status), one file per
  topic mirroring `docs/`
  - [docs/my-lab/vm-setup.md](docs/my-lab/vm-setup.md) — VM/network specifics
  - [docs/my-lab/k8s-setup.md](docs/my-lab/k8s-setup.md) — what actually
    happened installing Kubernetes on these VMs
  - [docs/my-lab/gitea-setup.md](docs/my-lab/gitea-setup.md) — Gitea's actual
    address, credentials location, and the registry consolidation done
  - [docs/my-lab/hello-camel-service-deploy.md](docs/my-lab/hello-camel-service-deploy.md)
    — building the image with Kaniko, deploying, and verifying Ingress access
- [apps/java-app/](apps/java-app/) — `hello-camel-service`: Java 17 + Spring
  Boot 4 + Apache Camel 4 REST API (`/api/hello`, `/api/version`), configured
  via `GREETING_PREFIX`/`APP_ENVIRONMENT` env vars, deployed to the cluster
  behind Ingress ([apps/java-app/k8s/](apps/java-app/k8s/))

## Topology

| VM | vCPU | RAM | Disk | Role | IP |
|---|---|---|---|---|---|
| k8s-master | 2 | 4GB | 20GB | control plane + etcd | 10.137.160.147 |
| k8s-node | 4 | 6GB | 30GB | worker (Tekton/Argo CD workloads) | 10.137.160.148 |

Gitea (git server + container registry) runs directly on the **host**, not a
VM, at `10.137.160.1:3000` (the `k8slab` network's gateway address) — see
[docs/gitea-setup.md](docs/gitea-setup.md) for why.
