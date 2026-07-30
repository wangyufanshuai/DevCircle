package io.github.devcircle.content.domain;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.content.api.DraftMediaView;
import io.github.devcircle.content.api.DraftView;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DraftSubmissionValidator {
    private static final Pattern IMAGE_START = Pattern.compile("(?<!\\\\)!\\[");
    private static final Pattern MEDIA_IMAGE = Pattern.compile(
            "(?<!\\\\)!\\[([^]\\r\\n]*)]\\(\\s*media://([1-9][0-9]*)\\s*\\)");
    private static final Pattern RAW_MEDIA_TAG = Pattern.compile(
            "<\\s*(?:img|video|audio|source|iframe)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\r\\n]*`");

    private DraftSubmissionValidator() {
    }

    public static void validate(DraftView draft) {
        if (draft.title().isBlank() || draft.content().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "标题和正文填写完整后才能投稿");
        }

        Map<Long, DraftMediaView> attached = new HashMap<>();
        int covers = 0;
        for (DraftMediaView media : draft.media()) {
            if (attached.put(media.mediaId(), media) != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "草稿中存在重复媒体，请重新保存后投稿");
            }
            if (!"COVER".equals(media.role()) && !"INLINE".equals(media.role())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "草稿中存在无效媒体角色，请重新设置后投稿");
            }
            if ("COVER".equals(media.role()) && ++covers > 1) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "每份投稿只能设置一张封面");
            }
            if (media.altText() == null || media.altText().isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请为每个媒体填写替代文本后再投稿");
            }
        }

        String content = withoutCode(draft.content());
        if (RAW_MEDIA_TAG.matcher(content).find()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "正文不支持直接嵌入 HTML 媒体，请使用创作台的“插入正文”功能");
        }

        int imageCount = count(IMAGE_START.matcher(content));
        Set<Long> referenced = new HashSet<>();
        Matcher matcher = MEDIA_IMAGE.matcher(content);
        int validCount = 0;
        while (matcher.find()) {
            validCount++;
            long mediaId;
            try {
                mediaId = Long.parseLong(matcher.group(2));
            } catch (NumberFormatException ex) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "正文媒体 ID 无效，请重新插入");
            }
            DraftMediaView media = attached.get(mediaId);
            if (media == null) {
                throw new BusinessException(ErrorCode.UPLOAD_REJECTED, "正文引用了未关联到本草稿的媒体");
            }
            if (!"INLINE".equals(media.role())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "封面不能作为正文媒体引用，请先将其角色改为正文媒体");
            }
            referenced.add(mediaId);
        }
        if (imageCount != validCount) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "正文图片只允许引用本人上传的媒体，请使用创作台的“插入正文”功能");
        }

        Set<Long> unplaced = new HashSet<>();
        for (DraftMediaView media : attached.values()) {
            if ("INLINE".equals(media.role()) && !referenced.contains(media.mediaId())) {
                unplaced.add(media.mediaId());
            }
        }
        if (!unplaced.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "仍有正文媒体未插入文章，请完成排版后再投稿");
        }
    }

    private static int count(Matcher matcher) {
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static String withoutCode(String markdown) {
        StringBuilder result = new StringBuilder(markdown.length());
        boolean fenced = false;
        char fence = 0;
        for (String line : markdown.split("\\R", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                char currentFence = trimmed.charAt(0);
                if (!fenced) {
                    fenced = true;
                    fence = currentFence;
                } else if (fence == currentFence) {
                    fenced = false;
                }
                result.append('\n');
                continue;
            }
            result.append(fenced ? "\n" : INLINE_CODE.matcher(line).replaceAll("")).append('\n');
        }
        return result.toString();
    }
}
