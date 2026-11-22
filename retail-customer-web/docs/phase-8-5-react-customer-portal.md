# Phase 8.5 — React customer portal

The portal is a standalone local Vite application for the Phase 8.1 customer APIs, Phase 8.4A session context, and Phase 8.5B chat API. Use synthetic `.test` addresses only.

## Architecture

React Router owns the page routes. React Hook Form and Zod handle forms, and Zod validates JSON responses at the API boundary. TanStack Query manages server reads and gates the private pages on the latest session-context response. Tailwind CSS and the portal stylesheet provide the responsive layout.

```text
BrowserRouter
└── QueryClientProvider / SessionProvider
    └── PortalLayout
        ├── Session indicator and End session action
        ├── Route navigation
        └── Route content
            ├── RegisterPage
            ├── SelectPage
            ├── ProfilePage → protected profile query → editor
            ├── PreferencesPage → protected preferences query → editor
            └── ChatPage → session-scoped Conversation → AssistantReply
```

| Route | Purpose |
|---|---|
| `/register` | Register a synthetic customer and hand its returned ID to the selection form |
| `/select` | Select a registered customer or switch the current session |
| `/profile` | Read and edit the selected customer's given and family names |
| `/preferences` | Manage language, marketing opt-in, and personalization consent |
| `/assistant` | Ask independent questions about registration, products, and demo inventory |

The root and unknown routes lead to registration. Protected routes show a selection prompt when there is no active session.

## API integration

All requests are relative to the portal origin. Vite forwards the permitted paths to Spring on loopback. The backend response determines the current customer.

| Method and path | Portal use |
|---|---|
| `POST /api/v1/customers` | Send `givenName`, `familyName`, and `email`. A `201` becomes a selection candidate. A `409` becomes an email-field error. |
| `POST /api/v1/demo/session` | Send the explicitly entered candidate UUID. The server creates or rotates the demo cookie. |
| `GET /api/v1/demo/session/context` | Resolve `customerId`, `selectionId`, consent, and consent epoch from the cookie. `403` clears the active session view. |
| `GET /api/v1/customers/{customerId}` | Read the server-selected profile. |
| `PATCH /api/v1/customers/{customerId}` | Update the names. Email remains read-only. |
| `GET /api/v1/customers/{customerId}/preferences` | Read the saved language and both boolean preferences. |
| `PUT /api/v1/customers/{customerId}/preferences` | Save all three fields. Enable consent by switching it on and saving. Withdraw it by switching it off and saving. |
| `DELETE /api/v1/demo/session` | Invalidate the server session and clear the portal view. |

## Session and consent

HTTP requests include credentials. The browser manages the `RETAIL_DEMO_SESSION` cookie. The application stores neither cookies nor customer IDs in browser storage. Registration and selection inputs are temporary form state. For profile and preference URLs, the ID comes from the current server session. Spring independently checks that the cookie's selection matches the path.

Private queries are keyed by `selectionId`, including when the same customer is selected again. A switch or logout removes previous private queries and unmounts the forms. In-flight reads are canceled as their query is removed, and writes may only update the UI while their original selection is current. A same-origin broadcast clears other portal tabs and re-resolves the session. Focus checks and a 15-second active-tab poll also reconcile changes from outside the portal. Failed mutations show an actionable error. Failed session changes re-read server context.

The consent indicator displays the saved server value. A new customer starts with consent disabled. Switching the control edits the form. Saving commits the change and refreshes session context.

## Local development

Use Node.js 22.12 or later, PowerShell 7 for the disposable fixture, and Java 25 for the customer service. Configure Spring's database environment variables using the [Phase 8.1 instructions](../../retail-customer-service/docs/phase-8-1-customer-registration-poc.md). The Spring build must include the Phase 8.4A session-context endpoint.

```powershell
# Terminal 1, after setting RETAIL_DB_URL, RETAIL_DB_USER, and RETAIL_DB_PASSWORD
Set-Location C:\agent-AI-workspace\retail-customer-service
$env:SPRING_PROFILES_ACTIVE = 'poc'
mvn spring-boot:run

# Terminal 2
Set-Location C:\agent-AI-workspace\retail-customer-web
npm ci
npm run dev
```

Open `http://127.0.0.1:5173`. Use the literal loopback host consistently. The portal uses Spring's port 8081 by default. Set `RETAIL_CUSTOMER_API_PORT` in the portal process to target another loopback port. The portal's `RETAIL_PORTAL_PORT` setting changes both the listener and the allowed origin.

## Verification commands

```powershell
Set-Location C:\agent-AI-workspace\retail-customer-web
npm run build
npm test
npx playwright install chromium

# With the POC Spring service already running on port 8081
npm run test:e2e

# Or use a disposable PostgreSQL cluster and the existing packaged customer-service JAR
.\scripts\Test-AgainstCustomerService.ps1
```

The fixture reads the existing customer-service JAR, starts Spring and PostgreSQL on loopback, runs Chromium through the Vite proxy, and stops the fixture processes. It uses synthetic registrations in a disposable database. Script parameters allow alternate JDK, PostgreSQL, and JAR paths.

| Verification on November 20, 2026 | Result |
|---|---|
| TypeScript and Vite production build | Passed |
| Vitest and React Testing Library | 8 passed, zero failures or skips |
| Chromium with the real Spring Boot service and PostgreSQL | 6 passed, zero failures or skips |
| Layout inspection | Registration page reviewed at 1440 × 900 and 390 × 844 |

The browser suite exercises synthetic registration, duplicate email, cookie-backed reloads, saved profile changes, consent grant and withdrawal, cross-tab switching and invalidation, CSRF and CORS checks, failed-request retry, and narrow-viewport navigation.

## POC security limitations

The Vite dev and preview proxy bind to the literal loopback address, reject a different Host or Origin on API requests, and disable Vite CORS. Writes require the same Origin, a portal-specific non-simple request header, and JSON where a payload applies. The API route and HTTP method are also allowlisted. Spring remains closed to cross-origin browser access and needs no browser-integration changes. Chromium verification checks the `HttpOnly`, `SameSite=Strict` cookie, rejected foreign-origin and unmarked writes, and the absence of a Spring CORS allow header on a foreign preflight.

These controls cover the local browser setup. Cookies are scoped to a host across ports and loopback HTTP uses the existing development cookie settings. A native local process can call the loopback service directly. A person who knows a synthetic UUID can choose it through the existing demo selection API. Treat the session as a synthetic POC selection mechanism. Keep personal data out of the portal.


## AI Assistant (Phase 8.5C)

The Assistant uses the existing portal navigation and credentialed API client. The browser calls Vite at its own origin, Vite forwards the allowlisted request to Spring, and Spring calls the loopback ADK adapter. Spring owns the authenticated bridge request. ADK and the inventory MCP server have no browser-facing route.

| Method and path | Contract |
|---|---|
| `GET /api/v1/chat/csrf` | Read `{ csrfToken }` with `X-Retail-Portal: 1` and the current HttpOnly demo cookie. The token is held only for the current attempt. |
| `POST /api/v1/chat/ask` | Send only `{ message }` as JSON, with `X-Retail-Portal: 1` and `X-Retail-CSRF`. The message must be nonblank and at most 1,200 UTF-16 code units. The browser supplies the same-origin request origin and cookie. |

The two exact methods and paths extend the existing proxy allowlist. The ask route has a 60-second proxy timeout so Spring's default 50-second deadline can return its structured error. Other routes retain their five-second proxy timeout. The proxy also requires the CSRF header on ask. Spring validates its value against the current selection. Keep the portal origin configured in Spring identical to the Vite origin.

The client validates `answer`, `citations`, `productSkus`, `inventoryObservations`, `personalized`, `status`, and `error`. It displays the answer as plain text. The citation list preserves document markers and channels. Catalog references show canonical SKUs. Inventory observations list the result and tool status along with any source, request ID and observation timestamp. A reported `actualInventory` is labeled a product-level inventory count. Absent counts stay absent, and `WAREHOUSE_UNSUPPORTED` displays an explicit limitation. `OK`, `PARTIAL`, and `INSUFFICIENT` all render as evidence responses with distinct execution labels.

For errors, the client maps Spring's public codes to fixed text without rendering upstream diagnostics. `SESSION_REQUIRED` clears the active view. `CSRF_REJECTED` and `SESSION_CHANGED` trigger session reconciliation. A retry re-reads CSRF. Timeout and unavailability have distinct messages for structured Spring errors.

### Customer lifecycle and single-turn behavior

The chat component is keyed by server selection and consent epoch. A change, logout, or expiry clears the draft, messages, and errors and aborts in-flight browser work. Browser context is checked after fetching CSRF and before displaying a response. Spring and ADK independently revalidate session and consent. The existing cross-tab session updates, focus checks, and 15-second active poll also refresh the portal. The consent label reflects the saved value. Its preferences link takes the user to the existing consent form.

Messages live in component state. Clearing the conversation, leaving the route, or reloading discards them. No transcript is stored in browser storage or PostgreSQL. Each request contains one question. Include relevant product names, SKUs, and details in every question because conversational follow-up understanding is unsupported.

### Local startup and verification

Use PowerShell 7. Prepare the customer database and existing ADK document, memory, and optional MCP settings using the [Phase 8.5B setup](../../retail-adk-agent/docs/phase-8-5-chat-api-integration.md). Generate the bridge token and share it privately between the Spring and ADK process environments.

```powershell
# Spring terminal with RETAIL_DB_* and RETAIL_CHAT_BRIDGE_TOKEN already set
Set-Location C:\agent-AI-workspace\retail-customer-service
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.5.0.3'
$env:SPRING_PROFILES_ACTIVE = 'poc'
$env:RETAIL_CHAT_ENABLED = 'true'
$env:RETAIL_CHAT_ADK_URL = 'http://127.0.0.1:8091'
$env:RETAIL_CHAT_PORTAL_ORIGIN = 'http://127.0.0.1:5173'
mvn spring-boot:run

# ADK terminal with the same RETAIL_CHAT_BRIDGE_TOKEN and existing dependencies configured
Set-Location C:\agent-AI-workspace\retail-adk-agent
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:RETAIL_CUSTOMER_CONTEXT_URL = 'http://127.0.0.1:8081'
$env:RETAIL_CHAT_ADK_PORT = '8091'
mvn -B -ntp compile exec:java '-Dexec.mainClass=com.retail.agent.context.RetailChatHttpServer'

# Portal terminal
Set-Location C:\agent-AI-workspace\retail-customer-web
npm ci
npm run dev
```

Open `http://127.0.0.1:5173`, register or select a synthetic account, and open **AI Assistant**. Ask “How do I register? Tell me about KB-201 and check its availability.” With published product evidence and secured inventory configured, confirm that document citations, the catalog SKU, and inventory provenance appear separately. Ask about availability at a particular warehouse and confirm that warehouse-specific availability is unsupported. End the demo session and confirm that returning to the Assistant shows the customer selection prompt.

```powershell
Set-Location C:\agent-AI-workspace\retail-customer-web
npm run build
npm test
npx playwright install chromium
.\scripts\Test-AgainstCustomerService.ps1

# Stop any Spring service occupying port 8081 before using the disposable fixture.
# Keep ADK running with its customer-context URL set to http://127.0.0.1:8081.
# In a shell with the matching RETAIL_CHAT_BRIDGE_TOKEN, run:
.\scripts\Test-AgainstCustomerService.ps1 -CustomerPort 8081 -ChatAdkUrl 'http://127.0.0.1:8091'
```

The gated browser test requires ADK to return published registration and product evidence and a reported KB-201 inventory observation. The script starts real Spring with disposable PostgreSQL and sets its portal origin to the Vite test port. The default script invocation skips the ADK-dependent case.

| Phase 8.5C verification | Passed | Failed | Skipped |
|---|---:|---:|---:|
| TypeScript and Vite production build | 1 | 0 | 0 |
| Vitest component suite | 18 | 0 | 0 |
| Chromium with real Spring Boot, PostgreSQL 18.7 and the ADK browser gate enabled | 9 | 0 | 0 |

Vitest covers rendering, blank submission, request shape, loading and send locking, evidence, warehouse limitations, retry after a CSRF failure, timeout and backend unavailability, expiry, delayed responses after switching or withdrawing consent, and clearing while a request is active. Chromium covers the existing portal regression, a 320-pixel keyboard flow, the exact proxy rules, Spring's token validation, and a combined registration, product, and inventory question through the actual ADK HTTP adapter and orchestrator. The ADK run used the existing deterministic document, generation, memory, and inventory fixtures. Live Gemini and a live inventory MCP server were outside this browser run.
