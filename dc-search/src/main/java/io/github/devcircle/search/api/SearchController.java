package io.github.devcircle.search.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.search.application.SearchService;
import io.github.devcircle.search.application.SearchReindexJobs;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import java.util.UUID;
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
    private final SearchReindexJobs reindexJobs;

    SearchController(SearchService searchService, SearchReindexJobs reindexJobs) {
        this.searchService = searchService;
        this.reindexJobs = reindexJobs;
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
        return ApiResponse.ok(new ReindexResult(reindexJobs.legacyReindex()));
    }

    @PutMapping("/admin/reindex/jobs/{id}")
    ApiResponse<SearchReindexJobs.Job> start(@PathVariable UUID id, @RequestParam boolean maintenanceConfirmed) {
        return ApiResponse.ok(reindexJobs.start(id, maintenanceConfirmed));
    }

    @GetMapping("/admin/reindex/jobs/{id}")
    ApiResponse<SearchReindexJobs.Job> status(@PathVariable UUID id) {
        return ApiResponse.ok(reindexJobs.get(id));
    }

    @PostMapping("/admin/reindex/jobs/{id}/step")
    ApiResponse<SearchReindexJobs.Job> step(@PathVariable UUID id, @RequestParam long revision) {
        return ApiResponse.ok(reindexJobs.step(id, revision));
    }

    @PostMapping("/admin/reindex/jobs/{id}/cancel")
    ApiResponse<SearchReindexJobs.Job> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(reindexJobs.cancel(id));
    }

    record ReindexResult(int indexed) {
    }
}
