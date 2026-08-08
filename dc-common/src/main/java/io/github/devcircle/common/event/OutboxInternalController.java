package io.github.devcircle.common.event;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/internal/ops/outbox")
public class OutboxInternalController {
    private final OutboxStatusService statusService;
    private final byte[] internalKey;

    public OutboxInternalController(OutboxStatusService statusService, String internalKey) {
        this.statusService = statusService;
        this.internalKey = bytes(internalKey);
    }

    @GetMapping("/status")
    public OutboxStatusView status(
            @RequestHeader(value = "X-Internal-Ops-Key", required = false) String providedKey) {
        if (!MessageDigest.isEqual(internalKey, bytes(providedKey))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "内部运维凭据无效");
        }
        return statusService.snapshot();
    }

    private static byte[] bytes(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
    }
}
