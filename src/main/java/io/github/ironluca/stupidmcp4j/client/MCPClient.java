package io.github.ironluca.stupidmcp4j.client;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/*
 * Copyright 2026 The stupidMCP4j Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
public class MCPClient {

    private static final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private static final ObjectMapper mapper=new ObjectMapper();
    private static final HttpClient httpClient;

    static{

        httpClient=HttpClient.newBuilder()
                             .version(Version.HTTP_1_1)
                             .cookieHandler(cookieManager)
                             .followRedirects(Redirect.NORMAL)
                             .build();

    }//static block closing

    private URL serverEndpointURL;
    private Map<String, String> headers=new HashMap<>();

    public MCPClient(URL serverEndpointURL){this.serverEndpointURL=serverEndpointURL;}

    public void setBasicAuth(byte[] userName, byte[] pwd) {

        // Combine userName + ":" + pwd entirely at the byte level
        byte[] separator = ":".getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[userName.length + separator.length + pwd.length];

        ByteBuffer buffer = ByteBuffer.wrap(combined);
        buffer.put(userName);
        buffer.put(separator);
        buffer.put(pwd);

        try {

            // Base64 encode directly from the byte array
            headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(combined));

        } finally {

            // Overwrite the combined buffer immediately to protect credentials in memory
            Arrays.fill(combined, (byte) 0);

        }//finally closing

    }//setBasicAuth closing

    public void setBearerAuth(byte[] token) {

        // Specify standard charset explicitly
        headers.put("Authorization", "Bearer " + new String(token, StandardCharsets.UTF_8));

    }//setBearerAuth closing

    public void setHeader(String headerName, String headerValue){headers.put(headerName, headerValue);}
    public void removeHeader(String headerName){headers.remove(headerName);}

    //All Strings are JSON strings directly, not escaped JSON, like {"abc":"xyz"} and not {\"abc\":\"xyx\"}


    public List<String> toolsList() {

        List<String> toolsJSON = new ArrayList<>();
        Set<String> visitedCursor = new HashSet<>();
        int pageCount = 0;
        String cursor = null;

        do {

            try {

                // 1. Build standard JSON-RPC 2.0 tools/list request
                ObjectNode requestNode = mapper.createObjectNode();
                requestNode.put("jsonrpc", "2.0");
                requestNode.put("id", "list-tools-" + pageCount);
                requestNode.put("method", "tools/list");

                //Build the params
                ObjectNode params = mapper.createObjectNode();
                requestNode.set("params", params);

                // Build mandatory _meta in the params
                ObjectNode meta = mapper.createObjectNode();
                meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
                meta.set("io.modelcontextprotocol/clientCapabilities", mapper.createObjectNode());
                params.set("_meta", meta);                

                //Give the cursor, if there is one
                if (cursor != null && !cursor.isBlank()) {
                    
                    params.put("cursor", cursor);
                    requestNode.set("params", params);

                }//if closing

                // 2. Transmit request and fetch the raw response string (via SSE/HTTP)
                String requestJson = mapper.writeValueAsString(requestNode);
                String responseJson = sendHttpRequest(requestJson, Map.of("MCP-Protocol-Version", "2026-07-28", 
                                                                          "Mcp-Method", "tools/list"));

                if (responseJson == null || responseJson.isBlank()) {
                    break;
                }

                // 3. Parse JSON-RPC response envelope
                JsonNode root = mapper.readTree(responseJson);

                // Check for JSON-RPC error
                if (root.has("error")) {

                    JsonNode errorNode = root.get("error");
                    String errorMsg = errorNode.path("message").asString("Unknown error");
                    int errorCode = errorNode.path("code").asInt(-1);
                    throw new RuntimeException("MCP tools/list failed [code " + errorCode + "]: " + errorMsg);

                }//if closing

                JsonNode resultNode = root.get("result");
                if (resultNode == null || !resultNode.isObject()) {
                    break;
                }

                // 4. Extract tools array and append each tool as an independent JSON string
                JsonNode toolsArray = resultNode.get("tools");
                if (toolsArray != null && toolsArray.isArray()) {
                    for (JsonNode toolNode : toolsArray) {
                        // toString() produces the exact unescaped JSON object string
                        toolsJSON.add(toolNode.toString());
                    }
                }

                // 5. Extract nextCursor and handle loop boundaries
                JsonNode nextCursorNode = resultNode.get("nextCursor");
                cursor = (nextCursorNode != null && nextCursorNode.isString() && !nextCursorNode.asString().isBlank())
                        ? nextCursorNode.asString()
                        : null;

                // Cycle detection guard: abort if the server repeats a cursor
                if (cursor != null && !visitedCursor.add(cursor)) {
                    break;
                }

            } catch (Exception e) {
                // Wrap and bubble up or handle per your library's error contract
                throw new RuntimeException("Error executing tools/list at page " + pageCount + ": " + e.getMessage(), e);
            }

            pageCount++;

        } while (cursor != null && pageCount < 20);

        return toolsJSON;

    }//toolsList closing

    public List<String> toolsCall(String toolName, Map<String, Object> params) {

        if (toolName == null || toolName.isBlank())
            throw new IllegalArgumentException("toolName cannot be null or empty");

        try {

            // 1. Build standard JSON-RPC 2.0 tools/call request
            ObjectNode requestNode = mapper.createObjectNode();
            requestNode.put("jsonrpc", "2.0");
            requestNode.put("id", "call-tool-" + System.currentTimeMillis());
            requestNode.put("method", "tools/call");

            ObjectNode paramsNode = mapper.createObjectNode();

            // 2. Attach mandatory MCP 2026-07-28 _meta
            ObjectNode meta = mapper.createObjectNode();
            meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
            meta.set("io.modelcontextprotocol/clientCapabilities", mapper.createObjectNode());
            paramsNode.set("_meta", meta);

            // 3. Attach tool name and arguments
            paramsNode.put("name", toolName);
            if (params != null && !params.isEmpty()) {
                paramsNode.set("arguments", mapper.valueToTree(params));
            } else {
                paramsNode.set("arguments", mapper.createObjectNode());
            }

            requestNode.set("params", paramsNode);

            // 4. Send request via HTTP
            String requestJson = mapper.writeValueAsString(requestNode);
            String responseJson = sendHttpRequest(requestJson, Map.of("MCP-Protocol-Version", "2026-07-28", 
                                                                      "Mcp-Method", "tools/call", 
                                                                      "Mcp-Name", toolName));

            if (responseJson == null || responseJson.isBlank()) {
                throw new RuntimeException("Empty response received from MCP server for tools/call: " + toolName);
            }

            // 5. Parse JSON-RPC response
            JsonNode root = mapper.readTree(responseJson);

            // Check for JSON-RPC level error
            if (root.has("error")) {
                JsonNode errorNode = root.get("error");
                String errorMsg = errorNode.path("message").asString("Unknown error");
                int errorCode = errorNode.path("code").asInt(-1);
                throw new RuntimeException("MCP tools/call failed [code " + errorCode + "]: " + errorMsg);
            }

            JsonNode resultNode = root.get("result");
            if (resultNode == null || !resultNode.isObject())
                throw new RuntimeException("Invalid response envelope: missing 'result' object");


            // 6. Handle input_required guard (for non-compliant servers attempting MRTR)
            String resultType = resultNode.path("resultType").asString("");
            if ("input_required".equalsIgnoreCase(resultType)) {
                throw new UnsupportedOperationException(
                    "Server requested interactive input ('input_required'), but this client does not support multi-round-trip flows."
                );
            }

            // 7. Extract text content blocks
            List<String> outputTextList = new ArrayList<>();
            JsonNode contentArray = resultNode.get("content");

            if (contentArray != null && contentArray.isArray()) {

                for (JsonNode item : contentArray) {

                    // Filter specifically for "text" content types, we don't support any other type currently
                    if ("text".equalsIgnoreCase(item.path("type").asString()))
                        outputTextList.add(item.path("text").asString(""));

                }//for closing

            }//if closing

            // 8. Handle tool-level execution errors (isError == true)
            if (resultNode.path("isError").asBoolean(false)) {

                String errorDetails = outputTextList.isEmpty() 
                        ? "Tool reported execution failure without message" 
                        : String.join("\n", outputTextList);
                throw new RuntimeException("Tool execution error for '" + toolName + "': " + errorDetails);

            }//if closing

            return outputTextList;

        } catch (Exception e) {

            if (e instanceof RuntimeException runtimeException)throw runtimeException;
            throw new RuntimeException("Execution failed for tool '" + toolName + "': " + e.getMessage(), e);

        }//catch closing

    }//toolsCall closing

    public List<String> toolsCall(String toolName, String jsonStringParams) {
        if (jsonStringParams == null || jsonStringParams.isBlank()) {
            return toolsCall(toolName, Map.of());
        }

        try {

            Map<String, Object> params = mapper.readValue(
                jsonStringParams, 
                new TypeReference<Map<String, Object>>() {}
            );
            
            return toolsCall(toolName, params);

        } catch (Exception e) {
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("Failed to parse JSON parameters for tool '" + toolName + "': " + e.getMessage(), e);
        }
    }

    //============================ Internal Methods =========================================//

    /**
     * Sends a plain synchronous HTTP POST request to the server and returns 
     * the response body as a JSON string.
     */
    private String sendHttpRequest(String requestJson, Map<String, String> additionalHeaders) throws Exception {

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                   .uri(this.serverEndpointURL.toURI())
                   .timeout(Duration.ofSeconds(30))
                   .header("Content-Type", "application/json")
                   .header("Accept", "application/json, text/event-stream")
                   .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));

        // Inject custom headers and auth (e.g., Authorization: Basic/Bearer)
        this.headers.forEach(builder::header);
        if(additionalHeaders!=null && !additionalHeaders.isEmpty())additionalHeaders.forEach(builder::header);

        HttpRequest request = builder.build();

        HttpResponse<String> response = httpClient.send(
                request, 
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        int statusCode = response.statusCode();

        // Check for HTTP-level failure (4xx, 5xx)
        if (statusCode < 200 || statusCode >= 300) {

            throw new RuntimeException("HTTP request failed with status " + statusCode + ": " + response.body());

        }//if closing

        //TODO - process content type application/json and text/event-stream differently

        return response.body();

    }//sendHttpRequest closing 

    public static void main(String[] args) throws Exception{

        //MCPClient client=new MCPClient(new URL("http://localhost:8008/mcp"));
        MCPClient client=new MCPClient(new URL("https://mcp.context7.com/mcp"));

        /*
        List<String> tools=client.toolsList();
        System.out.println(tools);
        */

        System.out.println(client.toolsCall("resolve-library-id", Map.of("query", "technical document", "libraryName", "/org/test")));
        
    }//main closing

}//class closing
