# Bridge contract test (static type-check)

Type-checks the **actual shipped** `form-filler-swing/src/main/resources/health/tiro/formfiller/swing/tiro-swm-bridge.js`
against the published TypeScript types of `@tiro-health/web-sdk` — the `<tiro-form-filler>` web component the
bridge drives. It exists to catch **drift between the bridge and the external frontend**, e.g. a
`submit({ intent })`-vs-`submit({ status })` mismatch. The .NET harness shipped exactly that bug
(Tiro-health/net-integration-harness#19); this guards the identical contract on the Java side.

The bridge file is `include`d by relative path (never copied), so the bytes checked are the bytes shipped.

## Version target: floating `latest` (intentionally)

CI installs `@tiro-health/web-sdk@latest` fresh on every run — **not** a pinned version. The harness defaults to
the floating `cdn.tiro.health/sdk/latest` channel (`FormFillerConfig.DEFAULT_SDK_URL`), so the check tracks
whatever `latest` currently is. A red run with no harness change means **the frontend's `latest` moved the
contract** — that's the alarm, not a flake.

## ⚠️ Currently expected to be RED — and why

The bridge's `save-draft` path (`requestSubmit("save-draft")` and `SmartWebMessaging.saveProgress()`) calls
`submit({ status: "in-progress" })`. That option was added in **web-sdk `0.3.0`**, which at time of writing is
only on the `next`/`rc` channel — stable `latest` is `0.2.3`, whose `submit()` takes **no arguments**. So:

| target | result |
|--------|--------|
| `latest` (0.2.3) | ❌ red — `submit({status})` not in stable yet |
| `next` (0.3.0-rc) | ✅ green — bridge matches |

This red is **correct**: it reports that `save-draft` does not function against the stable frontend the harness
loads by default. **Save-draft requires `@tiro-health/web-sdk` >= 0.3.0.** When 0.3.0 is promoted to the `latest`
dist-tag this check goes green automatically; at that point remove the `continue-on-error` in the workflow and
make it a required status check.

## Running locally

The package is on **GitHub Packages**, so npm needs a token with `read:packages`. Using the GitHub CLI:

```sh
gh auth refresh -h github.com -s read:packages    # one-time, adds the scope
cd tests/bridge-contract
export NODE_AUTH_TOKEN=$(gh auth token)
npm ci
npm install --no-save @tiro-health/web-sdk@latest  # or @next to see it green
npm run typecheck
```

In CI the ephemeral `GITHUB_TOKEN` (with `permissions: packages: read`) is used — no stored secret. The package
must grant this repo Actions read access (GitHub Packages → package → *Manage Actions access*).

## Follow-up

A heavier **behavioral** smoke test (Playwright, real element from the CDN) is tracked separately in #16.
