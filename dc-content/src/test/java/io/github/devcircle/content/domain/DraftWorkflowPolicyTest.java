package io.github.devcircle.content.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DraftWorkflowPolicyTest {
    @Test
    void rejectedAndChangesRequiredPostsCanBeEditedAndResubmitted() {
        assertThat(DraftWorkflowPolicy.isAuthorEditable("DRAFT")).isTrue();
        assertThat(DraftWorkflowPolicy.isAuthorEditable("CHANGES_REQUIRED")).isTrue();
        assertThat(DraftWorkflowPolicy.isAuthorEditable("REJECTED")).isTrue();
        assertThat(DraftWorkflowPolicy.isAuthorEditable("PENDING")).isFalse();
        assertThat(DraftWorkflowPolicy.isAuthorEditable("PUBLISHED")).isFalse();
        assertThat(DraftWorkflowPolicy.submissionAuditAction("DRAFT")).isEqualTo("SUBMIT_POST");
        assertThat(DraftWorkflowPolicy.submissionAuditAction("REJECTED")).isEqualTo("RESUBMIT_POST");
    }
}
