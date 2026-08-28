// Ambient globals the bridge relies on that aren't in the standard DOM lib.
// Declared loosely (any) on purpose: the bridge<->host transport plumbing is out of
// scope for this contract check, which exists solely to verify the bridge's calls
// against the <tiro-form-filler> (@tiro-health/web-sdk) API. Keeping these `any` lets
// `tsc --checkJs` focus its errors on the element contract.

interface Window {
  __swmBridgeLoaded?: boolean;
  // Defined by BridgeScriptLoader's preamble: the file:// URL of the embedded
  // @tiro-health/web-sdk bundle the bridge injects (GH-24).
  __tiroSdkUrl?: string;
  swmReceiveMessage?: (jsonStr: string) => void;
  SmartWebMessaging?: any;
}
