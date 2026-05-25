package io.github.devcircle.search.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.api.PageResponse;
import io.github.devcircle.common.model.PostSummary;
import io.github.devcircle.search.application.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
class SearchController {
    private final SearchService searchService;

    SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/posts")
    ApiResponse<PageResponse<PostSummary>> posts(@RequestParam String keyword,
                                                 @RequestParam(required = false) String tag,
                                                 @RequestParam(defaultValue = "relevance") String sort) {
        return ApiResponse.ok(searchService.search(keyword, tag, sort));
    }

    @PostMapping("/admin/reindex")
    ApiResponse<ReindexResult> reindex() {
        return ApiResponse.ok(new ReindexResult(searchService.reindexSeedPosts()));
    }

    record ReindexResult(int indexed) {
    }
}
