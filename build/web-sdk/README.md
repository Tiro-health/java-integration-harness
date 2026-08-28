# web-sdk pin (GH-24)

This directory pins the **exact** `@tiro-health/web-sdk` version that `form-filler-swing`
embeds and serves to the page. The harness ships the bundle it was validated against — the
SDK version is not a choice integrators or customers make (see the decision record in
[net-integration-harness#64](https://github.com/Tiro-health/net-integration-harness/issues/64)).

- `package.json` — the pin: an exact version, no range.
- `copy-bundle.mjs` — stages the bundle + generated `web-sdk.version.json` into
  `form-filler-swing/src/main/resources/health/tiro/formfiller/swing/` (committed there,
  packaged into the jar).

## How it reaches the page

`WebSdkAssets` extracts the bundle to
`$TMPDIR/tiro-form-filler-<user>/web-sdk-<version>-<sha12>/tiro-web-sdk.<version>.iife.js`, and
`BridgeScriptLoader` prepends `window.__tiroSdkUrl = "file://…"` to the bridge source that
both browser adapters inject. The bridge creates the `<script>` tag itself; the page — the
generated default page, or an integrator's own — carries no SDK reference at all.

The version is in the **file name** because Chromium caches by URL: at a constant path an
upgraded harness could keep running the previous release's bundle, exactly the bridge↔element
skew embedding exists to prevent. The **folder** is keyed by version *and* content hash, so
iterating on the bundle locally can't reuse a stale extraction either.

It is also **per user and owner-only** (`rwx------` where POSIX applies). On Windows `%TEMP%`
is already per-account, but `java.io.tmpdir` is `/tmp` on Linux and macOS: a shared folder
there would let the first account's `755` directory break every later account's launch — the
generated page is written into it — and would put the served bundle at a path anyone on the
host could write to before the harness first ran.

A `file://` script only loads into a `file://` document. A page served over http(s) therefore
cannot run the embedded bundle: the bridge reports `source: "error"` at handshake and the host
refuses the session (`WebSdkLoadException`) rather than letting a form silently fail to render.

## The bundle is committed

`form-filler-swing/src/main/resources/.../tiro-web-sdk.iife.js` and its `web-sdk.version.json`
are **tracked in this repo**, so `git clone && mvn package` works with no token, no npm install
and no staging step — for a new contributor, a fresh CI runner, or you on a new machine. The
same bytes are served unauthenticated from `cdn.tiro.health/sdk/v<version>/tiro-web-sdk.iife.js`
and ship inside the jar on Maven Central, so a `read:packages` token in the build path would
have guarded nothing.

The cost is ~6 MB of git history per pin bump, a few times a year. Three guards stand behind
it, all in `WebSdkAssetsTest` (no network, so they run on every `mvn test`):

1. the bundle is on the classpath and extracts;
2. the staged manifest's version matches the pin;
3. the manifest's sha256 matches the actual bytes — which is what replaces npm's `integrity`,
   thrown away by committing the file. `WebSdkAssets` also digests the bundle the *first* time
   it extracts it for a given version, so a repacked jar is caught on a cold start; later
   starts reuse the extracted file without re-digesting it, which is why that file lives in an
   owner-only per-user directory rather than a shared one.

`.gitattributes` marks the bundle `binary`. Without it, `text=auto` would classify a file with
no NUL bytes as text: line endings would be rewritten on a Windows checkout (breaking the
hash), and two branches bumping the pin would 3-way **text-merge** into a clean-looking splice
of two versions that the pin/manifest check cannot see, because those live in other files.

## Bumping the pin

`copy-bundle.mjs` exists for exactly this, and this is the one time you run it:

```sh
# one-time, if you have never authenticated to GitHub Packages
gh auth refresh -h github.com -s read:packages
npm config set //npm.pkg.github.com/:_authToken "$(gh auth token)"
```

```sh
# edit package.json to the new version, then
cd build/web-sdk
npm ci --ignore-scripts
node copy-bundle.mjs
git add -A build/web-sdk form-filler-swing/src/main/resources/health/tiro/formfiller/swing
```

**Commit the result.** Bumping `package.json` alone fails the build: `WebSdkAssetsTest`
compares the pin against the committed `web-sdk.version.json` and refuses a mismatch, because
a stale bundle is self-consistent and nothing downstream would catch it. A bot that edits only
the pin therefore arrives red by design; a maintainer runs the above on its branch and pushes.
A web-sdk bump ships new bytes to clinicians and wants human review anyway.

Bumping the pin also re-points `build/bridge-contract` (it reads the version from here), so the
type-check on that PR is the check that the bridge still matches the new frontend.
