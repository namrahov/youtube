package az.youtube.controller;

import az.youtube.dto.response.SearchResponse;
import az.youtube.search.SearchService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public: search results are public videos, no user header needed. */
@RestController
public class SearchController {

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping("/api/search")
    public SearchResponse search(@RequestParam("q") String query,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return search.search(query, page, size);
    }
}
