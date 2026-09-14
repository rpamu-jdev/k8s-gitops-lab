# Kubernetes install via kubespray

Generic steps to install Kubernetes on the two VMs from
[vm-setup.md](vm-setup.md), using [kubespray](https://github.com/kubernetes-sigs/kubespray)
(ansible-based, works well for a small kubeadm-based lab cluster).

## Prerequisites on the control machine (not the VMs)

```bash
git clone --depth 1 https://github.com/kubernetes-sigs/kubespray.git
cd kubespray
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

## Inventory

Newer kubespray releases dropped `contrib/inventory_builder`, so for a small
fixed cluster it's simplest to hand-write the inventory:

```bash
cp -rfp inventory/sample inventory/mycluster
```

`inventory/mycluster/inventory.ini`:

```ini
[kube_control_plane]
k8s-master ansible_host=<master-ip> ip=<master-ip> etcd_member_name=etcd1

[etcd:children]
kube_control_plane

[kube_node]
k8s-node ansible_host=<node-ip> ip=<node-ip>

[k8s_cluster:children]
kube_control_plane
kube_node

[all:vars]
ansible_user=<vm-username>
ansible_ssh_private_key_file=~/.ssh/id_rsa
ansible_python_interpreter=/usr/bin/python3
```

Sanity-check connectivity before running anything:

```bash
ansible -i inventory/mycluster/inventory.ini all -m ping
```

Defaults worth confirming in `inventory/mycluster/group_vars/k8s_cluster/k8s-cluster.yml`
for a lab (both are already the kubespray defaults):

```yaml
kube_network_plugin: calico
container_manager: containerd
```

## Preflight checks

kubeadm (which kubespray drives) refuses to run with swap enabled:

```bash
ansible -i inventory/mycluster/inventory.ini all -m shell -a "swapon --show"
```

Should be empty on every host. If not, disable it before running the
playbook (kubespray also does this itself during the `preinstall` role, but
it's worth confirming up front).

## Run it

```bash
ansible-playbook -i inventory/mycluster/inventory.ini \
  --become --become-user=root \
  cluster.yml
```

Takes 10–20 minutes on a decent connection — most of the time goes to
downloading the kubeadm/kubelet/kubectl binaries and pulling container
images (etcd, containerd, Calico, CoreDNS, kube-apiserver/scheduler/
controller-manager/proxy). It's fully idempotent: if it fails partway
through (flaky network, a single image pull timing out, etc.), just
re-run the same command — it picks up from wherever it left off rather
than starting over.

## Verify

```bash
ssh <vm-username>@<master-ip>
sudo kubectl get nodes -o wide
sudo kubectl get pods -A
```

All nodes should show `Ready`; all pods in `kube-system` should be
`Running`.

## Install an ingress controller

kubespray does **not** install one — `Ingress` resources sit inert without
it, and pretty much any real workload on the cluster will want one, so
treat this as part of standing the cluster up rather than optional. This
uses [Traefik](https://traefik.io/) via plain manifests (no Helm), since it
natively watches standard Kubernetes `Ingress` objects (its own
`IngressRoute` CRD is only needed for Traefik-specific features like
weighted routing or custom middlewares).

Fetch the CRDs and RBAC from upstream:

```bash
curl -sL -o traefik-crds.yaml \
  https://raw.githubusercontent.com/traefik/traefik/v3.7/docs/content/reference/dynamic-configuration/kubernetes-crd-definition-v1.yml
curl -sL -o traefik-rbac.yaml \
  https://raw.githubusercontent.com/traefik/traefik/v3.7/docs/content/reference/dynamic-configuration/kubernetes-crd-rbac.yml

kubectl apply -f traefik-crds.yaml
kubectl apply -f traefik-rbac.yaml
```

The RBAC manifest expects a `ServiceAccount` named `traefik-ingress-controller`
in the `default` namespace — match that in the Deployment below rather than
editing the fetched YAML.

Deploy Traefik itself:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: traefik-ingress-controller
  namespace: default
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: traefik
  namespace: default
  labels:
    app: traefik
spec:
  replicas: 1
  selector:
    matchLabels:
      app: traefik
  template:
    metadata:
      labels:
        app: traefik
    spec:
      serviceAccountName: traefik-ingress-controller
      containers:
        - name: traefik
          image: traefik:v3.7.13
          args:
            - --entrypoints.web.address=:80
            - --entrypoints.websecure.address=:443
            - --providers.kubernetescrd
            - --providers.kubernetesingress
            - --providers.kubernetesingress.ingressclass=traefik
            - --api.dashboard=true
            - --api.insecure=true   # dashboard with no auth - lab only
            - --ping=true            # needed for the /ping health endpoint
          ports:
            - name: web
              containerPort: 80
            - name: websecure
              containerPort: 443
            - name: dashboard
              containerPort: 8080
          readinessProbe:
            httpGet: { path: /ping, port: 8080 }
            initialDelaySeconds: 5
          livenessProbe:
            httpGet: { path: /ping, port: 8080 }
            initialDelaySeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: traefik
  namespace: default
spec:
  type: NodePort
  selector:
    app: traefik
  ports:
    - name: web
      port: 80
      targetPort: web
    - name: websecure
      port: 443
      targetPort: websecure
    - name: dashboard
      port: 8080
      targetPort: dashboard
---
apiVersion: networking.k8s.io/v1
kind: IngressClass
metadata:
  name: traefik
spec:
  controller: traefik.io/ingress-controller
```

`type: NodePort` (not `LoadBalancer`) since there's no cloud load balancer
provisioner on a bare kubeadm cluster.

```bash
kubectl apply -f traefik-deploy.yaml
kubectl wait --for=condition=Ready pod -l app=traefik --timeout=120s
kubectl get svc traefik   # note the NodePorts
```

### Point a hostname at it

Routing is host-header based. Either add `<any-node-ip> <your-hostname>` to
`/etc/hosts` (works for browser access), or pass the header explicitly:

```bash
curl -H "Host: <your-hostname>" http://<any-node-ip>:<web-nodeport>/
```

Any node IP works — `NodePort` listens on every node regardless of which
one the Traefik pod landed on.

### Point an `Ingress` at it

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: <name>
  namespace: <namespace>
spec:
  ingressClassName: traefik
  rules:
    - host: <your-lab-hostname>
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: <service-name>
                port:
                  number: <service-port>
```

### Debugging via the dashboard/API

With `--api.insecure=true`, Traefik's own API is reachable on the
`dashboard` NodePort with no auth — useful for confirming a route actually
registered:

```bash
curl http://<any-node-ip>:<dashboard-nodeport>/api/http/routers | python3 -m json.tool
```

Look for a router named `<ingress-name>-<namespace>-<host>@kubernetes` with
the expected `Host(...)` rule. If it's missing, check `ingressClassName`
matches exactly and that Traefik's pod logs don't show an RBAC/watch error.

### Notes / limitations

- No TLS configured — plain HTTP, fine for a private lab network.
- `--api.insecure=true` exposes the dashboard/API with zero authentication —
  acceptable only on an isolated lab network with no external exposure.
- Single replica; bump it if testing HA behavior matters.
- A `503`/connection-refused from a route means the backing Service has no
  ready endpoints yet, not necessarily an Ingress misconfiguration — check
  `kubectl get pods` for the target Deployment first.

## Using kubectl from the control machine instead of SSH

```bash
mkdir -p ~/.kube
ssh <vm-username>@<master-ip> "sudo cat /etc/kubernetes/admin.conf" > ~/.kube/config-lab
chmod 600 ~/.kube/config-lab
export KUBECONFIG=~/.kube/config-lab
kubectl get nodes
```

(Needs a `kubectl` binary matching the cluster's minor version on the
control machine — grab it from
`https://dl.k8s.io/release/<version>/bin/linux/amd64/kubectl`.)

## Troubleshooting: image pulls or downloads hang or time out

If a specific `Download_file` or `Download_container` task fails or hangs
repeatedly (not a one-off blip), it's almost always a network-path issue
between the VM and the internet, not a kubespray problem:

- **DNS not resolving one specific host** (e.g. a raw GitHub URL) but others
  work: check `getent hosts <hostname>` on the affected VM. If it times out,
  the VM's DNS resolver chain (VM → libvirt dnsmasq → host resolver →
  upstream) has a weak link somewhere. Pointing the VM's netplan directly at
  a public resolver (`1.1.1.1`, `8.8.8.8`) sidesteps the whole chain.
- **A pull or handshake stalls indefinitely** (connects, then hangs — often
  right after a TLS ServerHello) rather than erroring: this is the classic
  symptom of a path-MTU blackhole, where something on the route silently
  drops packets that need fragmenting instead of sending back "fragmentation
  needed." Lowering the VM's interface MTU (e.g. to `1400`) avoids ever
  generating oversized packets in the first place.

See [my-lab/vm-setup.md](my-lab/vm-setup.md) for the concrete netplan config
used to fix both of these in this lab.
