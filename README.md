# stupidMCP4j-client

> **A bare-metal, dependency-isolated Model Context Protocol (MCP) client library for Java 17+.**  
> Designed for clean LLM tool orchestration without heavy reactive frameworks or forced runtime dependencies.

`stupidMCP4j-client` is an unopinionated, lightweight client designed to interface directly with MCP servers over HTTP. It executes tool discovery and tool invocations while returning raw JSON payloads and cleanly extracted text output suitable for immediate forwarding to LLMs.

---

## ⚠️ Specification Compliance & Known Limitations

> **IMPORTANT:** This library targets the **MCP 2026-07-28** specification, but is currently **partially compliant** because it operates strictly over standard HTTP POST request/response (`application/json`). 

Before using this library in production or against public MCP endpoints, be aware of the following architectural constraints:

1. **No SSE (`text/event-stream`) Processing Yet:**
   * The MCP Streamable HTTP specification dictates that servers may respond with `text/event-stream` for intermediate updates, progress reporting, or streaming responses.
   * **Current State:** The client currently assumes immediate `application/json` responses. If a target server responds with an SSE stream (`Content-Type: text/event-stream`), execution will throw an error or fail to parse. Full single-pass SSE extraction is planned for a future update.
2. **Text-Only Tool Payloads:**
   * MCP allows mixed content blocks (`image`, `embedded resource`, `binary`). 
   * This client deliberately parses and extracts only `"type": "text"` content blocks. Non-text blocks are ignored during extraction.
3. **No Multi Round-Trip Requests (MRTR) / Interactive Input:**
   * The client explicitly advertises an empty capabilities object:  
     `"io.modelcontextprotocol/clientCapabilities": {}`.
   * It does not support interactive user elicitation, sampling, or directory roots mid-flight. If a server issues `resultType: "input_required"`, the client raises an `UnsupportedOperationException`.
4. **No STDIO Support:**
   * This library is built strictly for remote HTTP endpoints via Java's native `HttpClient`. It does not execute or communicate with local sub-processes over `stdio`.

---

## Features

* **Zero Leaked Dependencies:** The public API exposes standard Java primitives (`String`, `List<String>`, `Map<String, Object>`). Jackson is used internally for AST validation, but no third-party types leak into your signatures.
* **LLM Passthrough Ready:** `toolsList()` returns each tool schema as a self-contained, unescaped JSON string (`List<String>`), allowing zero-copy passthrough directly into OpenAI, Anthropic, or LangChain4j tool definitions.
* **Automatic Cursor Draining:** Transparently follows pagination cursors (`nextCursor`) across multiple pages with cycle-detection guards (capped at 20 pages).
* **MCP 2026 Ready Envelope:** Automatically injects mandatory `_meta` blocks (`io.modelcontextprotocol/protocolVersion: 2026-07-28`) and transport headers (`MCP-Protocol-Version`, `Mcp-Method`) into outbound requests.
* **Flexible Authentication:** Built-in support for preemptive HTTP Basic Auth and Bearer token authentication over HTTPS.

---

## Installation & Requirements

* **Java 17+** (Uses standard `java.net.http.HttpClient`)
* Built internally using Jackson 3.x (can be shaded into your build)

```xml
<!-- Example Maven dependency once published/installed locally -->
<dependency>
    <groupId>io.github.ironluca</groupId>
    <artifactId>stupidmcp4j-client</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

---

## Quickstart & Usage

### 1. Initializing the Client

```java
import io.github.ironluca.stupidmcp4j.client.McpClient;

McpClient client = new McpClient(new URL("https://mcp.yourdomain.com/mcp"));

// Preemptive Bearer Authentication (Recommended for standard MCP HTTP)
client.setBearerAuth("my-secret-token".getBytes(StandardCharsets.UTF_8));

// Or Preemptive Basic Auth
// client.setBasicAuth("user".getBytes(StandardCharsets.UTF_8), "pass".getBytes(StandardCharsets.UTF_8));
```

---

### 2. Listing Available Tools

`toolsList()` auto-paginates until all available tools are drained. Each item in the returned list is a standalone, valid JSON string representing one tool schema:

```java
List<String> tools = client.toolsList();

for (String toolJson : tools) {
    // Send raw JSON directly to your LLM API or custom JSON parser
    System.out.println("Discovered Tool: " + toolJson);
}
```

---

### 3. Calling a Tool

`toolsCall` handles argument packaging, executes the JSON-RPC call, validates that `isError` is not set, and unwraps the text content blocks:

```java
import java.util.Map;

Map<String, Object> arguments = Map.of(
    "message", "Hello from the LLM agent"
);

try {
    List<String> responses = client.toolsCall("passthrough_tool", arguments);
    
    // Join text blocks for LLM prompt context
    String combinedOutput = String.join("\n", responses);
    System.out.println("Tool Output:\n" + combinedOutput);

} catch (RuntimeException e) {
    // Catches HTTP errors, JSON-RPC errors, or tool-level execution failures (isError: true)
    System.err.println("Execution failed: " + e.getMessage());
}
```

---

## Sticky Sessions & Load Balancers

While the MCP 2026-07-28 protocol is stateless, enterprise deployments often sit behind reverse proxies, API gateways, or load balancers (such as AWS Application Load Balancers, Cloudflare, F5 BIG-IP, or NGINX) that require sticky sessions to route traffic to the same backing instance or cache.

`stupidMCP4j-client` manages this transparently:

* **Automated Cookie Handling:** The underlying transport employs a shared `CookieManager` (`CookiePolicy.ACCEPT_ALL`) that automatically captures incoming affinity cookies (e.g., `AWSALB`, `BIGipServer`, `JSESSIONID`) and replays them on subsequent calls.
* **RFC 6265 Domain & Path Isolation:** Cookies are scoped strictly by the host domain and URI path. Different MCP endpoints maintain isolated cookie scopes inside the runtime store.
* **One-Client-Per-Endpoint Pattern:** The intended usage pattern is to maintain **one `MCPClient` instance per remote server endpoint**. A single shared `HttpClient` is reused across all client instances to leverage TCP connection pooling and keep-alive sockets without leaking credentials across distinct endpoints.

---

## Architecture

```
 Upstream Code / LLM SDK (OpenAI, LangChain4j, etc.)
                        │
                        │ List<String> / Map<String, Object>
                        ▼
            ┌───────────────────────┐
            │     McpClient API     │
            └───────────┬───────────┘
                        │ (Internal Jackson 3 AST & Serializer)
                        ▼
            ┌───────────────────────┐
            │   sendHttpRequest()   │
            │   - MCP-Protocol-Ver  │
            │   - Mcp-Method        │
            │   - _meta injections  │
            └───────────┬───────────┘
                        │ POST /mcp (application/json)
                        ▼
              [ Remote MCP Server ]
```

---

## Roadmap

* [ ] **Full Streamable HTTP (SSE):** Support reading `text/event-stream` line-by-line using a single-pass buffer to capture the terminal event payload.
* [ ] **Raw String Arguments Overload:** Add `toolsCall(String toolName, String rawJsonArguments)` to bypass Map conversions when LLMs yield pre-serialized JSON strings.
* [ ] **Discovery Probe:** Add helper for `server/discover` inspection.

---

## License

Apache License 2.0. See `LICENSE` for details.