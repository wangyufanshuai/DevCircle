package io.github.devcircle.search.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.temporal.TemporalAccessor;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/** Encodes and validates opaque search cursors and their filter binding. */
final class SearchCursorCodec {
    private final ObjectMapper objectMapper;

    SearchCursorCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String fingerprint(String keyword, String tag, String sort, String theme,
                       String contentKind, String difficulty) {
        String canonical = String.join("\u001f", normalizeFilter(keyword), normalizeFilter(tag), sort,
                normalizeFilter(theme), normalizeFilter(contentKind), normalizeFilter(difficulty));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    Position decode(String cursor, String sort, String fingerprint) {
        if (cursor == null || cursor.isBlank()) return Position.first();
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(decoded);
            if (root.path("version").asInt() != 2
                    || !sort.equals(root.path("sort").asText())
                    || !fingerprint.equals(root.path("fingerprint").asText())) {
                throw new IllegalArgumentException();
            }
            Source source = Source.valueOf(root.path("source").asText());
            if (source == Source.SEED) {
                int offset = root.path("offset").asInt(-1);
                if (offset < 0) throw new IllegalArgumentException();
                return new Position(source, offset, List.of());
            }
            if (!root.path("values").isArray() || root.path("values").isEmpty()) {
                throw new IllegalArgumentException();
            }
            List<Object> values = objectMapper.convertValue(root.path("values"), new TypeReference<>() {});
            return new Position(source, 0, values);
        } catch (RuntimeException | JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "搜索游标无效或与当前筛选条件不匹配");
        }
    }

    String encodeElasticsearch(String sort, String fingerprint, List<Object> values) {
        return encode(new CursorPayload(2, Source.ELASTICSEARCH.name(), sort, fingerprint,
                normalizeSortValues(values), null));
    }

    String encodeSeed(int offset, String sort, String fingerprint) {
        return encode(new CursorPayload(2, Source.SEED.name(), sort, fingerprint, List.of(), offset));
    }

    private String encode(CursorPayload payload) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    objectMapper.writeValueAsBytes(payload));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to encode search cursor", ex);
        }
    }

    private List<Object> normalizeSortValues(List<Object> values) {
        return values.stream().map(value -> value instanceof TemporalAccessor ? value.toString() : value).toList();
    }

    private String normalizeFilter(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    enum Source {
        FIRST, ELASTICSEARCH, SEED
    }

    record Position(Source source, int offset, List<Object> values) {
        static Position first() {
            return new Position(Source.FIRST, 0, List.of());
        }
    }

    private record CursorPayload(int version, String source, String sort, String fingerprint,
                                 List<Object> values, Integer offset) {
    }
}
