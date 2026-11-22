# Retail ADK project architecture

This describes the current Java 17 / Maven implementation using Google ADK Java 1.13.0
and `gemini-3.8-flash`. No application code was changed for this analysis.

## Architecture diagram

```mermaid
flowchart TB
    U[User]

    subgraph SINGLE[Original single-agent mode]
        C1[AgentCliRunner] --> A1[RetailInventoryAgent]
    end

    subgraph MULTI[Phase 5A: model-directed delegation]
        C2[MultiAgentCliRunner] --> CO[RetailOperationsCoordinatorAgent]
        CO <--> IS[Inventory specialist]
        CO <--> DS[Discrepancy specialist]
        CO <--> RS[Replenishment specialist]
    end

    subgraph SEQUENTIAL[Phase 5B.1: ordered workflow]
        C3[SequentialWorkflowCliRunner] --> SQ[RetailInvestigationWorkflow / SequentialAgent]
        SQ --> W1[Inventory stage]
        W1 --> W2[Discrepancy stage]
        W2 --> W3[Replenishment stage]
        W3 --> WR[Deterministic report agent]
    end

    U --> C1
    U --> C2
    U --> C3

    A1 --> T[Existing FunctionTools]
    IS --> T
    DS --> T
    RS --> T
    W1 --> T
    W2 --> T
    W3 --> T
    T --> STORE[(RetailInventoryStore / InMemoryRetailInventoryStore)]

    G[Gemini: gemini-3.8-flash] -. model calls .-> A1
    G -. model calls .-> CO
    G -. model calls .-> IS
    G -. model calls .-> DS
    G -. model calls .-> RS
    G -. model calls .-> W1
    G -. model calls .-> W2
    G -. model calls .-> W3

    STATE[(Validated invocation state)]
    W1 -. results .-> STATE
    W2 -. results .-> STATE
    W3 -. results .-> STATE
    STATE -. report inputs .-> WR
```

Each CLI creates its own `InMemoryRunner` and in-memory session. All three modes use the
same shared-store definition; separate CLI processes have separate JVM memory and do not
share live inventory mutations with each other. Within an agent graph all tools receive the
same store instance. The store contains product catalog, warehouse stock, cycle-count records
and replenishment policies.

Cross-cutting runtime services omitted from the diagram for readability:

- `InMemoryRunner`: invokes the selected root and emits ADK events.
- In-memory session service: conversation events and callback state.
- `MultiAgentObservabilityCallbacks`: lifecycle auditing for Phase 5A and workflow agents.
- `ToolObservabilityCallbacks`: tool start, duration, success and failure auditing.
- `AgentAuditLogger`: correlated, sanitized logs and a bounded in-memory audit buffer.

| Tool | Single agent | Phase 5A owner | Phase 5B.1 owner |
| --- | --- | --- | --- |
| `lookup_inventory` | Yes | Inventory specialist | Inventory stage |
| `summarize_stock` | Yes | Inventory specialist | Not part of this workflow |
| `investigate_discrepancy` | Yes | Discrepancy specialist | Discrepancy stage |
| `calculate_replenishment` | Yes | Replenishment specialist | Replenishment stage |

## Sequential workflow flow diagram

```mermaid
flowchart TD
    START([User request]) --> CLEAR[Clear previous workflow state]
    CLEAR --> VALID{Exactly one SKU and warehouse?}
    VALID -- No --> ASK[Return clarification; no model or tool calls]
    ASK --> START
    VALID -- Yes --> REQ[Save SKU, warehouse and invocation ID]
    REQ --> INV[Inventory stage: lookup_inventory]
    INV --> IRES[Validate and save inventory result]
    IRES --> DISC[Discrepancy stage: investigate_discrepancy]
    DISC --> DRES[Validate and save discrepancy result]
    DRES --> REP[Replenishment stage: calculate_replenishment]
    REP --> RRES[Validate and save replenishment result]
    RRES --> REPORT[Read all validated results; render report without an LLM]
    REPORT --> DONE([Display consolidated report])

    INV -. failure .-> STOP[Audit failure; stop without consolidated report]
    DISC -. failure .-> STOP
    REP -. failure .-> STOP
    IRES -. malformed result .-> STOP
    DRES -. malformed result .-> STOP
    RRES -. malformed result .-> STOP
```

At each LLM stage:

1. Verify preceding state envelopes belong to the current request/invocation.
2. Supply the validated request and prior results to Gemini.
3. Restrict function calling to the designated tool.
4. Accept exactly one tool call with the exact requested identifiers; strip accompanying model text.
5. Execute the existing tool with audit callbacks.
6. Validate its response and persist an immutable structured result.
7. Finish with a local acknowledgement, without another Gemini call.

Normal domain outcomes such as `SKU_NOT_FOUND`, `NO_RECORDS_FOUND` and `POLICY_NOT_FOUND`
are saved and included as unavailable findings; they do not abort the remaining stages.
Unexpected failures, malformed responses, wrong arguments or missing/repeated calls abort.

## Phase 5A delegation flow

```mermaid
sequenceDiagram
    actor User
    participant CLI as MultiAgentCliRunner
    participant Coordinator
    participant Specialist
    participant Tool as Existing FunctionTool
    participant Store as Shared store
    User->>CLI: Domain request
    CLI->>Coordinator: Run in existing session
    Coordinator->>Specialist: transfer_to_agent
    Specialist->>Tool: Domain tool call
    Tool->>Store: Read inventory or audit/policy data
    Store-->>Tool: Recorded values
    Tool-->>Specialist: Structured response
    Specialist->>Coordinator: transfer_to_agent back to parent
    Note over Coordinator: Dispatch remaining portions if required
    Coordinator-->>CLI: Consolidated response
    CLI-->>User: Display response and delegation evidence
```

The Phase 5A path is model-directed; return transfers are instructed behavior, not a fixed
schedule. Its coordinator and specialists are independent of the Phase 5B.1 agents.

## Architectural assessment

- Clear separation: original, conversational multi-agent and ordered workflow entry points coexist.
- Reuse: domain tools, business calculations, store interface and auditing are shared.
- Reliable workflow boundaries: explicit IDs, invocation-bound state, guarded calls and deterministic reports.
- In-memory scope: inventory, sessions and audit history do not survive process restart.
- Model dependency: sequential order is fixed, but Gemini must still produce a valid guarded tool call.
- Consistency: reads across stages are not an atomic snapshot; changing available stock is disclosed.
- Safety: quantities come from tools, audit notes remain evidence, and replenishment is advisory only.
- Current scope: no ParallelAgent, LoopAgent, database, web API or deployment layer is implemented here.

Latest previously completed offline build: 106 discovered, 103 passed, 3 live tests skipped,
zero failures/errors. Tests were not rerun for this documentation-only analysis.
