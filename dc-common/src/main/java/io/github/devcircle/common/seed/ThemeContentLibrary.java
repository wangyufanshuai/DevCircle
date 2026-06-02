package io.github.devcircle.common.seed;

import io.github.devcircle.common.model.MediaAssetView;
import io.github.devcircle.common.model.PostSummary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ThemeContentLibrary {
    private static final int DEDUCTION_COUNT = 100;
    private static final int OTHER_THEME_COUNT = 40;
    private static final List<ThemeSeedPost> POSTS = buildPosts();

    private ThemeContentLibrary() {
    }

    public static List<ThemeSeedPost> posts() {
        return POSTS;
    }

    public static List<PostSummary> summaries() {
        return POSTS.stream().map(ThemeSeedPost::toSummary).toList();
    }

    public static Optional<ThemeSeedPost> find(Long id) {
        return POSTS.stream().filter(post -> post.id().equals(id)).findFirst();
    }

    public static List<PostSummary> related(Long id, int size) {
        ThemeSeedPost current = find(id).orElse(POSTS.getFirst());
        return POSTS.stream()
                .filter(post -> !post.id().equals(id))
                .filter(post -> post.theme().equals(current.theme()) || post.tags().stream().anyMatch(current.tags()::contains))
                .sorted(Comparator.comparingLong(ThemeSeedPost::likes).reversed())
                .limit(Math.max(1, size))
                .map(ThemeSeedPost::toSummary)
                .toList();
    }

    public static List<PostSummary> search(String keyword, String theme, String contentKind, String difficulty) {
        String normalized = keyword == null ? "" : keyword.toLowerCase(Locale.ROOT);
        return POSTS.stream()
                .filter(post -> normalized.isBlank()
                        || post.title().toLowerCase(Locale.ROOT).contains(normalized)
                        || post.excerpt().toLowerCase(Locale.ROOT).contains(normalized)
                        || post.author().toLowerCase(Locale.ROOT).contains(normalized)
                        || post.tags().stream().anyMatch(tag -> tag.toLowerCase(Locale.ROOT).contains(normalized))
                        || post.clues().stream().anyMatch(clue -> clue.toLowerCase(Locale.ROOT).contains(normalized)))
                .filter(post -> theme == null || theme.isBlank() || post.theme().equalsIgnoreCase(theme))
                .filter(post -> contentKind == null || contentKind.isBlank() || post.contentKind().equalsIgnoreCase(contentKind))
                .filter(post -> difficulty == null || difficulty.isBlank() || post.difficulty().equalsIgnoreCase(difficulty))
                .map(ThemeSeedPost::toSummary)
                .toList();
    }

    private static List<ThemeSeedPost> buildPosts() {
        List<ThemeSeedPost> posts = new ArrayList<>();
        for (int i = 0; i < DEDUCTION_COUNT; i++) {
            posts.add(deduction(i));
        }
        for (int i = 0; i < OTHER_THEME_COUNT; i++) {
            posts.add(scifi(i));
            posts.add(cosmos(i));
        }
        return List.copyOf(posts);
    }

    private static ThemeSeedPost deduction(int index) {
        DeductionTemplate template = deductionTemplate(index);
        long id = 1001L + index;
        String contentKind = index % 3 == 1 ? "PUZZLE" : "CASE_FILE";
        int solved = 18 + index;
        String title = template.place() + template.hook();
        List<String> tags = List.of("推理", template.series(), template.coreMethod(), contentKind.equals("PUZZLE") ? "逻辑谜题" : "案件档案");
        List<String> clues = List.of(
                template.objectClue(),
                template.timelineClue(),
                template.testimonyClue(),
                template.traceClue(),
                "第 " + ((index % 7) + 1) + " 份记录被人故意改写了顺序"
        );
        List<String> suspects = List.of(
                template.suspectA(),
                template.suspectB(),
                template.suspectC(),
                template.suspectD()
        );
        List<String> timeline = List.of(
                "22:" + minute(index, 11) + " 受害者最后一次公开露面",
                "23:" + minute(index, 23) + " 关键证人离开现场",
                "00:" + minute(index, 7) + " 现场出现第一次异常",
                "00:" + minute(index, 19) + " 巡查人员封锁入口",
                "01:" + minute(index, 31) + " 档案员发现记录被调换"
        );
        List<String> keywords = List.of(template.answerKeyword(), template.coreMethod(), template.secondaryKeyword(), template.objectKeyword());
        String question = "谁制造了关键的不在场证明？真正的手法和决定性证据分别是什么？";
        String explanation = "关键并不是最显眼的证词，而是“" + template.objectKeyword() + "”与“" + template.timelineClue()
                + "”之间的矛盾。凶手利用" + template.coreMethod() + "制造时间差，再用" + template.secondaryKeyword()
                + "掩盖行动路径；答案必须落到“" + template.answerKeyword() + "”。";
        return new ThemeSeedPost(id, "TEXT", "DEDUCTION", contentKind, difficulty(index), true, solved,
                title,
                "一桩围绕" + template.place() + "展开的" + template.series() + "推理内容，线索覆盖时间线、证词、物证和现场痕迹，适合训练排除误导证据的能力。",
                author(index),
                tags,
                List.of(),
                220 + index * 11L, 30 + index % 35, 5200 + index * 360L,
                Instant.now().minusSeconds(1500L * (index + 1)),
                "可解谜案件，已有 " + solved + " 人完成推理；核心手法：" + template.coreMethod(),
                clues, suspects, timeline, question, keywords, explanation,
                List.of(), List.of(), relatedIds(id, DEDUCTION_COUNT));
    }

    private static DeductionTemplate deductionTemplate(int index) {
        String[] places = {"雾港钟楼", "蓝线列车", "旧书店密室", "雪夜旅馆", "镜厅剧院", "孤岛灯塔", "地下档案室", "黑河码头", "红杉疗养院", "北境邮局"};
        String[] hooks = {"失踪案", "无声证词", "红色书签", "第十三间房", "倒影谋杀", "潮汐密室", "缺页卷宗", "午夜脚印", "匿名电报", "封存照片"};
        String[] series = {"密室", "时间线", "证词矛盾", "物证误导", "密码谜题", "航海", "列车", "剧院", "档案室", "冷案复盘"};
        String[] methods = {"夹层藏身", "调换时钟", "伪造脚印", "镜面反射", "潮汐延迟", "证词串供", "密码替换", "灯塔盲区", "列车换厢", "档案重排"};
        String[] objects = {"怀表", "航海日志", "湿鞋印", "红色书签", "破裂镜片", "煤油灯芯", "车票孔位", "锁孔铜屑", "相机底片", "电报时间戳"};
        String place = places[index % places.length];
        String method = methods[index % methods.length];
        String object = objects[index % objects.length];
        return new DeductionTemplate(
                place,
                hooks[index % hooks.length],
                series[index % series.length],
                method,
                object + "与公开记录的时间不一致",
                "时间线在 00:" + minute(index, 7) + " 到 00:" + minute(index, 19) + " 之间出现空档",
                "两名证人的描述都避开了" + place + "的后门",
                "现场只留下向内的擦痕，没有对应的离开痕迹",
                place + "管理员",
                "钟表匠许临",
                "记者白棠",
                "码头调度员周野",
                method,
                object,
                "时间差",
                object
        );
    }

    private static ThemeSeedPost scifi(int index) {
        String[] worlds = {"木卫二冰下城", "环土星共和国", "火星雨季穹顶", "近光速方舟", "仿生人记忆庭", "月背低温港", "金星云海电梯", "深空电台"};
        String[] kinds = {"设定集", "舰队识别图谱", "短篇", "殖民纪要", "技术白皮书", "派系档案", "城市地图", "通信残章"};
        String world = worlds[index % worlds.length];
        String kind = kinds[index % kinds.length];
        long id = 2001L + index;
        String type = index % 5 == 0 ? "VIDEO" : index % 3 == 0 ? "IMAGE" : "TEXT";
        return new ThemeSeedPost(id, type, "SCI_FI", index % 2 == 0 ? "WORLDBUILDING" : "FICTION", difficulty(index), false, 0,
                world + kind,
                "围绕" + world + "展开的科幻内容，包含社会结构、技术设定、人物冲突和可继续扩写的章节钩子。",
                author(index + 3),
                List.of("科幻", index % 2 == 0 ? "世界观" : "短篇", world.substring(0, Math.min(3, world.length()))),
                media(id, type, "scifi-" + index),
                210 + index * 8L, 18 + index, 4100 + index * 390L,
                Instant.now().minusSeconds(2200L * (index + 1)),
                index % 2 == 0 ? "世界观设定完整度高，适合系列共创" : "短篇阅读完成率高，近期讨论活跃",
                List.of(), List.of(), List.of(), "", List.of(), "",
                List.of("核心地点：" + world, "技术概念：潮汐能源、记忆备份或低温生态", "冲突来源：自治城市、航线垄断和身份边界", "可扩展方向：章节连载、派系年表、装备图谱"),
                List.of(), relatedIds(id, OTHER_THEME_COUNT));
    }

    private static ThemeSeedPost cosmos(int index) {
        String[] objects = {"银河系旋臂", "猎户座星云", "暗物质晕", "旅行者号航迹", "脉冲星灯塔", "系外行星海洋", "超新星遗迹", "柯伊伯带边界"};
        String[] angles = {"档案", "观测日志", "科普札记", "任务简报", "异常信号", "影像精选", "数据卡", "深空路线"};
        String object = objects[index % objects.length];
        String angle = angles[index % angles.length];
        long id = 3001L + index;
        String type = index % 4 == 0 ? "IMAGE" : index % 6 == 0 ? "VIDEO" : "TEXT";
        return new ThemeSeedPost(id, type, "COSMOS", index % 2 == 0 ? "COSMIC_ARTICLE" : "OBSERVATION_LOG", difficulty(index), false, 0,
                object + angle,
                "一份关于" + object + "的宇宙档案，整理观测背景、关键现象、科普解释和可视化阅读线索。",
                author(index + 6),
                List.of("宇宙", index % 2 == 0 ? "档案" : "观测", object.substring(0, Math.min(3, object.length()))),
                media(id, type, "cosmos-" + index),
                190 + index * 10L, 16 + index, 3800 + index * 430L,
                Instant.now().minusSeconds(2600L * (index + 1)),
                "宇宙档案热度上升，适合延伸阅读",
                List.of(), List.of(), List.of(), "", List.of(), "",
                List.of(),
                List.of("天体对象：" + object, "观测手段：光谱、引力透镜、射电或深空探测器", "核心问题：它如何改变我们对宇宙结构的理解", "延伸阅读：任务年表、图像档案和相关现象"),
                relatedIds(id, OTHER_THEME_COUNT));
    }

    private static List<MediaAssetView> media(long id, String type, String seed) {
        if ("IMAGE".equals(type)) {
            return List.of(new MediaAssetView(id, "demo/" + seed + ".webp", "https://picsum.photos/seed/" + seed + "/720/960", "image/webp", 128000, 720, 960, null, "PENDING_REVIEW"));
        }
        if ("VIDEO".equals(type)) {
            return List.of(new MediaAssetView(id, "demo/" + seed + ".mp4", "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4", "video/mp4", 1200000, 720, 1280, 18, "PENDING_REVIEW"));
        }
        return List.of();
    }

    private static String difficulty(int index) {
        return switch (index % 3) {
            case 0 -> "EASY";
            case 1 -> "MEDIUM";
            default -> "HARD";
        };
    }

    private static String author(int index) {
        List<String> authors = List.of("林墨", "顾白", "沈岚", "陈星", "许澜", "陆离", "王遥", "周望", "闻舟", "高远");
        return authors.get(index % authors.size());
    }

    private static String minute(int index, int seed) {
        return "%02d".formatted(Math.floorMod(index * 7 + seed, 60));
    }

    private static List<Long> relatedIds(long id, int count) {
        long base = id < 2000 ? 1001 : id < 3000 ? 2001 : 3001;
        long offset = (id - base) % count;
        return List.of(base + ((offset + 1) % count), base + ((offset + 7) % count), base + ((offset + 13) % count));
    }

    public static Map<String, Long> themeCounts() {
        return Map.of(
                "DEDUCTION", POSTS.stream().filter(post -> "DEDUCTION".equals(post.theme())).count(),
                "SCI_FI", POSTS.stream().filter(post -> "SCI_FI".equals(post.theme())).count(),
                "COSMOS", POSTS.stream().filter(post -> "COSMOS".equals(post.theme())).count()
        );
    }

    public static List<String> keywordsFromSeed(ThemeSeedPost seed) {
        return seed.answerKeywords();
    }

    private record DeductionTemplate(
            String place,
            String hook,
            String series,
            String coreMethod,
            String objectClue,
            String timelineClue,
            String testimonyClue,
            String traceClue,
            String suspectA,
            String suspectB,
            String suspectC,
            String suspectD,
            String answerKeyword,
            String secondaryKeyword,
            String objectKeyword,
            String unused
    ) {
    }
}
