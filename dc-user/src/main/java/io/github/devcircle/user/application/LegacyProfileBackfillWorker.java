package io.github.devcircle.user.application;

import io.github.devcircle.user.infrastructure.LegacyProfileBackfillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "devcircle.user.profile-backfill", name = "enabled",
        havingValue = "true", matchIfMissing = true)
class LegacyProfileBackfillWorker {
    private static final Logger log = LoggerFactory.getLogger(LegacyProfileBackfillWorker.class);
    private final LegacyProfileBackfillRepository repository;
    private final AtomicBoolean complete = new AtomicBoolean();

    LegacyProfileBackfillWorker(LegacyProfileBackfillRepository repository) {
        this.repository = repository;
    }

    @Scheduled(initialDelayString = "${devcircle.user.profile-backfill.initial-delay-ms:5000}",
            fixedDelayString = "${devcircle.user.profile-backfill.retry-delay-ms:30000}")
    void backfill() {
        if (complete.get()) return;
        try {
            int copied = repository.copyMissingProfiles(500);
            if (copied == 0) {
                complete.set(true);
                log.info("Legacy identity profile backfill is complete");
            } else {
                log.info("Backfilled {} legacy identity profiles", copied);
            }
        } catch (DataAccessException ex) {
            log.warn("Legacy identity profile backfill is waiting for the auth schema: {}",
                    ex.getMostSpecificCause().getMessage());
        }
    }
}
