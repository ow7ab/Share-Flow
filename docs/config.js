/**
 * Share Flow Official Download Portal Configuration
 *
 * Default mode fetches the latest published APK directly from GitHub Releases
 * for `ow7ab/Share-Flow` (asset: Share-Flow.apk, never ZIP).
 *
 * If the source repository is kept private, point `releasesRepo` to a public
 * releases-only mirror repository (e.g., "ow7ab/Share-Flow-Releases") OR set
 * `customManifestUrl` to your standalone HTTPS download server's `version.json`.
 */
window.SHAREFLOW_PORTAL_CONFIG = Object.freeze({
  releasesRepo: "ow7ab/Share-Flow",
  assetFileName: "Share-Flow.apk",
  manifestFileName: "version.json",
  customManifestUrl: ""
});
