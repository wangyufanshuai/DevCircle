package io.github.devcircle.admin.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.admin.application.AdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
class AdminController {
    private final AdminService adminService;

    AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/audits/posts")
    ApiResponse<List<AuditItem>> pendingPosts() {
        return ApiResponse.ok(adminService.pendingPosts());
    }

    @PostMapping("/audits/posts/{id}/approve")
    ApiResponse<AdminActionResult> approve(@PathVariable Long id,
                                           @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
                                           @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(adminService.approve(id, userId, traceId));
    }

    @PostMapping("/audits/posts/{id}/reject")
    ApiResponse<AdminActionResult> reject(@PathVariable Long id,
                                          @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
                                          @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(adminService.reject(id, userId, traceId));
    }

    @PostMapping("/users/{id}/ban")
    ApiResponse<AdminActionResult> ban(@PathVariable Long id) {
        return ApiResponse.ok(adminService.ban(id));
    }

    @PostMapping("/tags")
    ApiResponse<AdminActionResult> createTag() {
        return ApiResponse.ok(adminService.createTag());
    }

    @GetMapping("/metrics/overview")
    ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.ok(adminService.overview());
    }

    @GetMapping("/audit-logs")
    ApiResponse<List<Map<String, Object>>> auditLogs(@RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size,
                                                     @RequestParam(required = false) String action) {
        return ApiResponse.ok(adminService.auditLogs(page, size, action));
    }
}
