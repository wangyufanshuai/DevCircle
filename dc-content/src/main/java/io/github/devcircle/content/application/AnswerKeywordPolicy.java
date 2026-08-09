package io.github.devcircle.content.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parses and evaluates the structured answer-keyword policy for deduction posts. */
final class AnswerKeywordPolicy {
    private static final TypeReference<List<String>> KEYWORD_LIST = new TypeReference<>() {
    };
    private final ObjectMapper objectMapper;

    AnswerKeywordPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<String> parse(String json) {
        try {
            List<String> values = objectMapper.readValue(json == null || json.isBlank() ? "[]" : json, KEYWORD_LIST);
            return values == null ? List.of() : values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .toList();
        } catch (JsonProcessingException | RuntimeException ex) {
            BusinessException failure = new BusinessException(ErrorCode.SYSTEM_ERROR, "解谜内容数据异常，请稍后重试");
            failure.initCause(ex);
            throw failure;
        }
    }

    boolean matches(String answer, List<String> keywords) {
        String normalizedAnswer = normalize(answer);
        Set<String> normalizedKeywords = expand(keywords);
        long hits = normalizedKeywords.stream().filter(normalizedAnswer::contains).count();
        boolean decisiveHit = normalizedKeywords.stream().filter(keyword -> keyword.length() >= 2)
                .anyMatch(keyword -> normalizedAnswer.contains(keyword) && decisiveKeywords().contains(keyword));
        int requiredHits = Math.min(2, Math.max(1, normalizedKeywords.size()));
        return decisiveHit || hits >= requiredHits;
    }

    private Set<String> expand(List<String> keywords) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String keyword : keywords) {
            String normalized = normalize(keyword);
            if (!normalized.isBlank()) {
                values.add(normalized);
                values.addAll(synonyms(normalized));
            }
        }
        return values;
    }

    private Set<String> synonyms(String keyword) {
        return switch (keyword) {
            case "夹层", "夹层藏身" -> Set.of("暗层", "隔层", "钟楼夹层", "钟楼暗层", "藏身层");
            case "调换时钟" -> Set.of("改表", "拨快时钟", "拨慢时钟", "改动钟表", "伪造时间");
            case "伪造脚印" -> Set.of("假脚印", "制造脚印", "鞋印伪造");
            case "镜面反射" -> Set.of("镜像", "反射", "倒影");
            case "潮汐延迟" -> Set.of("潮汐", "涨潮", "退潮");
            case "证词串供" -> Set.of("串供", "证词矛盾", "伪证");
            case "密码替换" -> Set.of("替换密码", "暗号", "换码");
            case "灯塔盲区" -> Set.of("盲区", "灯光死角", "照明死角");
            case "列车换厢" -> Set.of("换车厢", "换厢", "调换车厢");
            case "档案重排" -> Set.of("重排档案", "调换卷宗", "记录重排");
            default -> Set.of();
        };
    }

    private Set<String> decisiveKeywords() {
        return Set.of("夹层", "夹层藏身", "暗层", "钟楼夹层", "钟楼暗层", "调换时钟", "伪造时间",
                "伪造脚印", "镜面反射", "潮汐延迟", "证词串供", "密码替换", "灯塔盲区", "列车换厢", "档案重排");
    }

    String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}\\p{P}]", "");
    }
}
