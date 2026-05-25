package io.github.devcircle.search.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.api.PageResponse;
import io.github.devcircle.common.model.PostSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/search")
class SearchController {
    @GetMapping("/posts")
    ApiResponse<PageResponse<PostSummary>> posts(@RequestParam String keyword,
                                                 @RequestParam(required = false) String tag,
                                                 @RequestParam(defaultValue = "relevance") String sort) {
        var result = new PostSummary(3001L, "搜索：" + keyword, "Elasticsearch 索引映射、中文分词和标签过滤示例。", "search-bot", List.of(tag == null ? "搜索" : tag), 88, 12, 2600, "PUBLISHED", Instant.now());
        return ApiResponse.ok(PageResponse.of(List.of(result), 1, 1, 10));
    }
}

