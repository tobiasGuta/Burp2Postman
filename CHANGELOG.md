# Changelog

## 0.2.0 - 2026-07-25

- Default-locked API-key delivery to `api.postman.com`; custom HTTPS endpoints are now an Advanced option that displays and requires confirmation of the exact receiving hostname.
- Disabled HTTP redirects for authenticated Postman API calls so `X-API-Key` is never forwarded to a redirect target.
- Added structured-array and legacy-string header compatibility modes. Structured headers are the default; legacy mode remains available for the legacy request endpoint behavior seen in 0.1.1.
- Added separate exact and sanitized per-destination context-menu actions.
- Added generation guards to workspace, collection, and folder loaders so stale asynchronous results cannot overwrite a newer selection.
- Replaced regex-only JSON sanitization with recursive parsed-JSON sanitization, retaining regex fallback for malformed captures.
- Added unit tests, an opt-in live Postman header compatibility test, and GitHub Actions JAR builds/releases.
- Renamed the Java/Maven namespace from `com.tobiasare` to `com.tobiasguta`.
- Sanitized sensitive query values in both the raw URL and structured query payload while preserving literal `+` characters.
- Bound saved destinations to their originating API endpoint and invalidate or reject stale cross-endpoint destinations.
- Restored Postman API gateway compatibility by keeping the required `PostmanRuntime/x.y.z` User-Agent shape and exposing `Burp2Postman/0.2.0` through a separate identity header.

## 0.1.1 - 2026-07-25

- **Bugfix:** Improved compatibility with the Postman API gateway by using a Postman-compatible `User-Agent`.
- **Bugfix:** Resolved a 404 error during folder creation by prioritizing the globally unique `uid` over the short `id` for collections, folders, and requests.
- **Bugfix:** Fixed an issue where custom headers (like `Host`) were stripped upon export. The headers payload now correctly uses a newline-separated string format required by the Postman API's legacy request endpoint instead of a JSON array.

## 0.1.0 - 2026-07-24

Initial MVP release.

- Dynamic Postman workspace, collection, and folder selection.
- Direct request creation through the Postman API.
- Burp context-menu integration for single and multi-selection.
- Exact and sanitized sending modes.
- New collection and folder creation.
- Session-only API key by default, with optional persistence.
- JSON, raw, and URL-encoded request-body conversion.
- Transport-header filtering and custom Host-header control.
- Dependency-free runtime JAR.
