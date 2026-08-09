package io.github.devcircle.admin.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.admin.application.AdminOpsQueryService;
import io.github.devcircle.admin.application.AdminReadService;
import io.github.devcircle.admin.application.AdminService;
import io.github.devcircle.admin.application.DltReplayService;
import io.github.devcircle.admin.application.query.DegradationView;
import io.github.devcircle.admin.application.query.OpsHealthView;
import io.github.devcircle.admin.application.query.OutboxEventView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
class AdminController {
    private final AdminService adminService;
    private final AdminReadService adminReadService;
    private final DltReplayService dltReplayService;
    private final AdminOpsQueryService adminOpsQueryService;

    AdminController(AdminService adminService, AdminReadService adminReadService, DltReplayService dltReplayService,
                    AdminOpsQueryService adminOpsQueryService) {
        this.adminService = adminService;
        this.adminReadService = adminReadService;
        this.dltReplayService = dltReplayService;
        this.adminOpsQueryService = adminOpsQueryService;
    }

    @GetMapping("/audits/posts")
    ApiResponse<List<AuditItem>> pendingPosts() {
        return ApiResponse.ok(adminReadService.pendingPosts());
    }

    @PostMapping("/audits/posts/{id}/approve")
    ApiResponse<AdminActionResult> approve(@PathVariable Long id,
                                           @RequestHeader("X-User-Id") Long userId,
                                           @RequestHeader("Idempotency-Key") String idempotencyKey,
                                           @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                                           @Valid @RequestBody ModerationDecisionRequest request) {
        return ApiResponse.ok(adminService.approve(id, userId, traceId, request.reason(), idempotencyKey));
    }

    @PostMapping("/audits/posts/{id}/reject")
    ApiResponse<AdminActionResult> reject(@PathVariable Long id,
                                          @RequestHeader("X-User-Id") Long userId,
                                          @RequestHeader("Idempotency-Key") String idempotencyKey,
                                          @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                                          @Valid @RequestBody ModerationDecisionRequest request) {
        return ApiResponse.ok(adminService.reject(id, userId, traceId, request.reason(), idempotencyKey));
    }

    @GetMapping("/moderation/tasks")
    ApiResponse<List<Map<String, Object>>> moderationTasks(@RequestParam(required = false) String queue,
                                                           @RequestParam(required = false) String riskLevel,
                                                           @RequestParam(required = false) String status,
                                                           @RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminReadService.moderationTasks(queue, riskLevel, status, page, size));
    }

    @PostMapping("/moderation/tasks/{id}/approve")
    ApiResponse<AdminActionResult> approveTask(@PathVariable Long id,
                                               @RequestHeader("X-User-Id") Long userId,
                                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                                               @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                                               @Valid @RequestBody ModerationDecisionRequest request) {
        return ApiResponse.ok(adminService.approveModerationTask(id, userId, traceId, request.reason(), idempotencyKey));
    }

    @PostMapping("/moderation/tasks/{id}/reject")
    ApiResponse<AdminActionResult> rejectTask(@PathVariable Long id,
                                              @RequestHeader("X-User-Id") Long userId,
                                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                                              @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                                              @Valid @RequestBody ModerationDecisionRequest request) {
        return ApiResponse.ok(adminService.rejectModerationTask(id, userId, traceId, request.reason(), idempotencyKey));
    }

    @PostMapping("/moderation/tasks/{id}/escalate")
    ApiResponse<AdminActionResult> escalateTask(@PathVariable Long id,
                                                @RequestHeader("X-User-Id") Long userId,
                                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                                                @Valid @RequestBody ModerationDecisionRequest request) {
        return ApiResponse.ok(adminService.escalateModerationTask(id, userId, traceId, request.reason(), idempotencyKey));
    }

    @PostMapping("/moderation/tasks/{id}/require-changes")
    ApiResponse<AdminActionResult> requireChanges(@PathVariable Long id,
                                                  @RequestHeader("X-User-Id") Long userId,
                                                  @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                  @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                                                  @Valid @RequestBody ModerationDecisionRequest request) {
        return ApiResponse.ok(adminService.requireChanges(id, userId, traceId, request.reason(), idempotencyKey));
    }

    @GetMapping("/appeals")
    ApiResponse<List<Map<String, Object>>> appeals(@RequestParam(required = false) String status,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminReadService.appeals(status, page, size));
    }

    @PostMapping("/appeals/{id}/approve")
    ApiResponse<AdminActionResult> approveAppeal(@PathVariable Long id,
                                                 @RequestHeader("X-User-Id") Long userId,
                                                 @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                 @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                                                 @Valid @RequestBody ModerationDecisionRequest request) {
        return ApiResponse.ok(adminService.resolveAppeal(id, userId, traceId, request.reason(), true, idempotencyKey));
    }

    @PostMapping("/appeals/{id}/reject")
    ApiResponse<AdminActionResult> rejectAppeal(@PathVariable Long id,
                                                @RequestHeader("X-User-Id") Long userId,
                                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                                                @Valid @RequestBody ModerationDecisionRequest request) {
        return ApiResponse.ok(adminService.resolveAppeal(id, userId, traceId, request.reason(), false, idempotencyKey));
    }

    @PostMapping("/users/{id}/ban")
    ApiResponse<AdminActionResult> ban(@PathVariable Long id,
                                      @RequestHeader("X-User-Id") Long userId,
                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                      @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                                      @Valid @RequestBody ModerationDecisionRequest request) {
        return ApiResponse.ok(adminService.restrictUser(id, userId, traceId, "BANNED", request.reason(), idempotencyKey));
    }

    @GetMapping("/risk/users")
    ApiResponse<List<Map<String, Object>>> riskUsers(@RequestParam(required = false) String status,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminReadService.riskUsers(status, page, size));
    }

    @GetMapping("/risk/users/{id}/profile")
    ApiResponse<Map<String, Object>> riskProfile(@PathVariable Long id) {
        return ApiResponse.ok(adminReadService.riskProfile(id));
    }

    @PostMapping("/risk/users/{id}/restrict")
    ApiResponse<AdminActionResult> restrictUser(@PathVariable Long id,
                                                @RequestHeader("X-User-Id") Long userId,
                                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                                                @RequestParam(defaultValue = "NO_POST") String status,
                                                @Valid @RequestBody ModerationDecisionRequest request) {
        return ApiResponse.ok(adminService.restrictUser(id, userId, traceId, status, request.reason(), idempotencyKey));
    }

    @PostMapping("/risk/users/{id}/unrestrict")
    ApiResponse<AdminActionResult> unrestrictUser(@PathVariable Long id,
                                                  @RequestHeader("X-User-Id") Long userId,
                                                  @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                  @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                                                  @Valid @RequestBody ModerationDecisionRequest request) {
        return ApiResponse.ok(adminService.unrestrictUser(id, userId, traceId, request.reason(), idempotencyKey));
    }

    @PostMapping("/tags")
    ApiResponse<AdminActionResult> createTag(@RequestHeader("X-User-Id") Long userId,
                                             @RequestHeader("Idempotency-Key") String idempotencyKey,
                                             @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                                             @Valid @RequestBody CreateTagRequest request) {
        return ApiResponse.ok(adminService.createTag(request.name(), userId, traceId, idempotencyKey));
    }

    @GetMapping("/metrics/overview")
    ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.ok(adminReadService.overview());
    }

    @GetMapping("/metrics/themes")
    ApiResponse<List<Map<String, Object>>> themeMetrics() {
        return ApiResponse.ok(adminReadService.themeMetrics());
    }

    @GetMapping("/recommend/experiments")
    ApiResponse<List<Map<String, Object>>> experiments() {
        return ApiResponse.ok(adminReadService.experiments());
    }

    @PostMapping("/recommend/experiments")
    ApiResponse<AdminActionResult> createExperiment(@RequestHeader("X-User-Id") Long userId,
                                                     @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                     @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                                                     @RequestBody Map<String, Object> payload) {
        return ApiResponse.ok(adminService.createExperiment(payload, userId, traceId, idempotencyKey));
    }

    @PostMapping("/recommend/experiments/{id}/activate")
    ApiResponse<AdminActionResult> activateExperiment(@PathVariable String id,
                                                       @RequestHeader("X-User-Id") Long userId,
                                                       @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                       @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(adminService.updateExperimentStatus(id, "ACTIVE", userId, traceId, idempotencyKey));
    }

    @PostMapping("/recommend/experiments/{id}/pause")
    ApiResponse<AdminActionResult> pauseExperiment(@PathVariable String id,
                                                    @RequestHeader("X-User-Id") Long userId,
                                                    @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                    @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(adminService.updateExperimentStatus(id, "PAUSED", userId, traceId, idempotencyKey));
    }

    @GetMapping("/audit-logs")
    ApiResponse<List<Map<String, Object>>> auditLogs(@RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size,
                                                     @RequestParam(required = false) String action) {
        return ApiResponse.ok(adminReadService.auditLogs(page, size, action));
    }

    @GetMapping("/ops/health")
    ApiResponse<OpsHealthView> opsHealth() {
        return ApiResponse.ok(adminOpsQueryService.health());
    }

    @GetMapping("/ops/outbox")
    ApiResponse<List<OutboxEventView>> outboxEvents(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String topic,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminOpsQueryService.outboxEvents(status, topic, page, size));
    }

    @PostMapping("/ops/outbox/replay")
    ApiResponse<AdminActionResult> replayOutbox(@RequestParam(required = false) String topic,
                                                @RequestParam(defaultValue = "100") int batchSize,
                                                @RequestHeader("X-User-Id") Long userId,
                                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(adminService.replayOutbox(topic, batchSize, userId, traceId, idempotencyKey));
    }

    @PostMapping("/ops/dlt/replay")
    ApiResponse<AdminActionResult> replayDlt(@RequestHeader("X-User-Id") Long userId,
                                             @RequestHeader("Idempotency-Key") String idempotencyKey,
                                             @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                                             @Valid @RequestBody DltReplayRequest request) {
        return ApiResponse.ok(dltReplayService.replay(request, userId, traceId, idempotencyKey));
    }

    @GetMapping("/ops/degradation")
    ApiResponse<DegradationView> degradation() {
        return ApiResponse.ok(adminOpsQueryService.degradation());
    }
}
