package io.github.devcircle.user.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventEnvelope;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.user.infrastructure.UserGraphRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UserRegisteredConsumerTest {
    @Test
    void createsTheProfileThroughTheConsumerDedupBoundary() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        UserGraphRepository repository = mock(UserGraphRepository.class);
        UserRegisteredConsumer consumer = new UserRegisteredConsumer(mapper, repository);
        EventEnvelope event = EventEnvelope.now(EventTopics.USER_REGISTERED, "dc-auth", "42",
                Map.of("userId", 42L, "displayName", "观测者", "status", "ACTIVE"));

        consumer.consume(mapper.writeValueAsString(event));

        verify(repository).synchronizeProfile(event.eventId().toString(), EventTopics.USER_REGISTERED, 42L, "观测者");
    }
}
