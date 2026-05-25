package io.github.devcircle.recommend.application;

import io.github.devcircle.common.api.CursorPage;
import io.github.devcircle.common.model.ExposureRequest;
import io.github.devcircle.common.model.FeedItem;
import io.github.devcircle.common.model.MediaAssetView;
import io.github.devcircle.common.model.PostSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class FeedService {
    private final RecommendationScorer scorer;
    private final LightweightMlRanker mlRanker;
    private final StringRedisTemplate redisTemplate;

    private final List<PostSummary> seedPosts = List.of(
            new PostSummary(1001L, "TEXT", "DEDUCTION", "CASE_FILE", "HARD", true, 31,
                    "雾港钟楼失踪案", "凌晨零点钟声响起后，守夜人从封闭钟楼消失，只留下停在 00:07 的怀表、潮湿鞋印和一页被撕掉的航海日志。", "林墨",
                    List.of("密室", "时间线", "海港"), List.of(), 428, 86, 15800, "PUBLISHED", Instant.now().minusSeconds(7200)),
            new PostSummary(1002L, "TEXT", "DEDUCTION", "PUZZLE", "MEDIUM", true, 54,
                    "列车第七节车厢的无声证词", "三名乘客都说自己没有离座，但餐车账单、雨伞水痕和被调快的车厢钟暴露了真正的移动路线。", "顾白",
                    List.of("逻辑谜题", "列车", "证词"), List.of(), 351, 64, 12200, "PUBLISHED", Instant.now().minusSeconds(5300)),
            new PostSummary(1003L, "IMAGE", "SCI_FI", "WORLDBUILDING", "MEDIUM", false, 0,
                    "木卫二冰下城市设定集", "多图拆解冰壳钻井、潮汐能源塔、低温生态穹顶和迁徙路线，适合科幻世界观共创。", "陈星",
                    List.of("世界观", "木卫二", "城市"), List.of(
                    image(201L, "demo/europa-city-1.webp", "https://picsum.photos/seed/europa-city-1/720/960", 720, 960),
                    image(202L, "demo/europa-city-2.webp", "https://picsum.photos/seed/europa-city-2/720/820", 720, 820),
                    image(203L, "demo/europa-city-3.webp", "https://picsum.photos/seed/europa-city-3/720/1040", 720, 1040)
            ), 312, 44, 9360, "PUBLISHED", Instant.now().minusSeconds(3600)),
            new PostSummary(1004L, "TEXT", "SCI_FI", "FICTION", "EASY", false, 0,
                    "短篇：给火星雨季的最后一封信", "通信延迟二十二分钟，地球回信抵达时，火星穹顶外的雨已经变成蓝色冰晶。", "许澜",
                    List.of("火星", "短篇", "移民"), List.of(), 489, 91, 18200, "PUBLISHED", Instant.now().minusSeconds(9900)),
            new PostSummary(1005L, "VIDEO", "COSMOS", "OBSERVATION_LOG", "EASY", false, 0,
                    "猎户臂边缘的一分钟观测日志", "用纵向视频记录星云光谱、脉冲星信号和无人探测器回传的异常闪烁。", "王遥",
                    List.of("观测日志", "星云", "探测器"), List.of(
                    video(301L, "demo/orion-log.mp4", "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4", 720, 1280, 18)
            ), 197, 28, 6100, "PUBLISHED", Instant.now().minusSeconds(900)),
            new PostSummary(1006L, "IMAGE", "COSMOS", "COSMIC_ARTICLE", "MEDIUM", false, 0,
                    "银河系旋臂档案：从太阳邻域到英仙臂", "把恒星形成区、尘埃带、超新星遗迹和航线窗口整理成一组可读的宇宙档案卡。", "周望",
                    List.of("银河系", "旋臂", "档案"), List.of(
                    image(401L, "demo/galaxy-arm-1.webp", "https://picsum.photos/seed/galaxy-arm-1/720/900", 720, 900),
                    image(402L, "demo/galaxy-arm-2.webp", "https://picsum.photos/seed/galaxy-arm-2/720/780", 720, 780)
            ), 265, 39, 8400, "PUBLISHED", Instant.now().minusSeconds(12000)),
            new PostSummary(1007L, "TEXT", "DEDUCTION", "CASE_FILE", "MEDIUM", true, 18,
                    "旧书店的红色书签", "死者夹在《时间机器》里的红色书签不是标记页码，而是指向书架倒影里的第四个名字。", "沈岚",
                    List.of("书店", "倒影", "暗号"), List.of(), 284, 51, 9900, "PUBLISHED", Instant.now().minusSeconds(14200)),
            new PostSummary(1008L, "IMAGE", "SCI_FI", "WORLDBUILDING", "HARD", false, 0,
                    "环土星共和国舰队识别图谱", "战术舰、农业环舱、远距通信帆和自治港口的视觉设定，包含编号体系和政治派系。", "陆离",
                    List.of("舰队", "土星", "设定"), List.of(
                    image(501L, "demo/saturn-fleet-1.webp", "https://picsum.photos/seed/saturn-fleet-1/720/1100", 720, 1100),
                    image(502L, "demo/saturn-fleet-2.webp", "https://picsum.photos/seed/saturn-fleet-2/720/860", 720, 860)
            ), 378, 70, 14600, "PUBLISHED", Instant.now().minusSeconds(21000)),
            new PostSummary(1009L, "TEXT", "COSMOS", "COSMIC_ARTICLE", "HARD", false, 0,
                    "如果暗物质有天气：一份科普化假说", "用可解释模型模拟暗物质晕的密度波动，讨论它会怎样影响星系盘、引力透镜和恒星流。", "闻舟",
                    List.of("暗物质", "科普", "星系"), List.of(), 421, 73, 16900, "PUBLISHED", Instant.now().minusSeconds(26000)),
            new PostSummary(1010L, "VIDEO", "SCI_FI", "FICTION", "MEDIUM", false, 0,
                    "垂直短剧：仿生人第九次醒来", "每次重启都少一段记忆，但墙上的宇宙微波背景图多出一个红点。", "白夜",
                    List.of("仿生人", "短剧", "记忆"), List.of(
                    video(601L, "demo/android-awake.mp4", "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4", 720, 1280, 24)
            ), 236, 42, 7800, "PUBLISHED", Instant.now().minusSeconds(31000)),
            new PostSummary(1011L, "TEXT", "DEDUCTION", "PUZZLE", "EASY", true, 77,
                    "三枚硬币和一盏坏掉的路灯", "不用复杂数学，只靠目击者站位、路灯闪烁频率和硬币落点就能排除两个假证词。", "叶青",
                    List.of("入门谜题", "目击者", "硬币"), List.of(), 229, 33, 7200, "PUBLISHED", Instant.now().minusSeconds(41000)),
            new PostSummary(1012L, "IMAGE", "COSMOS", "OBSERVATION_LOG", "MEDIUM", false, 0,
                    "旅行者号未归档影像精选", "用图像卡片展示深空探测器可能经过的尘埃云、冰巨行星边缘和恒星际介质。", "高远",
                    List.of("旅行者", "深空", "影像"), List.of(
                    image(701L, "demo/voyager-1.webp", "https://picsum.photos/seed/voyager-1/720/980", 720, 980),
                    image(702L, "demo/voyager-2.webp", "https://picsum.photos/seed/voyager-2/720/840", 720, 840),
                    image(703L, "demo/voyager-3.webp", "https://picsum.photos/seed/voyager-3/720/1060", 720, 1060)
            ), 318, 49, 10800, "PUBLISHED", Instant.now().minusSeconds(52000))
    );

    public FeedService(RecommendationScorer scorer, LightweightMlRanker mlRanker, StringRedisTemplate redisTemplate) {
        this.scorer = scorer;
        this.mlRanker = mlRanker;
        this.redisTemplate = redisTemplate;
    }

    public CursorPage<FeedItem> feed(Long userId, String cursor, int size) {
        return feed(userId, cursor, size, "mixed");
    }

    public CursorPage<FeedItem> feed(Long userId, String cursor, int size, String scene) {
        return feedFromPosts(userId, seedPosts, cursor, size, scene);
    }

    public CursorPage<FeedItem> feedFromPosts(Long userId, List<PostSummary> candidates, String cursor, int size) {
        return feedFromPosts(userId, candidates, cursor, size, "mixed");
    }

    public CursorPage<FeedItem> feedFromPosts(Long userId, List<PostSummary> candidates, String cursor, int size, String scene) {
        int offset = parseCursor(cursor);
        String normalizedScene = normalizeScene(scene);
        Set<String> interests = interests(userId);
        Set<Long> exposed = exposed(userId);
        List<FeedItem> ranked = candidates.stream()
                .filter(post -> matchesScene(post, normalizedScene))
                .map(post -> toFeedItem(userId, post, interests, exposed, normalizedScene))
                .filter(item -> item.score() > -100)
                .sorted(Comparator.comparingDouble(FeedItem::score).reversed())
                .toList();
        List<FeedItem> page = ranked.stream().skip(offset).limit(size).toList();
        int nextOffset = offset + page.size();
        return CursorPage.of(page, String.valueOf(nextOffset), nextOffset < ranked.size());
    }

    public int recordExposure(ExposureRequest request) {
        if (request.postIds() == null || request.postIds().isEmpty()) {
            return 0;
        }
        try {
            String key = "user:" + request.userId() + ":exposed-posts";
            String[] ids = request.postIds().stream().map(String::valueOf).toArray(String[]::new);
            redisTemplate.opsForSet().add(key, ids);
            redisTemplate.expire(key, Duration.ofDays(7));
        } catch (RuntimeException ignored) {
        }
        return request.postIds().size();
    }

    private FeedItem toFeedItem(Long userId, PostSummary post, Set<String> interests, Set<Long> exposed, String scene) {
        double hotScore = hotScore(post.id());
        boolean followedAuthor = followedAuthor(userId, post.author());
        boolean alreadyExposed = exposed.contains(post.id());
        long mutualFollowees = mutualFollowees(userId, post.author());
        try {
            var ranked = mlRanker.rank(post, interests, hotScore, followedAuthor, mutualFollowees, alreadyExposed, scene);
            double themeBoost = post.theme().equalsIgnoreCase(sceneToTheme(scene)) ? 18 : 0;
            double solveBoost = post.solvable() && post.solvedCount() > 20 ? 8 : 0;
            return new FeedItem(post, reason(post, interests, hotScore, followedAuthor, mutualFollowees), ranked.score() + themeBoost + solveBoost, false, false, "THEME_LINEAR_MODEL", post.type(), ranked.features());
        } catch (RuntimeException ex) {
            double score = scorer.score(post, interests, hotScore, followedAuthor, alreadyExposed);
            return new FeedItem(post, scorer.reason(post, interests, hotScore, followedAuthor), score, false, false, "RULE_FALLBACK", post.type(), Map.of());
        }
    }

    private String reason(PostSummary post, Set<String> interests, double hotScore, boolean followedAuthor, long mutualFollowees) {
        if (post.solvable()) return "可解谜案件，已有 " + post.solvedCount() + " 人完成推理";
        if (followedAuthor) return "来自你关注的创作者";
        if (mutualFollowees > 0) return "你们有共同关注，图谱亲密度较高";
        if (post.tags().stream().anyMatch(interests::contains)) return "与你最近关注的主题标签相关";
        if (hotScore > 20 || post.likes() > 300) return "近期互动热度较高";
        return switch (post.theme()) {
            case "SCI_FI" -> "科幻设定与短篇创作精选";
            case "COSMOS" -> "宇宙档案与观测日志推荐";
            default -> "推理馆编辑精选";
        };
    }

    private boolean matchesScene(PostSummary post, String scene) {
        if ("mixed".equals(scene) || "home".equals(scene)) {
            return true;
        }
        if ("image".equals(scene) || "video".equals(scene)) {
            return post.type().equalsIgnoreCase(scene);
        }
        return post.theme().equalsIgnoreCase(sceneToTheme(scene));
    }

    private String sceneToTheme(String scene) {
        return switch (scene) {
            case "sci_fi" -> "SCI_FI";
            case "cosmos" -> "COSMOS";
            default -> "DEDUCTION";
        };
    }

    private String normalizeScene(String scene) {
        if (scene == null || scene.isBlank()) {
            return "mixed";
        }
        String normalized = scene.toLowerCase();
        if (List.of("home", "mixed", "deduction", "sci_fi", "cosmos", "image", "video").contains(normalized)) {
            return normalized;
        }
        return "mixed";
    }

    private Set<String> interests(Long userId) {
        try {
            Set<String> values = redisTemplate.opsForSet().members("user:" + userId + ":interest-tags");
            if (values != null && !values.isEmpty()) return values;
        } catch (RuntimeException ignored) {
        }
        return Set.of("密室", "世界观", "星云");
    }

    private Set<Long> exposed(Long userId) {
        try {
            Set<String> values = redisTemplate.opsForSet().members("user:" + userId + ":exposed-posts");
            if (values != null) return values.stream().map(Long::valueOf).collect(java.util.stream.Collectors.toSet());
        } catch (RuntimeException ignored) {
        }
        return Set.of();
    }

    private boolean followedAuthor(Long userId, String author) {
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember("user:" + userId + ":followees", author));
        } catch (RuntimeException ignored) {
            return "林墨".equals(author) && userId == 1L;
        }
    }

    private long mutualFollowees(Long userId, String author) {
        try {
            String value = (String) redisTemplate.opsForHash().get("user:" + userId + ":graph-features", "mutual:" + author);
            return value == null ? 0L : Long.parseLong(value);
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private double hotScore(Long postId) {
        try {
            Double score = redisTemplate.opsForZSet().score("feed:hot-posts", postId.toString());
            return score == null ? 0.0 : score;
        } catch (RuntimeException ignored) {
            return 0.0;
        }
    }

    private int parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(cursor));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static MediaAssetView image(long id, String objectKey, String url, int width, int height) {
        return new MediaAssetView(id, objectKey, url, "image/webp", 128000, width, height, null, "PENDING_REVIEW");
    }

    private static MediaAssetView video(long id, String objectKey, String url, int width, int height, int durationSeconds) {
        return new MediaAssetView(id, objectKey, url, "video/mp4", 1200000, width, height, durationSeconds, "PENDING_REVIEW");
    }
}
