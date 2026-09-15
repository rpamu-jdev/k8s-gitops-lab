# Argo CD: GitOps CD, generic

Argo CD is the CD half of this lab's pipeline: Tekton (see
[tekton-setup.md](tekton-setup.md)) builds and pushes an image on a tag
push and stops there — it does not touch the cluster. Argo CD watches this
git repo and keeps the cluster's live state in sync with whatever's
committed under an app's `k8s/` manifests. Bumping a deployment (image
tag, env var, replica count, whatever) is a **git commit**, not a
`kubectl apply`.

## 1. Install

Pin a version, same as everything else in this lab:

```bash
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/v2.13.2/manifests/install.yaml
kubectl -n argocd wait --for=condition=available --timeout=180s \
  deployment/argocd-server deployment/argocd-repo-server deployment/argocd-dex-server \
  deployment/argocd-applicationset-controller deployment/argocd-redis \
  deployment/argocd-notifications-controller
```

## 2. Serve plain HTTP (no TLS backend passthrough needed)

`argocd-server` terminates its own TLS by default. Since this lab has no
TLS anywhere else (Traefik itself only serves plain HTTP — see
[k8s-setup.md](k8s-setup.md)), the simplest fix is Argo CD's own documented
"insecure" mode: it drops its self-signed cert and speaks plain HTTP
instead, so Traefik proxies straight through with no `ServersTransport`
trick (unlike the Kubernetes Dashboard, which keeps its cert — see
[dashboards-setup.md](dashboards-setup.md)):

```bash
kubectl -n argocd patch configmap argocd-cmd-params-cm --type merge \
  -p '{"data":{"server.insecure":"true"}}'
kubectl -n argocd rollout restart deployment argocd-server
```

## 3. Route through Traefik with its own hostname

Same convention as the dashboards — a standalone UI gets its own `Host`
rather than a path prefix:

```yaml
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: argocd-server
  namespace: argocd
spec:
  entryPoints:
    - web
  routes:
    - match: Host(`argocd.<your-domain>`)
      kind: Rule
      services:
        - name: argocd-server
          port: 80
```

Add the `/etc/hosts` entry (or real DNS) the same way as every other
hostname in this lab.

## 4. Log in

```bash
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath='{.data.password}' | base64 -d
```

Username `admin`. Rotate/delete `argocd-initial-admin-secret` once you've
logged in and changed the password, per Argo CD's own docs.

### Rotating the admin password without the CLI installed locally

The `argocd-server` pod already bundles the `argocd` binary, so hash the
new password inside the pod itself rather than installing the CLI
anywhere:

```bash
kubectl -n argocd exec deploy/argocd-server -- argocd account bcrypt --password '<new-password>'

kubectl -n argocd patch secret argocd-secret -p \
  '{"stringData": {"admin.password": "<bcrypt-hash-from-above>", "admin.passwordMtime": "'"$(date -u +%FT%TZ)"'"}}'

kubectl -n argocd delete secret argocd-initial-admin-secret
```

## 5. Give Argo CD read access to the git repo

A private Gitea repo needs credentials, added as a `Secret` labeled for
Argo CD to pick it up as a repository — **not committed to git**:

```bash
kubectl apply -f - <<'EOF'
apiVersion: v1
kind: Secret
metadata:
  name: <name>
  namespace: argocd
  labels:
    argocd.argoproj.io/secret-type: repository
stringData:
  type: git
  url: http://<gitea-host>:3000/<user>/<repo>.git
  username: <user>
  password: <token>
EOF
```

## 6. Define an Application

One `Application` per deployable unit, pointing at that app's `k8s/`
manifest directory. Nothing app-specific belongs in Argo CD's own install —
it all lives in this one small resource:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: <app-name>
  namespace: argocd
spec:
  project: default
  source:
    repoURL: http://<gitea-host>:3000/<user>/<repo>.git
    targetRevision: main
    path: apps/<app-name>/k8s
  destination:
    server: https://kubernetes.default.svc
    namespace: default
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=false
```

`automated.prune: true` means removing a manifest from `k8s/` and pushing
deletes the corresponding cluster object too — Argo CD treats the git
directory as the full desired state, not just an overlay of additions.
`selfHeal: true` means a manual `kubectl edit`/`kubectl delete` against a
tracked resource gets reverted back to what git says on the next
reconcile — deliberate, since the whole point is that git, not
someone's terminal, is the source of truth.

## Day-to-day flow

1. Tekton builds+pushes an image on a tag push (see
   [tekton-setup.md](tekton-setup.md)).
2. Edit the app's `k8s/deployment.yaml` to point at the new tag (or change
   any other manifest field), commit, push.
3. Argo CD picks up the change on its next poll (default: **3 minutes**)
   and reconciles the cluster to match. To see it immediately instead of
   waiting:
   ```bash
   kubectl -n argocd annotate application <app-name> \
     argocd.argoproj.io/refresh=hard --overwrite
   ```
4. Watch it land: `kubectl -n argocd get application <app-name> -w`, or
   the Argo CD UI.

No manual `kubectl apply` step remains anywhere in this flow.
