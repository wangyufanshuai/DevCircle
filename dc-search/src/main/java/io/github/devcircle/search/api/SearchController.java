package io.github.devcircle.search.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.search.application.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/search")
class SearchController {
    private final SearchService searchService;

    SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/posts")
    ApiResponse<?> posts(@RequestParam String keyword,
                         @RequestParam(required = false) String tag,
                         @RequestParam(defaultValue = "relevance") String sort,
                         @RequestParam(required = false) String theme,
                         @RequestParam(required = false) String contentKind,
                         @RequestParam(required = false) String difficulty,
                         @RequestParam(required = false) String cursor,
                         @RequestParam(defaultValue = "20") int size) {
        if (cursor != null) {
            SearchService.SearchSlice result = searchService.searchCursor(
                    keyword, tag, sort, theme, contentKind, difficulty, cursor, size);
            return ApiResponse.ok(result.records(), Map.of(
                    "nextCursor", result.nextCursor(), "hasMore", result.hasMore()));
        }
        return ApiResponse.ok(searchService.search(keyword, tag, sort, theme, contentKind, difficulty));
    }

    @PostMapping("/admin/reindex")
    ApiResponse<ReindexResult> reindex() {
        return ApiResponse.ok(new ReindexResult(searchService.reindexSeedPosts()));
    }

    record ReindexResult(int indexed) {
    }
}
