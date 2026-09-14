# Local Gitea: git server + container registry

A single lightweight self-hosted service providing both a GitHub-like git
server and an OCI/Docker container registry (Gitea's built-in "Packages"
feature, available since 1.17). One service instead of two — simpler to run
and maintain than a separate git server plus a separate `registry:2`
container.

Runs directly on the **host machine** (not a VM/container) — a static Go
binary with a SQLite backend, light enough (~100-200MB RSS) that a dedicated
VM isn't worth the RAM overhead for a lab. The Kubernetes nodes reach it over
the libvirt NAT network's gateway address.

## Why on the host, not a VM

A dedicated VM for this would cost 1-2GB RAM just for the guest OS on top of
whatever Gitea itself needs. Running the binary directly on the host avoids
that entirely — relevant if the host is already resource-constrained.

## 1. Install the binary

```bash
curl -fsSL -o /tmp/gitea "https://dl.gitea.com/gitea/<version>/gitea-<version>-linux-amd64"
chmod +x /tmp/gitea
mkdir -p ~/.local/bin
mv /tmp/gitea ~/.local/bin/gitea
```

## 2. Configure

```bash
mkdir -p ~/gitea/{custom,data,log}
cat > ~/gitea/app.ini <<'EOF'
APP_NAME = Local Lab Git
RUN_MODE = prod
RUN_USER = <your-username>

[server]
DOMAIN           = <host-ip-reachable-from-vms>
HTTP_ADDR        = 0.0.0.0
HTTP_PORT        = 3000
ROOT_URL         = http://<host-ip-reachable-from-vms>:3000/
DISABLE_SSH      = true
START_SSH_SERVER = false

[database]
DB_TYPE = sqlite3
PATH    = /home/<your-username>/gitea/data/gitea.db

[repository]
ROOT = /home/<your-username>/gitea/data/gitea-repositories

[security]
INSTALL_LOCK = true

[service]
DISABLE_REGISTRATION = true

[packages]
ENABLED = true
EOF
```

Notes:
- `DOMAIN`/`ROOT_URL` should be the address your VMs can actually reach —
  typically the host's IP on whatever libvirt network they're on (the
  network's gateway address, since the host is the gateway on a NAT
  network), not `127.0.0.1` or the host's public/Wi-Fi IP.
- `DISABLE_SSH = true` avoids clashing with the host's own sshd on port 22.
  Git operations go over HTTP(S) instead of SSH — fine for a lab, just use
  `http://` clone URLs.
- `INSTALL_LOCK = true` skips Gitea's interactive first-run web installer.

## 3. Run it

As a plain background process for a quick test:

```bash
cd ~/gitea
GITEA_WORK_DIR=/home/<your-username>/gitea nohup ~/.local/bin/gitea web \
  --config /home/<your-username>/gitea/app.ini \
  > ~/gitea/log/gitea-stdout.log 2>&1 &
disown
```

For anything longer-lived, install it as a systemd service instead (needs
root):

```ini
# /etc/systemd/system/gitea.service
[Unit]
Description=Gitea (local lab git + registry)
After=network.target

[Service]
Type=simple
User=<your-username>
WorkingDirectory=/home/<your-username>/gitea
Environment=GITEA_WORK_DIR=/home/<your-username>/gitea
ExecStart=/home/<your-username>/.local/bin/gitea web --config /home/<your-username>/gitea/app.ini
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now gitea
```

## 4. Create an admin user + access token

```bash
GITEA_WORK_DIR=/home/<your-username>/gitea ~/.local/bin/gitea admin user create \
  --config ~/gitea/app.ini \
  --username <your-username> \
  --password '<a-password>' \
  --email <your-username>@lab.local \
  --admin --must-change-password=false

GITEA_WORK_DIR=/home/<your-username>/gitea ~/.local/bin/gitea admin user generate-access-token \
  --config ~/gitea/app.ini \
  --username <your-username> \
  --token-name lab-token \
  --scopes "write:package,write:repository,write:admin" \
  --raw
```

Save the token somewhere private (`chmod 600`) — it's used both for `git`
HTTP auth and for `docker`/`nerdctl login` to the registry.

## 5. Tell containerd on every Kubernetes node to trust the registry as HTTP

Same mechanism as any self-hosted insecure registry — see
[k8s-setup.md](k8s-setup.md) if this is unfamiliar. On **every node**:

```bash
sudo mkdir -p "/etc/containerd/certs.d/<gitea-host>:3000"
sudo tee "/etc/containerd/certs.d/<gitea-host>:3000/hosts.toml" > /dev/null <<EOF
server = "http://<gitea-host>:3000"

[host."http://<gitea-host>:3000"]
  capabilities = ["pull", "resolve", "push"]
EOF
sudo systemctl restart containerd
```

## 6. Push, pull, verify

```bash
# from any node with nerdctl:
echo "<token>" | sudo nerdctl login <gitea-host>:3000 -u <your-username> --password-stdin
sudo nerdctl tag busybox:latest <gitea-host>:3000/<your-username>/busybox:latest
sudo nerdctl push <gitea-host>:3000/<your-username>/busybox:latest

# verify the CRI path (what kubelet actually uses):
sudo crictl pull <gitea-host>:3000/<your-username>/busybox:latest

# and a real pod:
kubectl run gitea-registry-test --image=<gitea-host>:3000/<your-username>/busybox:latest \
  --restart=Never --command -- sleep 3600
kubectl get pod gitea-registry-test -o wide
kubectl delete pod gitea-registry-test
```

Confirm the pushed package shows up via Gitea's own API:

```bash
curl -H "Authorization: token <token>" http://<gitea-host>:3000/api/v1/packages/<your-username>
```

## Git usage (the other half of Gitea)

Create a repo via the web UI (`http://<gitea-host>:3000`) or the API, then
clone/push over HTTP with the same token:

```bash
git clone http://<your-username>:<token>@<gitea-host>:3000/<your-username>/<repo>.git
```

This is what Argo CD will point at later for GitOps (watching a repo here
for manifest changes) and what Tekton pipelines will check out as source.

## Notes / limitations

- SQLite backend — fine for a single-user lab, not for concurrent heavy
  load. Switch `[database]` to Postgres if that ever matters.
- No TLS — everything is plain HTTP, acceptable only on a private lab
  network.
- Registry pulls right after a `containerd restart` can transiently fail
  once (connection reset) before succeeding on retry — a brief window while
  containerd's client re-establishes, not a real problem.
