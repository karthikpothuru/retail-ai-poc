# Retail Inventory MCP Server

A learning project that exposes retail inventory capabilities as MCP tools using Spring Boot and Spring AI.

## Technology

- Java 21
- Spring Boot 4.0.3
- Spring AI 2.0.2
- Spring AI MCP Server WebMVC starter
- MCP Streamable HTTP transport

## Exposed MCP tools

| Tool | Purpose |
|---|---|
| `get_inventory` | Retrieve expected stock, actual stock, product name and reorder threshold |
| `get_sales_summary` | Retrieve sample sales quantity for a product and date range |
| `get_reorder_status` | Determine whether available stock is at or below the reorder threshold |

The application uses in-memory demonstration data. The sales quantity is intentionally sample data and is not filtered by the requested dates. No tool changes inventory or places orders.

## Sample products

| Product ID | Product | Expected | Actual | Reorder threshold | Sample sales |
|---|---|---:|---:|---:|---:|
| WM-101 | Wireless Mouse | 120 | 105 | 30 | 15 |
| KB-201 | Mechanical Keyboard | 80 | 15 | 20 | 12 |
| MN-301 | Computer Monitor | 60 | 55 | 15 | 5 |

## Prerequisites on Windows

1. Install JDK 21 and Maven 3.9 or later.
2. Verify:
   ```powershell
   java -version
   mvn -version
   ```

## Build and run

Open PowerShell in the extracted project folder:

```powershell
mvn clean test
mvn spring-boot:run
```

The server listens on port `8080`. The Streamable HTTP MCP endpoint is:

`http://localhost:8080/mcp`

## Test with MCP Inspector

In a second PowerShell window:

```powershell
npx -y @modelcontextprotocol/inspector
```

Open the local Inspector URL printed in the terminal.

1. Choose **Streamable HTTP** transport.
2. Set the server URL to `http://localhost:8080/mcp`.
3. Connect.
4. Open **Tools** and confirm that the three tools are listed.
5. Invoke the tools using the example inputs below.

### Tool: get_inventory

```json
{
  "productId": "WM-101"
}
```

Expected values include `actualInventory: 105` and `reorderThreshold: 30`.

### Tool: get_sales_summary

```json
{
  "productId": "WM-101",
  "fromDate": "2026-10-01",
  "toDate": "2026-10-31"
}
```

Expected `quantitySold` is `15`. The response includes a note that this is demonstration data and is not date-filtered.

### Tool: get_reorder_status

```json
{
  "productId": "KB-201"
}
```

Expected `reorderRequired` is `true`, because stock is 15 and the threshold is 20.

## Architecture

```text
Google Managed Agent (later integration)
              |
              | MCP / Streamable HTTP
              v
Spring Boot + Spring AI MCP Server
              |
              +-- get_inventory
              +-- get_sales_summary
              +-- get_reorder_status
              |
              v
       In-memory sample data
```

## Important security note

The HTTP MCP endpoint is unauthenticated by default. Keep this learning server bound to a trusted local development environment. Do not expose it to the public internet or connect it to production systems until authentication, authorization, network controls, input validation, and audit logging are implemented.

## Next learning milestone

After confirming tool discovery and invocation locally, the next step is to deploy the MCP server behind HTTPS with authentication and then register its Streamable HTTP endpoint with the Google Managed Agent.
