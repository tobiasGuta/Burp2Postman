# Changelog

## 0.1.1 - 2026-07-25

- **Bugfix:** Bypassed Cloudflare 403 blocks on the Postman API by updating the `User-Agent` header to mimic the native Postman client (`PostmanRuntime`).
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
