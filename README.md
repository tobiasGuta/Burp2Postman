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

1. Extract the release ZIP.
2. In Burp Suite, open **Extensions → Installed**.
3. Click **Add**.
4. Select extension type **Java**.
5. Choose `Burp2Postman.jar`.
6. Confirm that a **Burp2Postman** suite tab appears.

Use a current Burp Suite build with Montoya API support. The included JAR targets Java 17 bytecode.

## First-time setup

1. In Postman, create a Postman API key from your account settings.
2. Open Burp's **Burp2Postman** tab.
3. Leave the API base URL as `https://api.postman.com` unless your organization uses a different official Postman API endpoint.
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
   - **Choose destination and send…** — dynamically select another workspace, collection, and folder for this send.

## Request conversion behavior

Burp2Postman transfers:

- HTTP method
- Full URL
- Query parameters
- Duplicate and custom headers
- Cookies and authorization headers in exact mode
- Raw JSON, XML, HTML, JavaScript, text, and GraphQL-style payloads
- URL-encoded form fields

By default it removes headers that Postman should recalculate, including `Content-Length`, `Transfer-Encoding`, `Connection`, and related transport headers. Custom `Host` headers can be preserved or removed from the extension tab.

### Multipart limitation

Version 0.1.0 preserves captured multipart bodies as raw body data. Burp has the transmitted file bytes but not necessarily the original local file path that Postman needs for a normal file picker. Multipart requests containing files may therefore need manual adjustment in Postman.

## Security and privacy

Direct sending uses the Postman cloud API. An exact send may upload session cookies, bearer tokens, CSRF tokens, personal data, private endpoints, and request bodies to the selected Postman workspace.

- Use exact mode only when the destination workspace is appropriate for that data.
- Sanitized mode is best effort, not a guarantee. Review sensitive requests before sending.
- The API key is not saved by default.
- Enabling **Remember API key** stores it through Burp's extension preferences. Treat the Burp user configuration and project environment as sensitive.
- Response bodies are not uploaded by this version.

## Build from source

Requirements:

- JDK 17 or newer
- Maven 3.9 or newer

Linux/macOS:

```bash
./build.sh
```

Windows PowerShell:

```powershell
./build.ps1
```

Or directly:

```bash
mvn clean package
```

The resulting extension is `target/Burp2Postman.jar`.

## Project layout

```text
src/main/java/com/tobiasare/burp2postman/
├── Burp2PostmanExtension.java   Entry point and context menu
├── Burp2PostmanPanel.java       Main settings/destination tab
├── DestinationDialog.java       Per-send destination selector
├── PostmanClient.java           Postman API client
├── RequestConverter.java        Burp request → Postman request conversion
├── ConfigStore.java             Burp preference storage
├── MiniJson.java                Dependency-free JSON parser/writer
└── Models.java                  Internal records
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
