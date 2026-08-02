package io.github.devcircle.user.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventEnvelope;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.ValidatedEvent;
import io.github.devcircle.user.infrastructure.UserGraphRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class UserRegisteredConsumerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final UserGraphRepository repository = mock(UserGraphRepository.class);
    private final UserRegisteredConsumer consumer = new UserRegisteredConsumer(mapper, repository);

    @Test
    void createsTheProfileThroughTheConsumerDedupBoundary() throws Exception {
        EventEnvelope event = event("42", Map.of(
                "userId", 42L, "displayName", "观测者", "status", "ACTIVE"));

        consumer.consume(mapper.writeValueAsString(event));

        ArgumentCaptor<ValidatedEvent> eventCaptor = ArgumentCaptor.forClass(ValidatedEvent.class);
        verify(repository).synchronizeProfile(eventCaptor.capture(), eq(42L), eq("观测者"));
        assertThat(eventCaptor.getValue().eventId()).isEqualTo(event.eventId());
        assertThat(eventCaptor.getValue().type()).isEqualTo(EventTopics.USER_REGISTERED);
        assertThat(eventCaptor.getValue().payload().path("displayName").asText()).isEqualTo("观测者");
    }

    @Test
    void aggregateIdentityMismatchIsRejectedBeforeProjection() throws Exception {
        EventEnvelope event = event("99", Map.of(
                "userId", 42L, "displayName", "观测者", "status", "ACTIVE"));

        assertThatThrownBy(() -> consumer.consume(mapper.writeValueAsString(event)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("User aggregateId does not match userId");

        verifyNoInteractions(repository);
    }

    @Test
    void malformedUserIdentityFieldsAreRejectedBeforeProjection() {
        assertInvalid(Map.of("displayName", "观测者"));
        assertInvalid(Map.of("userId", "42", "displayName", "观测者"));
        assertInvalid(Map.of("userId", 0L, "displayName", "观测者"));
        assertInvalid(Map.of("userId", 42L));
        assertInvalid(Map.of("userId", 42L, "displayName", 7));
        assertInvalid(Map.of("userId", 42L, "displayName", " "));
        assertInvalid(Map.of("userId", 42L, "displayName", "x".repeat(65)));
        assertInvalid(Map.of("userId", 42L, "displayName", " 观测者"));

        verifyNoInteractions(repository);
    }

    private void assertInvalid(Map<String, Object> payload) {
        EventEnvelope event = event("42", payload);
        assertThatThrownBy(() -> consumer.consume(mapper.writeValueAsString(event)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid user registration event payload");
    }

    private EventEnvelope event(String aggregateId, Map<String, Object> payload) {
        return EventEnvelope.now(EventTopics.USER_REGISTERED, "dc-auth", aggregateId, payload);
    }
}
