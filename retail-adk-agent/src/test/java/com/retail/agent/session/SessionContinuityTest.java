package com.retail.agent.session;

import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.retail.agent.RetailInventoryAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Google ADK Session Management & Multi-Turn Continuity Tests")
class SessionContinuityTest {

    private InMemoryRunner runner;
    private BaseSessionService sessionService;

    @BeforeEach
    void setUp() {
        runner = new InMemoryRunner(RetailInventoryAgent.ROOT_AGENT);
        sessionService = runner.sessionService();
    }

    @Test
    @DisplayName("Should successfully create, persist and retrieve multi-turn session state")
    void shouldCreateAndRetrieveSession() {
        Session session = sessionService
                .createSession(runner.appName(), "retail-test-user")
                .blockingGet();

        assertThat(session).isNotNull();
        assertThat(session.id()).isNotBlank();
        assertThat(session.userId()).isEqualTo("retail-test-user");
        assertThat(session.appName()).isEqualTo(runner.appName());

        // Verify retrieval from session service
        Session retrieved = sessionService
                .getSession(runner.appName(), session.userId(), session.id(), Optional.empty())
                .blockingGet();

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.id()).isEqualTo(session.id());
    }

    @Test
    @DisplayName("Should preserve multi-turn event history across sequential user interactions")
    void shouldMaintainEventHistoryAcrossTurns() {
        Session session = sessionService
                .createSession(runner.appName(), "multi-turn-eval-user")
                .blockingGet();

        // Turn 1: User queries product availability
        Event turn1User = Event.builder()
                .content(Content.fromParts(Part.fromText("What is the stock for SKU-ELEC-1001 at WH-CENTRAL-01?")))
                .author("user")
                .build();
        session.addEvent(turn1User);

        // Turn 1: Agent responds with stock facts
        Event turn1Agent = Event.builder()
                .content(Content.fromParts(Part.fromText("Available stock for SKU-ELEC-1001 at WH-CENTRAL-01 is 115 units.")))
                .author("retail_inventory_agent")
                .build();
        session.addEvent(turn1Agent);

        // Turn 2: User asks follow-up referencing prior turn
        Event turn2User = Event.builder()
                .content(Content.fromParts(Part.fromText("Is replenishment needed for that TV?")))
                .author("user")
                .build();
        session.addEvent(turn2User);

        // Verify that session retains all 3 multi-turn events chronologically
        List<Event> history = session.events();
        assertThat(history).hasSize(3);
        assertThat(history.get(0).stringifyContent()).contains("SKU-ELEC-1001");
        assertThat(history.get(1).stringifyContent()).contains("115 units");
        assertThat(history.get(2).stringifyContent()).contains("replenishment needed");
    }

    @Test
    @DisplayName("Should preserve contextual state across session turns")
    void shouldPreserveContextualStateVariables() {
        Map<String, Object> initialState = Map.of(
                "activeWarehouseId", "WH-WEST-03",
                "lastInvestigatedSku", "SKU-APPR-2002"
        );

        Session session = sessionService
                .createSession(runner.appName(), "stateful-eval-user", initialState, "test-sess-001")
                .blockingGet();

        // Verify state is preserved and retrievable through session service
        Session retrieved = sessionService
                .getSession(runner.appName(), session.userId(), session.id(), Optional.empty())
                .blockingGet();

        assertThat(retrieved.state()).containsEntry("activeWarehouseId", "WH-WEST-03");
        assertThat(retrieved.state()).containsEntry("lastInvestigatedSku", "SKU-APPR-2002");
    }
}
