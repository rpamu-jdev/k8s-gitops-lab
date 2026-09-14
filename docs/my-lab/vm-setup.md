# My Lab: actual setup details

Concrete values for *this* lab instance, following the generic steps in
[../vm-setup.md](../vm-setup.md). Keep this file updated as the lab environment
changes — it's the source of truth for what's actually running, not a
template.

## Host

- Laptop: Intel i7-13620H, 16 vCPU, 15GB RAM, `/dev/kvm` available (VT-x).
- OS user: `rpamu`
- SSH key used for VM access: `~/.ssh/id_ed25519`

## VMs

| VM | vCPU | RAM | Disk | Role | IP | MAC |
|---|---|---|---|---|---|---|
| `k8s-master` | 2 | 4GB | 20GB | kube_control_plane + etcd | 10.137.160.147 | 52:54:00:aa:bb:01 |
| `k8s-node` | 4 | 6GB | 30GB | kube_node | 10.137.160.148 | 52:54:00:aa:bb:02 |

Login user on both VMs: `rpamu` (created via cloud-init, passwordless sudo).

```bash
ssh rpamu@10.137.160.147   # master
ssh rpamu@10.137.160.148   # node
```

## Network

- libvirt network name: `k8slab`
- Bridge: `virbr1`
- Subnet: `10.137.160.0/24`, gateway `10.137.160.1`
- DHCP range: `10.137.160.100`–`10.137.160.200` (master/node use static
  reservations outside this range via MAC, see table above)
- Definition file: `~/vms/k8slab-net.xml`

## Local file layout

```
~/vms/
├── images/jammy-server-cloudimg-amd64.img   # base Ubuntu 22.04 cloud image
├── k8s-master.qcow2
├── k8s-node.qcow2
├── k8s-master-seed.iso                      # cloud-init seed (user-data + meta-data)
├── k8s-node-seed.iso
├── user-data-master.yaml
├── user-data-node.yaml
├── meta-data.yaml
├── k8slab-net.xml
└── k8s-gitops-lab/                          # this repo
```

## Known gotchas hit on this box

- `LIBVIRT_DEFAULT_URI` had to be set explicitly to `qemu:///system` in
  `~/.bashrc` — without it `virsh` was silently using `qemu:///session` and
  network/bridge creation failed with `Operation not permitted`.
- A stale `virbr0` interface already existed on this host from an earlier
  partial libvirt setup attempt, blocking the `default` network from
  starting. Removed with `sudo ip link delete virbr0` +
  `virsh net-undefine default`; not using `default` at all now, only
  `k8slab`.
- After a host reboot, both VMs come back `shut off` and `k8slab` reactivates
  automatically (autostart is set on the network, not the VMs). Start them
  manually with `virsh start k8s-master k8s-node`, or run
  `virsh autostart k8s-master k8s-node` once to make that automatic too.
- **Host is on a mobile-hotspot uplink (`172.20.10.0/28` on `wlp44s0`)**,
  which caused two separate problems inside the VMs during the kubespray
  install (see [k8s-setup.md](k8s-setup.md) for how they showed up):
  - The hotspot's DNS (particularly its IPv6 resolver) is unreliable, and
    that unreliability propagated through libvirt's dnsmasq into both VMs
    (`getent hosts github.com` intermittently timing out).
  - The hotspot silently drops packets that need path-MTU fragmentation
    (a PMTU blackhole) — TLS handshakes to some registries would stall
    right after ServerHello and hang forever instead of failing fast.
  - **Fix applied to both VMs' netplan** (`/etc/netplan/50-cloud-init.yaml`):
    explicit public DNS servers, `dhcp6: false` (no real IPv6 path exists on
    `k8slab` anyway, so a stray IPv6 route was making the guest try IPv6
    first and hang), and `mtu: 1400` to stay under the hotspot's real path
    MTU:
    ```yaml
    network:
      version: 2
      ethernets:
        enp1s0:
          match:
            macaddress: "<vm-mac>"
          dhcp4: true
          dhcp6: false
          set-name: "enp1s0"
          mtu: 1400
          nameservers:
            addresses: [1.1.1.1, 8.8.8.8]
    ```
    Applied with `sudo netplan apply` on each VM. If this lab ever moves to
    a normal home/office network, this override probably isn't needed —
    but it's harmless to leave in place.
  - The same DNS flakiness hits the **host** directly too, not just the
    VMs — `git push`/`ssh` to `github.com` from the host would intermittently
    fail to resolve at all (no usable IPv4 record, only an inconsistent
    NAT64-synthesized IPv6 one). Rather than touch the host's system DNS
    (needs `sudo`), added an SSH tunnel through `k8s-master` for GitHub
    specifically, in `~/.ssh/config`:
    ```
    Host github.com
      ProxyCommand ssh -q rpamu@10.137.160.147 -W %h:%p
    ```
    This only relays raw TCP bytes through the VM — the VM never sees the
    host's private key, SSH negotiation still happens end-to-end host↔GitHub.

## Current status

- [x] Host virtualization stack installed (libvirt/KVM)
- [x] `k8slab` network created and autostarting
- [x] Both VMs created, cloud-init working, SSH access confirmed
- [x] Kubernetes installed (see [k8s-setup.md](k8s-setup.md))
- [ ] Tekton installed
- [ ] Argo CD installed
- [ ] Sample Java app deployed via the pipeline
