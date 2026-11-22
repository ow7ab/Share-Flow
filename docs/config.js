/**
 * Share Flow Official Public Download Portal Configuration
 *
 * Source repository (PRIVATE): ow7ab/Share-Flow
 * Public distribution repository (PUBLIC Releases + GitHub Pages): ow7ab/Share-Flow-Releases
 *
 * Browser-side JavaScript NEVER contains tokens or credentials.
 * All APK downloads and release metadata are fetched publicly without GitHub authentication.
 */
window.SHAREFLOW_PORTAL_CONFIG = Object.freeze({
  releasesRepo: "ow7ab/Share-Flow-Releases",
  assetFileName: "Share-Flow.apk",
  manifestFileName: "version.json",
  directLatestApkUrl: "https://github.com/ow7ab/Share-Flow-Releases/releases/latest/download/Share-Flow.apk",
  portalUrl: "https://ow7ab.github.io/Share-Flow-Releases/",
  customManifestUrl: ""
});
