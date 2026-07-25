# Burp2Postman

Burp2Postman is a Java/Montoya Burp Suite extension that sends selected HTTP requests directly to Postman. It does not export a collection file and does not hardcode workspace, collection, or folder IDs.

## What it does

- Adds a **Burp2Postman** tab to Burp Suite.
- Connects to the Postman API using an API key you provide.
- Dynamically loads the workspaces available to that key.
- Dynamically loads collections from the selected workspace.
- Loads the collection's folder tree and lets you choose a folder or the collection root.
- Adds a **Burp2Postman** right-click menu for one or multiple selected requests.
- Supports exact sending and best-effort sanitized sending.
- Can create a new collection or folder from inside Burp.
- Stores the selected default destination in Burp preferences.
- Keeps the API key session-only unless **Remember API key** is explicitly enabled.

## Install the ready-to-load JAR

1. Download `Burp2Postman.jar` from the GitHub Release for the version you want.
2. In Burp Suite, open **Extensions → Installed**.
3. Click **Add**.
4. Select extension type **Java**.
5. Choose `Burp2Postman.jar`.
6. Confirm that a **Burp2Postman** suite tab appears.

Use a current Burp Suite build with Montoya API support. The release JAR targets Java 17 bytecode.

## First-time setup

1. In Postman, create a Postman API key from your account settings.
2. Open Burp's **Burp2Postman** tab.
3. Leave the API endpoint locked to `https://api.postman.com` unless your organization requires a different trusted endpoint.
4. Paste the API key and click **Connect / Refresh**.
5. Select a workspace, collection, and optional folder.
6. Click **Save default destination**.

No destination IDs are compiled into the extension. The workspaces, collections, and folders are loaded at runtime from Postman.

## Send a request

1. Select one or more entries in Burp Proxy HTTP history, Logger, Repeater history, or another Burp view that supplies selected HTTP request/response objects to extension context menus.
2. Right-click and choose **Burp2Postman**.
3. Choose one of:
   - **Send exact** — preserves request headers and body, except transport-managed headers configured for removal.
   - **Send sanitized** — replaces common cookies, authorization values, CSRF tokens, API keys, and selected body secrets with Postman variables.
   - **Choose destination and send exact…** — dynamically select another destination and preserve captured secrets.
   - **Choose destination and send sanitized…** — dynamically select another destination and sanitize the request.

## Request conversion behavior

Burp2Postman transfers:

- HTTP method
- Full URL
- Query parameters
- Duplicate and custom headers
- Duplicate query parameters, with sensitive query values replaced in both the URL and structured query data when sanitized mode is selected
- Cookies and authorization headers in exact mode
- Raw JSON, XML, HTML, JavaScript, text, and GraphQL-style payloads
- URL-encoded form fields

By default it removes headers that Postman should recalculate, including `Content-Length`, `Transfer-Encoding`, `Connection`, and related transport headers. Custom `Host` headers can be preserved or removed from the extension tab.

### Header payload compatibility

The default **Structured array** mode sends each header as a `{key, value}` object, matching Postman's documented structured request schema. Duplicate headers and custom `Host` values remain separate entries.

The **Legacy newline-separated string** mode is retained because Postman's legacy request-creation endpoint rejected or dropped custom headers for this project when an array was used in version 0.1.1. Select legacy mode only when the structured format is incompatible with the Postman API behavior for your account. Both representations have automated duplicate-header and custom-`Host` regression coverage.

### Multipart limitation

Version 0.2.0 preserves captured multipart bodies as raw body data. Burp has the transmitted file bytes but not necessarily the original local file path that Postman needs for a normal file picker. Multipart requests containing files may therefore need manual adjustment in Postman.

## Security and privacy

Direct sending uses the Postman cloud API. An exact send may upload session cookies, bearer tokens, CSRF tokens, personal data, private endpoints, and request bodies to the selected Postman workspace.

- Use exact mode only when the destination workspace is appropriate for that data.
- Sanitized mode is best effort, not a guarantee. Review sensitive requests before sending.
- The API key is not saved by default.
- Enabling **Remember API key** stores it through Burp's extension preferences. Treat the Burp user configuration and project environment as sensitive.
- The API endpoint is locked to `api.postman.com` by default. A custom endpoint must be enabled under **Advanced**, shows the exact hostname that will receive `X-API-Key`, and requires explicit confirmation before first use in the current configuration.
- Authenticated API requests do not follow redirects. Configure and confirm the final HTTPS endpoint directly.
- The Postman-compatible User-Agent is accompanied by `X-Burp2Postman-Version`, which identifies the extension without triggering the Postman API gateway's HTML challenge response.
- Saved destinations include the API endpoint where they were loaded. Changing endpoints clears the destination selectors and requires reconnecting; sends are also rejected if a stale destination does not match the current endpoint.
- Response bodies are not uploaded by this version.

## Build from source

Requirements:

- JDK 17 or newer
- Maven 3.9 or newer

Linux/macOS:

```bash
cd source
./build.sh
```

Windows PowerShell:

```powershell
cd source
./build.ps1
```

Or directly:

```bash
cd source
mvn clean package
```

The resulting extension is `source/target/Burp2Postman.jar` relative to the repository root.

Run unit tests with `cd source && mvn test`. An opt-in live compatibility test can validate both header formats against a disposable Postman collection:

```bash
cd source
POSTMAN_API_KEY=... POSTMAN_TEST_COLLECTION_ID=... mvn -Plive-postman verify
```

The live test creates one structured and one legacy request, reloads the collection to confirm duplicate headers and a custom `Host` survived, and attempts to remove the test requests afterward.

## Project layout

```text
source/
|-- pom.xml
|-- build.ps1
|-- build.sh
`-- src/
    |-- main/java/com/tobiasguta/burp2postman/
    |   |-- ApiEndpoint.java              API-key destination policy
    |   |-- Burp2PostmanExtension.java    Entry point and context menu
    |   |-- Burp2PostmanPanel.java        Main settings/destination tab
    |   |-- DestinationDialog.java        Per-send destination selector
    |   |-- PostmanClient.java            Postman API client
    |   |-- RequestConverter.java         Burp request to Postman conversion
    |   |-- ConfigStore.java              Burp preference storage
    |   |-- MiniJson.java                 Dependency-free JSON parser/writer
    |   `-- Models.java                   Internal records
    `-- test/java/com/tobiasguta/burp2postman/
```

## Current limitations

- Live Postman and Burp integration must be validated with your own account and Burp installation.
- No response export.
- No automatic duplicate detection or updating of an existing Postman request yet.
- No hostname-to-destination routing rules yet.
- Sanitization does not understand every custom application secret format.
- Very large requests are subject to Postman API and network limits.

## License

MIT
