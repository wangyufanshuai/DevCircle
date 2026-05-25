package io.github.devcircle.file.infrastructure;

import io.github.devcircle.file.api.FileUploadResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;

@Repository
public class MediaRepository {
    private final JdbcTemplate jdbcTemplate;

    public MediaRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public FileUploadResult save(String objectKey, String url, String contentType, long size, Integer width, Integer height, Integer durationSeconds) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO media_asset(object_key, url, content_type, size_bytes, width, height, duration_seconds)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, objectKey);
            statement.setString(2, url);
            statement.setString(3, contentType);
            statement.setLong(4, size);
            statement.setObject(5, width);
            statement.setObject(6, height);
            statement.setObject(7, durationSeconds);
            return statement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("Failed to save media metadata");
        }
        return new FileUploadResult(id.longValue(), objectKey, url, contentType, size, width, height, durationSeconds, "PENDING_REVIEW");
    }

    public long count() {
        return JdbcClient.create(jdbcTemplate).sql("SELECT COUNT(*) FROM media_asset").query(Long.class).single();
    }
}
