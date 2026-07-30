package io.github.devcircle.common.event;

public final class EventTopics {
    public static final String POST_CREATED = "post.created";
    public static final String POST_SUBMITTED = "post.submitted";
    public static final String POST_UPDATED = "post.updated";
    public static final String POST_DELETED = "post.deleted";
    public static final String COMMENT_CREATED = "comment.created";
    public static final String INTERACTION_CHANGED = "interaction.changed";
    public static final String NOTIFICATION_CREATED = "notification.created";
    public static final String AUDIT_COMPLETED = "audit.completed";
    public static final String CONTENT_REJECTED = "content.rejected";
    public static final String APPEAL_CREATED = "appeal.created";
    public static final String APPEAL_RESOLVED = "appeal.resolved";
    public static final String RISK_SIGNAL_CREATED = "risk.signal.created";
    public static final String RECOMMEND_EVENT_LOGGED = "recommend.event.logged";

    private EventTopics() {
    }
}
