# My Lab: hello-camel-service build + deploy + ingress

Concrete record of getting the sample app from source to a browser-reachable
URL in the cluster.

## Build & push (manual, via Kaniko — Tekton not set up yet)

Since there's no CI pipeline yet, the image was built and pushed manually
using **Kaniko** run directly via `nerdctl` on `k8s-node` — chosen
specifically because it's the same tool a future Tekton pipeline will use,
so this doubled as a dry run:

```bash
# docker config.json with Gitea registry auth, built from the admin token:
echo '{"auths":{"10.137.160.1:3000":{"auth":"<base64 rpamu:token>"}}}' > /tmp/docker-config.json

# build context copied to the node (scp apps/java-app -> /tmp/java-app-ctx)

sudo nerdctl run --rm \
  -v /tmp/java-app-ctx:/workspace \
  -v /tmp/docker-config.json:/kaniko/.docker/config.json \
  gcr.io/kaniko-project/executor:latest \
  --context=dir:///workspace \
  --dockerfile=/workspace/Dockerfile \
  --destination=10.137.160.1:3000/rpamu/hello-camel-service:1.0.0 \
  --insecure --insecure-pull --skip-tls-verify
```

`--insecure`/`--insecure-pull`/`--skip-tls-verify` are needed because Gitea
here runs plain HTTP, no TLS. Took about 9 minutes end to end (Kaniko's own
image pull + a from-scratch `mvn dependency:go-offline` inside the build
stage, since the build container starts with an empty `.m2` cache — Maven's
`-q` flag then makes it look "stuck" for several minutes with zero log
output; it wasn't, `nerdctl ps` showing the container still `Up` confirmed
it was working).

Verified in the registry:
```bash
curl -H "Authorization: token <token>" http://10.137.160.1:3000/api/v1/packages/rpamu
# -> includes {"name": "hello-camel-service", "version": "1.0.0"}
```

## Deploy

Manifests in [../../apps/java-app/k8s/](../../apps/java-app/k8s/), applied
directly (no Argo CD yet):

```bash
kubectl apply -f namespace.yaml -f configmap.yaml -f deployment.yaml -f service.yaml -f ingress.yaml
```

Confirmed `1/1 Running`, landed on `k8s-node`.

## Ingress

Traefik (installed as a standard part of cluster setup — see
[k8s-setup.md](k8s-setup.md)) was already running by the time this app was
deployed. Just needed `apps/java-app/k8s/ingress.yaml`'s
`ingressClassName: traefik` and applying it, no extra ingress-specific work
for this app.

## Verified working end-to-end

```bash
curl -H "Host: hello.lab.local" http://10.137.160.147:31834/api/hello
curl -H "Host: hello.lab.local" http://10.137.160.148:31834/api/hello
```

Both nodes answered identically (NodePort listens cluster-wide, regardless
of which node the ingress controller pod actually landed on). Response
confirmed the `ConfigMap`-supplied env var took effect —
`"environment": "k8s-lab"` (the ConfigMap's value), not `"local"` (the
app's built-in default) — proving the whole env-var → ConfigMap → pod path
works, not just that the pod started.

Also confirmed the route registered correctly via Traefik's own API:
```bash
curl http://10.137.160.147:30469/api/http/routers | python3 -c "..."
# -> hello-camel-service-hello-camel-service-hello-lab-local@kubernetes
#    Host("hello.lab.local") && PathPrefix("/")
```

## Status

- [x] Image built and pushed to Gitea registry via Kaniko
- [x] App deployed to `hello-camel-service` namespace, `1/1 Running`
- [x] Traefik installed and healthy (replaced ingress-nginx)
- [x] Ingress-routed access verified from both nodes, env var config
      confirmed flowing through, route confirmed in Traefik's own API
- [ ] `/etc/hosts` entry or real DNS for `hello.lab.local` (currently
      accessed via explicit `Host:` header only)
- [ ] Automate build+push via Tekton (currently manual Kaniko run)
- [ ] Automate deploy via Argo CD (currently manual `kubectl apply`)
