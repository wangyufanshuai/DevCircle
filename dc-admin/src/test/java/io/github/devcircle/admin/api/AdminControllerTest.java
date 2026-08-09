package io.github.devcircle.admin.api;

import io.github.devcircle.admin.application.AdminOpsQueryService;
import io.github.devcircle.admin.application.AdminModerationService;
import io.github.devcircle.admin.application.AdminReadService;
import io.github.devcircle.admin.application.query.DegradationView;
import io.github.devcircle.admin.application.query.OpsHealthView;
import io.github.devcircle.admin.application.query.OutboxEventView;
import io.github.devcircle.admin.application.query.OutboxSourceView;
import io.github.devcircle.admin.application.AdminService;
import io.github.devcircle.admin.application.DltReplayService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminControllerTest {
    private final AdminService service = mock(AdminService.class);
    private final AdminModerationService moderationService = mock(AdminModerationService.class);
    private final AdminReadService readService = mock(AdminReadService.class);
    private final DltReplayService dltReplayService = mock(DltReplayService.class);
    private final AdminOpsQueryService opsQueryService = mock(AdminOpsQueryService.class);
    private final AdminController controller = new AdminController(
            service, moderationService, readService, dltReplayService, opsQueryService);

    @Test
    void governanceDecisionsForwardIdentityReasonsAndIdempotencyKeys() {
        ModerationDecisionRequest request = new ModerationDecisionRequest("证据有效，重新进入人工复审队列");
        when(moderationService.requireChanges(7L, 9L, "trace-1", request.reason(), "moderate-7"))
                .thenReturn(new AdminActionResult("REQUIRE_CHANGES", Map.of()));
        when(service.resolveAppeal(5L, 9L, "trace-2", request.reason(), true, "appeal-5"))
                .thenReturn(new AdminActionResult("APPEAL_APPROVED", Map.of()));
        when(service.restrictUser(12L, 9L, "trace-3", "NO_POST", request.reason(), "restrict-12"))
                .thenReturn(new AdminActionResult("RESTRICT_USER", Map.of()));
        when(service.unrestrictUser(12L, 9L, "trace-4", request.reason(), "unrestrict-12"))
                .thenReturn(new AdminActionResult("UNRESTRICT_USER", Map.of()));
        when(service.restrictUser(13L, 9L, "trace-5", "BANNED", request.reason(), "ban-13"))
                .thenReturn(new AdminActionResult("RESTRICT_USER", Map.of()));
        Map<String, Object> experiment = Map.of("experimentId", "exp-home-a", "name", "首页实验");
        when(service.createExperiment(experiment, 9L, "trace-6", "experiment-create"))
                .thenReturn(new AdminActionResult("CREATE_RECOMMEND_EXPERIMENT", Map.of()));
        when(service.updateExperimentStatus("exp-home-a", "ACTIVE", 9L, "trace-7", "experiment-active"))
                .thenReturn(new AdminActionResult("UPDATE_RECOMMEND_EXPERIMENT", Map.of()));
        when(service.replayOutbox("post.submitted", 25, 9L, "trace-8", "outbox-replay"))
                .thenReturn(new AdminActionResult("REPLAY_OUTBOX", Map.of()));
        DltReplayRequest dltReplay = new DltReplayRequest("audit.completed", 0, 7,
                "Elasticsearch has recovered and the event is safe to replay.");
        when(dltReplayService.replay(dltReplay, 9L, "trace-dlt", "dlt-replay"))
                .thenReturn(new AdminActionResult("REPLAY_KAFKA_DLT", Map.of()));
        CreateTagRequest tag = new CreateTagRequest("轨道档案");
        when(service.createTag(tag.name(), 9L, "trace-9", "tag-create"))
                .thenReturn(new AdminActionResult("CREATE_TAG", Map.of()));

        controller.requireChanges(7L, 9L, "moderate-7", "trace-1", request);
        controller.approveAppeal(5L, 9L, "appeal-5", "trace-2", request);
        controller.restrictUser(12L, 9L, "restrict-12", "trace-3", "NO_POST", request);
        controller.unrestrictUser(12L, 9L, "unrestrict-12", "trace-4", request);
        controller.ban(13L, 9L, "ban-13", "trace-5", request);
        controller.createExperiment(9L, "experiment-create", "trace-6", experiment);
        controller.activateExperiment("exp-home-a", 9L, "experiment-active", "trace-7");
        controller.replayOutbox("post.submitted", 25, 9L, "outbox-replay", "trace-8");
        controller.replayDlt(9L, "dlt-replay", "trace-dlt", dltReplay);
        controller.createTag(9L, "tag-create", "trace-9", tag);

        verify(moderationService).requireChanges(7L, 9L, "trace-1", request.reason(), "moderate-7");
        verify(service).resolveAppeal(5L, 9L, "trace-2", request.reason(), true, "appeal-5");
        verify(service).restrictUser(12L, 9L, "trace-3", "NO_POST", request.reason(), "restrict-12");
        verify(service).unrestrictUser(12L, 9L, "trace-4", request.reason(), "unrestrict-12");
        verify(service).restrictUser(13L, 9L, "trace-5", "BANNED", request.reason(), "ban-13");
        verify(service).createExperiment(experiment, 9L, "trace-6", "experiment-create");
        verify(service).updateExperimentStatus("exp-home-a", "ACTIVE", 9L, "trace-7", "experiment-active");
        verify(service).replayOutbox("post.submitted", 25, 9L, "trace-8", "outbox-replay");
        verify(dltReplayService).replay(dltReplay, 9L, "trace-dlt", "dlt-replay");
        verify(service).createTag(tag.name(), 9L, "trace-9", "tag-create");
    }

    @Test
    void operationsEndpointsReturnStronglyTypedFleetHealth() {
        OpsHealthView health = new OpsHealthView("UP", 3, 2, 1, 1, "FLEET", false,
                List.of(OutboxSourceView.unavailable("dc-user")), List.of("dc-user"),
                2L, 1L, 180L, "DB", true, "OUTBOX_SOURCE_UNAVAILABLE",
                "OUTBOX_SOURCE_UNAVAILABLE");
        DegradationView degradation = new DegradationView(true, "DB", "OUTBOX_SOURCE_UNAVAILABLE",
                3, 2, 1, 1, "FLEET", false,
                List.of(OutboxSourceView.unavailable("dc-user")), List.of("dc-user"),
                10L, 12L, 2L, "OUTBOX_SOURCE_UNAVAILABLE");
        OutboxEventView event = new OutboxEventView(7, "event-7", "post.submitted", "POST",
                "42", "DEAD", 3, null, null, "previous failure", null, null);
        when(opsQueryService.health()).thenReturn(health);
        when(opsQueryService.degradation()).thenReturn(degradation);
        when(opsQueryService.outboxEvents("DEAD", "post.submitted", 1, 20)).thenReturn(List.of(event));

        org.assertj.core.api.Assertions.assertThat(controller.opsHealth().data()).isEqualTo(health);
        org.assertj.core.api.Assertions.assertThat(controller.degradation().data()).isEqualTo(degradation);
        org.assertj.core.api.Assertions.assertThat(controller.outboxEvents("DEAD", "post.submitted", 1, 20).data())
                .containsExactly(event);
    }
}
