# Phase 5B.1: Sequential retail investigation

Implemented on `phase-5b-workflow-orchestration`, starting from Phase 5A commit `88b0864`.
No existing source, dependency, model, tool, store, callback, CLI, or test was modified.

## Architecture

`RetailInvestigationWorkflow.create(store)` builds a new ADK Java 1.13.0 `SequentialAgent`
with four children, in this order:

1. `investigation_inventory`: existing `InventoryLookupTool.asFunctionTool()`.
2. `investigation_discrepancy`: existing `DiscrepancyInvestigationTool.asFunctionTool()`.
3. `investigation_replenishment`: existing `ReplenishmentCalculatorTool.asFunctionTool()`.
4. `investigation_report`: a deterministic `BaseAgent` renderer, with no model call.

The first three are fresh `LlmAgent` instances using `RetailInventoryAgent.MODEL_NAME`
(`gemini-3.8-flash`). All tools receive the same supplied store. The CLI supplies
`RetailInventoryAgent.SHARED_STORE`. Phase 5A agents are not reparented or given workflow
instructions: their delegation contract is different and they retain their existing hierarchy.

Stage order is controlled by `SequentialAgent`, not a model-generated transfer. Parent/peer
transfers are disabled on the workflow LLM stages. `beforeModel` requests Gemini function-calling
mode `ANY`, restricted to that stage's single tool. `afterModel` checks exactly one designated
function call and exact arguments before execution; accompanying model text is removed.
After successful execution, `beforeModel` supplies a local neutral completion response rather
than making a second Gemini request. Thus a successful investigation uses three Gemini requests.

The model still has to produce a valid tool call. If it does not, the workflow stops rather than
guessing identifiers, retrying indefinitely, or publishing an ungrounded report.

## Explicit state contract

The root callback parses exactly one `SKU-...` identifier and one `WH-...` identifier from the
current request. Matching is case-insensitive and identifiers are normalized to uppercase.
Absent or ambiguous identifiers cause a clarification response before any child, model,
or business tool executes. A new request must provide both IDs; implicit references to previous
investigations are deliberately not resolved. After clarification, submit the complete pair.

All state uses the invocation-scoped `temp:retail_investigation:` namespace:

| Suffix | Value |
| --- | --- |
| `request` | `{sku, warehouseId, invocationId}` |
| `inventory` | `{request, toolName, arguments, result}` |
| `discrepancy` | `{request, toolName, arguments, result}` |
| `replenishment` | `{request, toolName, arguments, result}` |
| `report` | Deterministically rendered consolidated text |

`afterTool` captures the actual tool response, validates identity, status and required field types,
and stores an immutable JSON-compatible snapshot via `ToolContext.state()`. No model-authored
text is accepted as a result. Each later stage verifies all preceding envelopes belong to the
current request and invocation. Prior structured results are also injected into its model request.
The report consumes these maps directly. Natural-language conversation history is not the state
contract. Initialization clears this namespace on every request, including invalid requests.

Known domain outcomes (`SKU_NOT_FOUND`, `NO_RECORDS_FOUND`, `POLICY_NOT_FOUND`, etc.) are
valid results; remaining stages still execute for the same pair. Unavailable sections do not
present the tools' error-path zero placeholders as measured quantities or decisions. Unexpected
results and execution failures stop the sequence without a consolidated report.

Inventory numbers, audit records, timestamps, notes and replenishment decisions are rendered
from validated tool values. Notes are labeled as recorded evidence. No root cause is inferred.
Every report includes the existing advisory-only purchase-order disclaimer.

Existing agent lifecycle and tool observability callbacks provide session/invocation correlation,
start/end events, timing and result status. The workflow also routes malformed/error tool
responses through the existing error callback.

## Run in PowerShell

Use the already configured Gemini credential; do not put it on the command line.

```powershell
Set-Location 'C:\agent-AI-workspace\retail-adk-agent'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
mvn compile exec:java '-Dexec.mainClass=com.retail.agent.workflow.SequentialWorkflowCliRunner'
```

Example inputs:

| Input | Expected behavior |
| --- | --- |
| `Investigate SKU-ELEC-1001 at WH-CENTRAL-01` | `lookup_inventory` -> `investigate_discrepancy` -> `calculate_replenishment` -> consolidated report |
| `Investigate SKU-ELEC-1002 at WH-EAST-02` | Same tool order; every call uses this new pair |
| `Investigate SKU-ELEC-1001` | Clarification; no model or tool calls |
| `Investigate SKU-MISSING-999 at WH-CENTRAL-01` | Three tool calls; report records not-found outcomes, not invented stock |
| `quit` | Exit |

The CLI prints actual tool call authors/arguments, response statuses and the consolidated report.
It suppresses intermediate stage acknowledgements and avoids echoing SDK exception details.
It limits LLM calls to six and applies a 90-second inactivity timeout to the event stream.

## Validation

```powershell
mvn clean test
```

`RetailInvestigationWorkflowTest` uses the production workflow factory with a package-private
scripted model transport seam. It covers topology and model preservation, real ADK tool execution
order, exact identifiers, shared store mutations, state envelopes, preceding-state injection,
audit correlation, report values and evidence, missing/ambiguous inputs, wrong tool arguments,
missing/multiple calls, fabricated model text, missing data, changing stock, turn isolation,
execution failure after a successful run, and CLI clarification/report output.

The optional Gemini test remains disabled unless explicitly enabled:

```powershell
mvn test '-Dtest=SequentialWorkflowLiveSmokeTest' '-DrunLiveSequentialWorkflowTest=true'
```

Normal offline tests do not need credentials. The two existing live gates are unchanged.

Validated with `mvn clean test`: 106 discovered, 103 passed, 3 live tests skipped, zero failures/errors.
The 14 new offline tests passed. No live Gemini request was made for this implementation validation.

## ADK 1.13.0 findings and limitations

- The installed JAR exposes `SequentialAgent.builder()` and the inherited `subAgents(...)`,
  `beforeAgentCallback(...)`, and `afterAgentCallback(...)` APIs. Its normal execution uses
  `Flowable.concatMap` over the child list.
- The SequentialAgent builder accepts asynchronous lifecycle callbacks; adapters wrap the existing
  synchronous lifecycle loggers. LlmAgent separately supplies synchronous callback helpers.
- SequentialAgent guarantees child ordering, not correct tool selection or a typed data contract.
  Explicit guards and validated state envelopes supply those guarantees for successful runs.
- LlmAgent `outputKey` stores model output text; it is not used for authoritative tool results.
- FunctionTool can catch a Java business exception and return `{status: "error", message: ...}`,
  bypassing the framework's normal tool-error path. The workflow detects and audits this, then stops.
- Temporary state is per invocation; durable resumption and concurrent requests in the same session
  are outside this phase. Use one active investigation per session.
- Store reads occur at different times. They are not a transaction or atomic snapshot. The report
  warns if available stock differs between inventory and replenishment stages.
- The report supports ordinary text `runAsync`, not bidirectional audio/video `runLive`.
- A standalone Maven `exec:java` CLI must close the runner and stop RxJava schedulers on exit;
  otherwise the plugin warns about lingering worker threads. This cleanup is confined to `main`.
- Gemini availability, authentication and tool-call compliance remain runtime dependencies.
  The offline suite proves contracts and deterministic rendering, not live model reliability.

ParallelAgent, LoopAgent, external integrations, automatic commits and deployment are not part of this phase.
