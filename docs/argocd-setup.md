# Argo CD: GitOps CD, generic

Argo CD is the CD half of this lab's pipeline: Tekton (see
[tekton-setup.md](tekton-setup.md)) builds and pushes an image on a tag
push and stops there — it does not touch the cluster. Argo CD watches a
git repo and keeps the cluster's live state in sync with whatever's
committed under an app's manifest directory. Bumping a deployment (image
tag, env var, replica count, whatever) is a **git commit**, not a
`kubectl apply`.

**Deliberately a separate repo from application source.** Argo CD's
`Application` points at a dedicated manifests repo, not the app's own
source/CI repo — so a commit Tekton's pipeline makes (if it ever makes
one) can never be the same commit Argo CD syncs from, and the two
concerns (build vs. deploy) stay genuinely decoupled rather than just
conventionally separated by directory.

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

## 5. Create the manifests repo and give Argo CD read access

A separate git repo, holding only deploy manifests, one directory per
app:

```
apps/<app-name>/*.yaml
```

A private Gitea repo needs credentials, added as a `Secret` labeled for
Argo CD to pick it up as a repository — **not committed to git**. This is
per-repo, so a repo for source/CI and a repo for manifests each need
their own such `Secret` if both are private:

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

One `Application` per deployable unit, pointing `repoURL` at the
**manifests repo** (not the app's source repo) and `path` at that app's
directory within it. Nothing app-specific belongs in Argo CD's own
install — it all lives in this one small resource:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: <app-name>
  namespace: argocd
spec:
  project: default
  source:
    repoURL: http://<gitea-host>:3000/<user>/<manifests-repo>.git
    targetRevision: main
    path: apps/<app-name>
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

`automated.prune: true` means removing a manifest from the app's
directory and pushing deletes the corresponding cluster object too — Argo CD treats the git
directory as the full desired state, not just an overlay of additions.
`selfHeal: true` means a manual `kubectl edit`/`kubectl delete` against a
tracked resource gets reverted back to what git says on the next
reconcile — deliberate, since the whole point is that git, not
someone's terminal, is the source of truth.

## 7. Auto-bump the image tag: Argo CD Image Updater

Without this, step 2 below is a manual edit. With it, the entire chain
from `git tag` to a running pod is unattended.

Image Updater patches an image reference through Argo CD's
Kustomize/Helm parameter mechanism — a plain manifest directory (no
`kustomization.yaml`/`Chart.yaml`) has no such hook, so the app's
manifest directory needs at least a minimal Kustomize base first:

```yaml
# apps/<app-name>/kustomization.yaml, in the manifests repo
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - configmap.yaml
  - deployment.yaml
  - service.yaml
  - ingressroute.yaml
images:
  - name: <registry>/<user>/<app-name>
    newTag: <whatever's currently deployed>
```

No overlays needed for a single-environment lab — this is purely to give
Argo CD a parameter surface to patch.

Install (pin a version):

```bash
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj-labs/argocd-image-updater/v0.15.1/manifests/install.yaml
```

Point it at the registry — Gitea's registry is plain HTTP, so it needs
`insecure: true`, and a `pullsecret` reference for private-repo auth (the
same `dockerconfigjson` Secret used for image pulls, duplicated into the
`argocd` namespace since Image Updater's RBAC only covers secrets there):

```bash
kubectl -n argocd create secret generic gitea-registry-creds \
  --type=kubernetes.io/dockerconfigjson \
  --from-literal=.dockerconfigjson='<same content as the pull secret>'

kubectl apply -f - <<'EOF'
apiVersion: v1
kind: ConfigMap
metadata:
  name: argocd-image-updater-config
  namespace: argocd
data:
  registries.conf: |
    registries:
      - name: gitea
        api_url: http://<gitea-host>:3000
        prefix: <gitea-host>:3000
        ping: false
        insecure: true
        credentials: pullsecret:argocd/gitea-registry-creds
EOF

kubectl -n argocd rollout restart deployment argocd-image-updater
```

Then annotate the `Application` itself — no separate config file per app,
it all lives on the resource:

```yaml
metadata:
  annotations:
    argocd-image-updater.argoproj.io/image-list: <alias>=<registry>/<user>/<app-name>
    argocd-image-updater.argoproj.io/<alias>.update-strategy: semver
    argocd-image-updater.argoproj.io/<alias>.allow-tags: "regexp:^[0-9]+\\.[0-9]+\\.[0-9]+$"
    argocd-image-updater.argoproj.io/write-back-method: git
```

`update-strategy: semver` picks the highest valid-semver tag seen in the
registry; `allow-tags` restricts it to tags that actually look like a
release version (so a Tekton-pushed dev/SHA-tagged image, or `latest`,
never gets picked up by accident). `write-back-method: git` is what makes
this GitOps-correct — Image Updater doesn't call `kubectl set image`
against the live cluster; it commits the new tag back to the **manifests
repo**, and Argo CD's normal sync (not Image Updater) is what actually
deploys it.

No separate git credentials to configure: Image Updater reuses the same
repository `Secret` already registered with Argo CD for that repo (step
5). On a new tag, it writes (or updates) an
`.argocd-source-<app-name>.yaml` file next to the manifests — a
parameter-override file Argo CD's Kustomize renderer applies
automatically on top of the base `kustomization.yaml`, so the base file
itself is left untouched.

Poll interval is 2 minutes by default (`--interval`, tunable on the
`argocd-image-updater` Deployment's args).

## Day-to-day flow

1. Tekton builds+pushes an image on a tag push (see
   [tekton-setup.md](tekton-setup.md)) — in the app's **source** repo.
2. Argo CD Image Updater's next poll cycle sees the new tag, decides it's
   the new highest semver match, and commits/pushes a parameter override
   to the **manifests** repo — no manual edit needed if this is set up
   (see step 7 above); otherwise, edit the app's manifest to point at the
   new tag, commit, push, by hand.
3. Argo CD picks up that commit on its next poll (default: **3 minutes**)
   and reconciles the cluster to match. To see it immediately instead of
   waiting:
   ```bash
   kubectl -n argocd annotate application <app-name> \
     argocd.argoproj.io/refresh=hard --overwrite
   ```
4. Watch it land: `kubectl -n argocd get application <app-name> -w`, or
   the Argo CD UI.

No manual `kubectl apply` step remains anywhere in this flow, and with
Image Updater running, no manual git edit either — `git tag && git push`
on the source repo is the entire release process end to end.
