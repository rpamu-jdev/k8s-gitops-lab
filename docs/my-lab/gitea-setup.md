# My Lab: Gitea (git + registry)

Concrete setup following [../gitea-setup.md](../gitea-setup.md).

## Where it runs

- **Host machine** (not a VM) — chosen over a dedicated VM because the host
  was already under memory pressure (only ~920Mi free, 2.3GB already
  swapped) with the two k8s VMs running; a third VM would have added
  another 1-2GB of pure guest-OS overhead on top of whatever Gitea itself
  needs. Running the ~100-200MB Gitea binary directly on the host avoided
  that entirely.
- Binary: `~/.local/bin/gitea` (v1.23.7), downloaded via `k8s-master` (its
  network path was already fixed) and scp'd to the host, same trick used
  for `kubectl`.
- Config: `~/gitea/app.ini`, data/logs under `~/gitea/`.
- Address: `http://10.137.160.1:3000` — the `k8slab` network's gateway IP
  (the host, since it's a libvirt NAT network), reachable from both VMs and
  the host itself.
- Admin user: `rpamu` (password in `~/gitea/admin-password.txt`, API/registry
  token in `~/gitea/admin-token.txt` — both `chmod 600`, not committed
  anywhere).
- SSH disabled (`DISABLE_SSH = true`) to avoid clashing with the host's own
  sshd on port 22; git operations go over HTTP.

## Persistence

Started manually first via `nohup` to test, then handed off to a systemd
unit (`/etc/systemd/system/gitea.service`, `Restart=always`) so it survives
host reboots and crashes — installing that needed host `sudo`, run manually
rather than through the assistant.

## containerd trust config

Same `certs.d` mechanism as before, applied on both `k8s-master` and
`k8s-node` at `/etc/containerd/certs.d/10.137.160.1:3000/hosts.toml`:

```toml
server = "http://10.137.160.1:3000"

[host."http://10.137.160.1:3000"]
  capabilities = ["pull", "resolve", "push"]
```

## Consolidation: replaced the earlier registry:2 setup

This lab originally ran a separate anonymous `registry:2` container on
`k8s-node` (see the now-removed `my-lab/registry-setup.md`). Once Gitea's
built-in registry was verified working, that was redundant, so it was torn
down:

```bash
sudo nerdctl rm -f registry
sudo rm -rf /var/lib/registry-data
# on both nodes:
sudo rm -rf '/etc/containerd/certs.d/10.137.160.148:5000'
sudo systemctl restart containerd
```

Both nodes stayed `Ready` throughout. Gitea's registry is now the only image
source for this lab besides public registries (Docker Hub, `registry.k8s.io`,
`quay.io`).

## Verified working

- `curl http://10.137.160.1:3000/` returns 200 from both host and VMs.
- Admin user + access token created via `gitea admin user create` /
  `generate-access-token` CLI (no web installer needed, `INSTALL_LOCK=true`).
- `nerdctl login 10.137.160.1:3000` from `k8s-node`, using the token as the
  password.
- Pushed `busybox:latest` as `10.137.160.1:3000/rpamu/busybox:latest`.
- `crictl pull` succeeded on both `k8s-master` and `k8s-node`.
- Real pod (`gitea-registry-test`) scheduled and reached `Running` pulling
  from Gitea.
- Package confirmed visible via `GET /api/v1/packages/rpamu`.

## Status

- [x] Gitea running on host, git + registry both enabled
- [x] Admin user + token created
- [x] Both nodes trust it as an insecure registry
- [x] Push/pull/pod-scheduling verified end-to-end
- [x] Old `registry:2` on `k8s-node` removed, consolidated onto Gitea
- [x] Systemd persistence — `gitea.service` installed and enabled
      (`systemctl enable --now gitea`), survives reboots/crashes now
- [ ] First real git repo created for the sample Java app
