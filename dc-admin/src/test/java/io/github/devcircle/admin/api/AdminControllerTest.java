package io.github.devcircle.admin.api;

import io.github.devcircle.admin.application.AdminService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminControllerTest {
    private final AdminService service = mock(AdminService.class);
    private final AdminController controller = new AdminController(service);

    @Test
    void governanceDecisionsForwardIdentityReasonsAndIdempotencyKeys() {
        ModerationDecisionRequest request = new ModerationDecisionRequest("证据有效，重新进入人工复审队列");
        when(service.requireChanges(7L, 9L, "trace-1", request.reason(), "moderate-7"))
                .thenReturn(new AdminActionResult("REQUIRE_CHANGES", Map.of()));
        when(service.resolveAppeal(5L, 9L, "trace-2", request.reason(), true, "appeal-5"))
                .thenReturn(new AdminActionResult("APPEAL_APPROVED", Map.of()));
        when(service.restrictUser(12L, 9L, "trace-3", "NO_POST", request.reason(), "restrict-12"))
                .thenReturn(new AdminActionResult("RESTRICT_USER", Map.of()));
        when(service.unrestrictUser(12L, 9L, "trace-4", request.reason(), "unrestrict-12"))
                .thenReturn(new AdminActionResult("UNRESTRICT_USER", Map.of()));
        when(service.restrictUser(13L, 9L, "trace-5", "BANNED", request.reason(), "ban-13"))
                .thenReturn(new AdminActionResult("RESTRICT_USER", Map.of()));

        controller.requireChanges(7L, 9L, "moderate-7", "trace-1", request);
        controller.approveAppeal(5L, 9L, "appeal-5", "trace-2", request);
        controller.restrictUser(12L, 9L, "restrict-12", "trace-3", "NO_POST", request);
        controller.unrestrictUser(12L, 9L, "unrestrict-12", "trace-4", request);
        controller.ban(13L, 9L, "ban-13", "trace-5", request);

        verify(service).requireChanges(7L, 9L, "trace-1", request.reason(), "moderate-7");
        verify(service).resolveAppeal(5L, 9L, "trace-2", request.reason(), true, "appeal-5");
        verify(service).restrictUser(12L, 9L, "trace-3", "NO_POST", request.reason(), "restrict-12");
        verify(service).unrestrictUser(12L, 9L, "trace-4", request.reason(), "unrestrict-12");
        verify(service).restrictUser(13L, 9L, "trace-5", "BANNED", request.reason(), "ban-13");
    }
}
