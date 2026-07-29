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
    @Field(type = FieldType.Keyword)
    private List<String> tags;
    @Field(type = FieldType.Keyword)
    private String status;
    @Field(type = FieldType.Date)
    private Instant createdAt;
    @Field(type = FieldType.Date)
    private Instant eventOccurredAt;

    public PostSearchDocument() {
    }

    public PostSearchDocument(Long id, String title, String excerpt, String author, List<String> tags, String status, Instant createdAt) {
        this(id, title, excerpt, author, tags, status, createdAt, createdAt);
    }

    public PostSearchDocument(Long id, String title, String excerpt, String author, List<String> tags, String status, Instant createdAt, Instant eventOccurredAt) {
        this.id = id;
        this.title = title;
        this.excerpt = excerpt;
        this.author = author;
        this.tags = tags;
        this.status = status;
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

    public List<String> getTags() {
        return tags;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getEventOccurredAt() {
        return eventOccurredAt;
    }
}
