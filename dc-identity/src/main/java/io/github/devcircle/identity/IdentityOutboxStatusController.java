package io.github.devcircle.identity;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.messaging.outbox.OutboxStatusService;
import io.github.devcircle.messaging.outbox.OutboxStatusView;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Reports every logical outbox owned by the Identity deployment in one non-lossy operations view. */
@RestController
final class IdentityOutboxStatusController {
    private final OutboxStatusService authStatus;
    private final IdentityUserOutboxStatus userStatus;
    private final byte[] internalKey;

    IdentityOutboxStatusController(
            @Qualifier("outboxStatusService") OutboxStatusService authStatus,
            IdentityUserOutboxStatus userStatus,
            @Value("${devcircle.internal.ops-key:local-development-internal-ops-key}") String internalKey) {
        this.authStatus = authStatus;
        this.userStatus = userStatus;
        this.internalKey = bytes(internalKey);
    }

    @GetMapping("/internal/ops/identity/outbox/status")
    IdentityOutboxStatusView status(
            @RequestHeader(value = "X-Internal-Ops-Key", required = false) String providedKey) {
        authorize(providedKey);
        return new IdentityOutboxStatusView(authStatus.snapshot(), userStatus.snapshot());
    }

    @GetMapping("/internal/ops/identity/outbox/auth/status")
    OutboxStatusView authStatus(
            @RequestHeader(value = "X-Internal-Ops-Key", required = false) String providedKey) {
        authorize(providedKey);
        return authStatus.snapshot();
    }

    @GetMapping("/internal/ops/identity/outbox/user/status")
    OutboxStatusView userStatus(
            @RequestHeader(value = "X-Internal-Ops-Key", required = false) String providedKey) {
        authorize(providedKey);
        return userStatus.snapshot();
    }

    private void authorize(String providedKey) {
        if (!MessageDigest.isEqual(internalKey, bytes(providedKey))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Invalid internal operations credential");
        }
    }

    private static byte[] bytes(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
    }

    record IdentityOutboxStatusView(OutboxStatusView auth, OutboxStatusView user) {
    }
}
