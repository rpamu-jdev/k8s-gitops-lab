# My Lab: Tekton and Kubernetes dashboards

Concrete setup following [../dashboards-setup.md](../dashboards-setup.md).

## Installed

- **Tekton Dashboard v0.72.0** — `tekton-pipelines` namespace,
  `tekton-dashboard` Service (`ClusterIP`, port `9097`)
- **Kubernetes Dashboard v2.7.0** — `kubernetes-dashboard` namespace,
  `kubernetes-dashboard` Service (`ClusterIP`, port `443`, HTTPS
  internally with a self-signed cert)

## Access

Both routed through Traefik with dedicated hostnames — and, since Traefik
runs on `hostNetwork` (see [k8s-setup.md](k8s-setup.md)), **no port number
at all**:

| Dashboard | URL | Auth |
|---|---|---|
| Tekton | `http://tekton.staging.test/` | none |
| Kubernetes | `http://dashboard.staging.test/` | token login |

```bash
# /etc/hosts, added manually (needs sudo) - .148 is the node Traefik is
# pinned to, not just any node, since hostNetwork only binds there:
10.137.160.148  api.staging.test tekton.staging.test dashboard.staging.test
```

## Kubernetes Dashboard login

Initially considered `cluster-admin` for convenience — **the assistant's
own harness flagged that as too sensitive to create without explicit
confirmation**, which prompted picking a real access level rather than
defaulting to the broadest one. Went with the built-in `view` `ClusterRole`
instead (read-only across all namespaces) via a dedicated `dashboard-viewer`
ServiceAccount:

```bash
kubectl -n kubernetes-dashboard create token dashboard-viewer --duration=87600h
```

Token saved to `~/k8s-dashboard-token.txt` on the host (`chmod 600`, not
committed anywhere). Can browse everything, can't create/edit/delete
through the UI.

## Kubernetes Dashboard's HTTPS backend

Traefik needs a `ServersTransport` with `insecureSkipVerify: true` to
proxy to the Dashboard's self-signed-cert HTTPS Service — otherwise it
refuses the backend connection. The browser-facing side is still plain
HTTP (Traefik's `web` entrypoint), so no cert warning shows up for the
person actually using it; the insecure hop is only Traefik→Dashboard,
inside the cluster network.

## Why hostnames instead of the app-style path convention

`hello-camel-service` uses a path prefix (`api.staging.test/sample`) under
one shared host — works because the app itself was built to own that full
path (`BASE_PATH`, see [hello-camel-service-deploy.md](hello-camel-service-deploy.md)).
Neither dashboard was built with a configurable base path, so a path
prefix would have broken their asset loading (absolute `/static/...`-style
references). Dedicated hostnames sidestep that entirely — same Traefik
`web` entrypoint, same "DNS instead of a port number" goal, no risk of a
half-broken UI.

## Verified working

```bash
curl -H "Host: tekton.staging.test" http://10.137.160.148/
curl -H "Host: dashboard.staging.test" http://10.137.160.148/
# both -> 200, real HTML, no port

curl http://10.137.160.148:8080/api/http/routers | python3 -c "..."
# -> tekton-pipelines-tekton-dashboard-...@kubernetescrd   Host(`tekton.staging.test`)   enabled
# -> kubernetes-dashboard-kubernetes-dashboard-...@kubernetescrd   Host(`dashboard.staging.test`)   enabled
```

(Traefik's own debug API on `8080` still needs a port — that's an
internal/operator tool, not something branded with a hostname here.)

## Status

- [x] Tekton Dashboard installed and routed via `tekton.staging.test`
- [x] Kubernetes Dashboard installed and routed via `dashboard.staging.test`
- [x] Read-only login token created and saved locally
- [x] Both Services reverted to ClusterIP (Traefik is the single entry
      point, not per-service NodePorts)
- [x] Traefik switched to `hostNetwork` — both dashboards (and
      `api.staging.test`) now reachable with **no port number** at all
- [x] `/etc/hosts` entries added on the lab host, both dashboards
      confirmed loading in the browser (`dashboard.staging.test` login
      page, `tekton.staging.test` UI) — would need repeating on any other
      machine that wants browser access
