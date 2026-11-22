# Security overlay for the existing retail inventory MCP server

These are the exact three additive files installed and tested in:

`C:\agent-AI-workspace\retail-stock-investigator\retail-inventory-mcp`

They are kept in the ADK checkout for review/version control because the supplied server directory is not a Git repository. Existing server business logic, Spring dependencies, application properties, legacy optional API-key filter and three business tests were preserved.

`DelegationVerifier` verifies the ADK local-profile HMAC signature, fixed audience and expiry. `SecureMcpDelegationFilter` applies before Spring MCP transport dispatch, requires a key at startup, and independently checks the configured single tenant/user, allowed tool, signed request UUID, argument keys, product IDs and date range. It rejects browser Origin requests and supports the existing default `/mcp` Streamable HTTP endpoint. Authentication is required for all MCP transport methods. This local profile is not OAuth and does not turn the static demo inventory into a multi-tenant store.

The server requires Java 21+ (validation used installed JDK 25 targeting Java 21); the ADK client remains Java 17. Provide a dedicated Base64-encoded >=32-byte key privately using `MCP_DELEGATION_KEY` (or `app.mcp.delegation-key`) in the server process. Client and server keys must match. Default audience is `local-stock`, tenant `retail-demo`, user `analyst`; `app.mcp.allowed-tools` defaults to the three reviewed read-only operations. Missing/invalid keys fail startup. Keep the legacy `app.mcp.api-key` setting unset for the delegation-only profile, or deliberately implement both requirements.

The existing process on port 8080 was not restarted. Source changes do not alter its already-loaded filters. Restart it with the private key before treating it as secured. No permanent key was created or saved by this integration.

From the ADK project in PowerShell 7:

```powershell
.\scripts\Run-SpringMcpIntegrationTests.ps1 -Gemini
```

This helper starts a separate secured instance on loopback port 18080 with a random ephemeral key, invokes actual Spring tools through ADK and optionally Gemini, then stops it. It does not propagate Gemini or database credentials into the server process. It preserves/restores the caller environment and does not touch port 8080.

Validation: server tests 9 passed; real Spring integration 4 passed including actual Gemini execution of all three product tools; client offline Maven suite 210 passed / 102 gated skips. See [the Phase 6.6A document](../../docs/phase-6-6a-secure-mcp-integration.md) for architecture, commands and limitations.

For another server checkout, inspect its source first and merge these files deliberately. Do not overwrite existing security files. Production deployment still needs managed identity/ACLs, short-lived credential rotation/revocation, TLS, rate limits, audit operations and a reviewed tenant data model. No purchase-order actions or persistent side effects are introduced by these three product tools.
