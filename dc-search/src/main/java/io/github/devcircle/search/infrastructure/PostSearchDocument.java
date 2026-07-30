package io.github.devcircle.search.infrastructure;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;
import java.util.List;

@Document(indexName = "devcircle-posts")
public class PostSearchDocument {
    @Id
    private Long id;
    @Field(type = FieldType.Text)
    private String title;
    @Field(type = FieldType.Text)
    private String excerpt;
    @Field(type = FieldType.Keyword)
    private String author;
    @Field(type = FieldType.Long)
    private Long authorId;
    @Field(type = FieldType.Keyword)
    private List<String> tags;
    @Field(type = FieldType.Keyword)
    private String status;
    @Field(type = FieldType.Keyword)
    private String theme;
    @Field(type = FieldType.Keyword)
    private String contentKind;
    @Field(type = FieldType.Keyword)
    private String difficulty;
    @Field(type = FieldType.Date)
    private Instant createdAt;
    @Field(type = FieldType.Date)
    private Instant eventOccurredAt;

    public PostSearchDocument() {
    }

    public PostSearchDocument(Long id, String title, String excerpt, String author, List<String> tags, String status, Instant createdAt) {
        this(id, title, excerpt, author, null, tags, status, null, null, null, createdAt, createdAt);
    }

    public PostSearchDocument(Long id, String title, String excerpt, String author, List<String> tags, String status, Instant createdAt, Instant eventOccurredAt) {
        this(id, title, excerpt, author, null, tags, status, null, null, null, createdAt, eventOccurredAt);
    }

    public PostSearchDocument(Long id, String title, String excerpt, String author, List<String> tags, String status,
                              String theme, String contentKind, String difficulty,
                              Instant createdAt, Instant eventOccurredAt) {
        this(id, title, excerpt, author, null, tags, status, theme, contentKind, difficulty, createdAt, eventOccurredAt);
    }

    public PostSearchDocument(Long id, String title, String excerpt, String author, Long authorId,
                              List<String> tags, String status, String theme, String contentKind, String difficulty,
                              Instant createdAt, Instant eventOccurredAt) {
        this.id = id;
        this.title = title;
        this.excerpt = excerpt;
        this.author = author;
        this.authorId = authorId;
        this.tags = tags;
        this.status = status;
        this.theme = theme;
        this.contentKind = contentKind;
        this.difficulty = difficulty;
        this.createdAt = createdAt;
        this.eventOccurredAt = eventOccurredAt;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getExcerpt() {
        return excerpt;
    }

    public String getAuthor() {
        return author;
    }

    public Long getAuthorId() { return authorId; }

    public List<String> getTags() {
        return tags;
    }

    public String getStatus() {
        return status;
    }

    public String getTheme() { return theme; }

    public String getContentKind() { return contentKind; }

    public String getDifficulty() { return difficulty; }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getEventOccurredAt() {
        return eventOccurredAt;
    }
}
