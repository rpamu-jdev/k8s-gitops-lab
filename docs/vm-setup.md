# KVM Lab: 2 Ubuntu VMs (master + node)

This documents how to build the lab environment on any Ubuntu or Debian host
with hardware virtualization support: a libvirt/KVM network + two Ubuntu VMs,
sized and networked to later run Kubernetes (1 master + 1 node) on top.

## Host requirements

- A 64-bit Ubuntu/Debian host with VT-x/AMD-V enabled and `/dev/kvm`
  available. Check with:

  ```bash
  egrep -c '(vmx|svm)' /proc/cpuinfo   # >0 means virtualization is supported
  ls /dev/kvm                          # should exist
  ```

- At least 6 vCPU and 12GB RAM free to comfortably run both VMs below
  alongside the host OS; more headroom is better once Tekton/Argo CD
  workloads are added. Adjust the sizing table to whatever the host has
  available:

| VM | vCPU | RAM | Disk | Role |
|---|---|---|---|---|
| `k8s-master` | 2 | 4GB | 20GB | kube_control_plane + etcd |
| `k8s-node` | 4 | 6GB | 30GB | kube_node (workloads: Tekton/Argo CD) |

## 1. Virtualization stack

Assumes nothing is preinstalled except a base Ubuntu/Debian system. Install:

```bash
sudo apt update
sudo apt install -y qemu-kvm libvirt-daemon-system libvirt-clients virtinst genisoimage bridge-utils
sudo usermod -aG libvirt,kvm $USER
sudo systemctl enable --now libvirtd
```

**Gotcha:** after `usermod`, you must fully log out/in (not just `newgrp`) for
the group membership to apply to every new shell. Until then, `virsh`
silently falls back to the unprivileged `qemu:///session` connection instead
of `qemu:///system`, which cannot create bridge interfaces
(`Operation not permitted`). This applies on any Ubuntu/Debian host — it's a
property of how libvirt group membership and connection URIs work, not
something specific to one machine.

Pin the connection explicitly once logged back in:

```bash
echo 'export LIBVIRT_DEFAULT_URI=qemu:///system' >> ~/.bashrc
export LIBVIRT_DEFAULT_URI=qemu:///system
virsh uri   # should print qemu:///system
```

## 2. Ubuntu cloud image

```bash
mkdir -p ~/vms/images && cd ~/vms/images
wget https://cloud-images.ubuntu.com/jammy/current/jammy-server-cloudimg-amd64.img
```

## 3. Per-VM disks (backed by the cloud image, copy-on-write)

```bash
cd ~/vms
qemu-img create -f qcow2 -F qcow2 -b images/jammy-server-cloudimg-amd64.img k8s-master.qcow2 20G
qemu-img create -f qcow2 -F qcow2 -b images/jammy-server-cloudimg-amd64.img k8s-node.qcow2   30G
```

## 4. cloud-init seed ISOs (user creation, SSH key, packages)

`user-data-master.yaml` (same for node, with `hostname: k8s-node`):

```yaml
#cloud-config
hostname: k8s-master
manage_etc_hosts: true
users:
  - name: <your-username>
    sudo: ALL=(ALL) NOPASSWD:ALL
    shell: /bin/bash
    ssh_authorized_keys:
      - <contents of ~/.ssh/id_ed25519.pub>
package_update: true
packages:
  - python3
  - qemu-guest-agent
  - open-iscsi
runcmd:
  - systemctl enable --now qemu-guest-agent
```

```bash
genisoimage -output k8s-master-seed.iso -volid cidata -joliet -rock user-data-master.yaml meta-data.yaml
genisoimage -output k8s-node-seed.iso   -volid cidata -joliet -rock user-data-node.yaml   meta-data.yaml
```

## 5. Dedicated NAT network (`k8slab`)

The default libvirt `default` network can conflict with a stale `virbr0`
interface left behind by a previous or partially-torn-down libvirt install
(`error: Network is already in use by interface virbr0` when starting it) —
if that happens, remove the stale interface (`sudo ip link delete virbr0`)
and undefine the `default` network before continuing.

Rather than depend on `default`, this lab uses its own isolated NAT network
with static DHCP reservations pinned by MAC, so the VMs always get the same
IPs regardless of what else is on the host. Substitute your own subnet if
`10.137.160.0/24` collides with an existing network:

```xml
<!-- ~/vms/k8slab-net.xml -->
<network>
  <name>k8slab</name>
  <bridge name='virbr1' stp='on' delay='0'/>
  <forward mode='nat'/>
  <ip address='10.137.160.1' netmask='255.255.255.0'>
    <dhcp>
      <range start='10.137.160.100' end='10.137.160.200'/>
      <host mac='52:54:00:aa:bb:01' name='k8s-master' ip='10.137.160.147'/>
      <host mac='52:54:00:aa:bb:02' name='k8s-node' ip='10.137.160.148'/>
    </dhcp>
  </ip>
</network>
```

```bash
virsh net-define ~/vms/k8slab-net.xml
virsh net-start k8slab
virsh net-autostart k8slab
```

| VM | Static IP |
|---|---|
| k8s-master | 10.137.160.147 |
| k8s-node | 10.137.160.148 |

## 6. Create the VMs

```bash
virt-install \
  --name k8s-master --memory 4096 --vcpus 2 \
  --disk path=~/vms/k8s-master.qcow2,format=qcow2 \
  --disk path=~/vms/k8s-master-seed.iso,device=cdrom \
  --os-variant ubuntu22.04 \
  --network network=k8slab,model=virtio,mac=52:54:00:aa:bb:01 \
  --graphics none --import --noautoconsole

virt-install \
  --name k8s-node --memory 6144 --vcpus 4 \
  --disk path=~/vms/k8s-node.qcow2,format=qcow2 \
  --disk path=~/vms/k8s-node-seed.iso,device=cdrom \
  --os-variant ubuntu22.04 \
  --network network=k8slab,model=virtio,mac=52:54:00:aa:bb:02 \
  --graphics none --import --noautoconsole
```

Login as the user created via cloud-init, **not root**:

```bash
ssh <your-username>@10.137.160.147   # master
ssh <your-username>@10.137.160.148   # node
```

##  Starting/stopping the lab

VMs and the network do **not** auto-start after a host reboot unless
autostart is set (network was set to autostart; VMs were not). To bring the
lab back up:

```bash
export LIBVIRT_DEFAULT_URI=qemu:///system
virsh net-list --all        # confirm k8slab is active
virsh start k8s-master
virsh start k8s-node
virsh domifaddr k8s-master  # confirm it picked up 10.137.160.147
virsh domifaddr k8s-node    # confirm it picked up 10.137.160.148
```

To make them persist across host reboots automatically:

```bash
virsh autostart k8s-master
virsh autostart k8s-node
```

To shut the lab down cleanly:

```bash
virsh shutdown k8s-master
virsh shutdown k8s-node
```

## Next steps

Both VMs are up, reachable over SSH, and sitting on the `k8slab` network at
fixed IPs — ready for whatever gets installed on them next (Kubernetes via
kubespray, kubeadm, k3s, etc.). See [k8s-setup.md](k8s-setup.md).
