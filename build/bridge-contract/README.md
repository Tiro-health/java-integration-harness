# Bridge contract test (static type-check)

Type-checks the **actual shipped** `form-filler-swing/src/main/resources/health/tiro/formfiller/swing/tiro-swm-bridge.js`
against the published TypeScript types of `@tiro-health/web-sdk` — the `<tiro-form-filler>` web component the
bridge drives. It exists to catch **drift between the bridge and the frontend**, e.g. a
`submit({ intent })`-vs-`submit({ status })` mismatch. The .NET harness shipped exactly that bug
(Tiro-health/net-integration-harness#19); this guards the identical contract on the Java side.

The bridge file is `include`d by relative path (never copied), so the bytes checked are the bytes shipped.

## Version target: the embedded pin

CI installs the version pinned in [`../web-sdk/package.json`](../web-sdk/package.json) — the bundle the
harness embeds and serves (GH-24). That is the only frontend any deployment can be running, so it is the
only pairing worth blocking a merge or a release on. The pin is read, never restated: bump it in one place
and this check follows.

Two things it now also guards, beyond the call shapes:

- `describeClient()` casts the registered element to the SDK's own class before reading
  `static version`, so a release that drops that field turns this red instead of quietly
  reporting `null` to the host forever.
- `submit({ status: "in-progress" })` — the save-draft path. This was expectedly **red** while
  the harness tracked the floating `latest` channel and stable was `0.2.3`; the pin is `0.3.3`,
  so it is green, and the workflow's `continue-on-error` is gone.

A second, **non-blocking** nightly job (`latest-drift`) checks the bridge against
`@tiro-health/web-sdk@latest`. A red run there breaks nothing — no deployment runs `latest` — but it is the
early warning that the next pin bump will need bridge work.

## Running locally

The package is on **GitHub Packages**, so npm needs a token with `read:packages`. Using the GitHub CLI:

```sh
gh auth refresh -h github.com -s read:packages    # one-time, adds the scope
cd build/bridge-contract
export NODE_AUTH_TOKEN=$(gh auth token)
npm ci
npm install --no-save "@tiro-health/web-sdk@$(node -p "require('../web-sdk/package.json').dependencies['@tiro-health/web-sdk']")"
npm run typecheck
```

In CI the ephemeral `GITHUB_TOKEN` (with `permissions: packages: read`) is used — no stored secret. The package
must grant this repo Actions read access (GitHub Packages → package → *Manage Actions access*).

## Follow-up

A heavier **behavioral** smoke test (real element, dockerized SDC server) is tracked separately in #16.
