package io.github.devcircle.content.domain;

import java.util.Set;

public final class DraftWorkflowPolicy {
    private static final Set<String> AUTHOR_EDITABLE = Set.of("DRAFT", "CHANGES_REQUIRED", "REJECTED");

    private DraftWorkflowPolicy() {
    }

    public static boolean isAuthorEditable(String status) {
        return AUTHOR_EDITABLE.contains(status);
    }

    public static String submissionAuditAction(String previousStatus) {
        return "DRAFT".equals(previousStatus) ? "SUBMIT_POST" : "RESUBMIT_POST";
    }
}
