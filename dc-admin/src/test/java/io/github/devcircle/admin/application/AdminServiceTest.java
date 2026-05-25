package io.github.devcircle.admin.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminServiceTest {
    @Test
    void approvePostEmitsAuditCompletedShape() {
        var result = new AdminService().approve(1002L);

        assertThat(result.action()).isEqualTo("APPROVE_POST");
        assertThat(result.attributes()).containsEntry("status", "PUBLISHED");
        assertThat(result.attributes()).containsEntry("event", "audit.completed");
    }
}

