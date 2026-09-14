# My Lab: Kubernetes install (kubespray)

Concrete record of the kubespray run on this lab's two VMs, following
[../k8s-setup.md](../k8s-setup.md). See [vm-setup.md](vm-setup.md) for the
VM/network specifics and networking gotchas referenced below.

## Result

Cluster is up and healthy.

```
NAME         STATUS   ROLES           VERSION   INTERNAL-IP
k8s-master   Ready    control-plane   v1.36.4   10.137.160.147
k8s-node     Ready    <none>          v1.36.4   10.137.160.148
```

- kube_network_plugin: `calico`
- container_manager: `containerd` (2.3.5)
- All `kube-system` pods `Running` (calico-node x2, calico-kube-controllers,
  coredns x2, dns-autoscaler, kube-proxy x2, nginx-proxy, nodelocaldns x2,
  plus the control-plane static pods on `k8s-master`)

## Setup used

- kubespray checked out to `~/kubespray` (venv at `~/kubespray/venv`)
- Inventory at `~/kubespray/inventory/mycluster/inventory.ini`, hand-written
  (this kubespray release has no `contrib/inventory_builder`):

  ```ini
  [kube_control_plane]
  k8s-master ansible_host=10.137.160.147 ip=10.137.160.147 etcd_member_name=etcd1

  [etcd:children]
  kube_control_plane

  [kube_node]
  k8s-node ansible_host=10.137.160.148 ip=10.137.160.148

  [k8s_cluster:children]
  kube_control_plane
  kube_node

  [all:vars]
  ansible_user=rpamu
  ansible_ssh_private_key_file=~/.ssh/id_rsa
  ansible_python_interpreter=/usr/bin/python3
  ```

- Run with: `ansible-playbook -i inventory/mycluster/inventory.ini --become --become-user=root cluster.yml`

## What actually went wrong (3 attempts before it went clean)

All three issues trace back to the host being on a mobile-hotspot uplink —
see the "Known gotchas" section in [vm-setup.md](vm-setup.md) for the full
netplan fix. Sequence of what happened:

1. **First run** (interrupted): host rebooted mid-install, killing the
   ansible-playbook process and shutting down both VMs. Not a real failure —
   just needed the VMs restarted and the playbook re-run (kubespray is
   idempotent).

2. **Second run**: failed 36 minutes in, on `k8s-master` only, downloading a
   file (output was masked by kubespray's `no_log: true` on that task, but
   correlated to the Calico CRDs manifest fetched from
   `raw.githubusercontent.com`). Root cause turned out to be **DNS**: the
   host's own resolver (upstream of libvirt's dnsmasq) was intermittently
   failing to resolve `github.com` from inside the VM
   (`getent hosts github.com` timing out). Fixed by adding explicit
   `nameservers: [1.1.1.1, 8.8.8.8]` to both VMs' netplan.

3. **Third run**: succeeded (`failed=0` on both nodes, ~20 min). But one pod,
   `dns-autoscaler`, stayed stuck `ContainerCreating` afterwards — `crictl`
   showed its image pull to `registry.k8s.io` connecting fine, then hanging
   forever right after the TLS ServerHello. That's the signature of a
   **path-MTU blackhole**: the hotspot silently drops packets needing
   fragmentation instead of returning an ICMP error, so anything larger than
   its real (sub-1500) path MTU just vanishes. Also noticed `dhcp6: true`
   was letting the guest pick up a stray IPv6 route with no real upstream on
   `k8slab`, which made some lookups (`registry.k8s.io` resolves to an
   IPv6-only-preferred record) try IPv6 first and hang.

   Fixed by setting `dhcp6: false` and `mtu: 1400` in both VMs' netplan; the
   stuck pull then completed in about a second, and `dns-autoscaler` came up
   immediately after `crictl pull` was re-run manually.

## Day-2 kubectl access

Installing `kubectl` directly on the host hit the same hotspot-related
flakiness (partial downloads timing out) at first. Worked around it the
same way as everything else network-bound in this lab: downloaded the
binary via `k8s-master` (whose network path was already fixed) and `scp`'d
it over, rather than fighting the host's connection directly:

```bash
ssh rpamu@10.137.160.147 "curl -fsSL -o /tmp/kubectl https://dl.k8s.io/release/v1.36.4/bin/linux/amd64/kubectl"
scp rpamu@10.137.160.147:/tmp/kubectl ~/.local/bin/kubectl
chmod +x ~/.local/bin/kubectl

mkdir -p ~/.kube
ssh rpamu@10.137.160.147 "sudo cat /etc/kubernetes/admin.conf" > ~/.kube/config-k8slab
chmod 600 ~/.kube/config-k8slab
```

`~/.local/bin` on `PATH` and `KUBECONFIG=~/.kube/config-k8slab` both added
to `~/.bashrc`. `kubectl get nodes` now works directly from the host, no
SSH needed.

## Ingress: Traefik

Following [../k8s-setup.md](../k8s-setup.md)'s ingress section. Installed
Traefik v3.7.13 — manifests vendored at
[../../infra/traefik/](../../infra/traefik/) rather than fetched from GitHub
each time:

```bash
kubectl apply -f infra/traefik/crds.yaml
kubectl apply -f infra/traefik/rbac.yaml
kubectl apply -f infra/traefik/deploy.yaml
```

Came up `1/1 Running` in the `default` namespace. NodePorts assigned:
- HTTP (`web`): `31834`
- HTTPS (`websecure`): `30303`
- Dashboard/API: `30469`

`hello-camel-service`'s `Ingress` just sets `ingressClassName: traefik`.
Verified end-to-end from both nodes and via Traefik's own router API — see
[hello-camel-service-deploy.md](hello-camel-service-deploy.md) for the
full request/response trace.

## Status

- [x] Kubernetes v1.36.4 installed, both nodes `Ready`
- [x] Calico CNI healthy
- [x] CoreDNS healthy
- [x] kubectl working locally on the host (not just over SSH)
- [x] Traefik ingress controller installed and verified
- [ ] Tekton installed
- [ ] Argo CD installed
- [ ] Sample Java app deployed via the pipeline
