package com.retail.agent.context;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class MemoryRepositoryProviderTest extends ContextFixture {
    @Test void defaultSelectionDoesNotReadDatabaseCredentialsOrOpenConnections() {
        try(var handle=MemoryRepositoryProvider.open(key->{
            assertThat(key).isEqualTo("MEMORY_REPOSITORY");return null;
        },clock,policy,audit)) {assertThat(handle.repository()).isInstanceOf(InMemoryMemoryRepository.class);}
    }
    @Test void unsupportedSelectionAndMissingConfigurationFailClosed() {
        assertThatThrownBy(()->MemoryRepositoryProvider.open(key->"unsupported",clock,policy,audit)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->MemoryRepositoryProvider.open(Map.of("MEMORY_REPOSITORY","postgres")::get,clock,policy,audit))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void embeddedCredentialsAndInvalidPoolBoundsAreRejectedWithoutConnecting() {
        assertThatThrownBy(()->MemoryRepositoryProvider.pool(Map.of("MEMORY_POSTGRES_URL","jdbc:postgresql://localhost/test?password=do-not-log")::get,false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("do-not-log");
        assertThatThrownBy(()->MemoryRepositoryProvider.pool(Map.of("MEMORY_POSTGRES_URL","jdbc:postgresql://localhost/test",
                "MEMORY_POSTGRES_USER","test","MEMORY_POSTGRES_PASSWORD","not-a-real-password","MEMORY_POSTGRES_POOL_SIZE","0")::get,false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
