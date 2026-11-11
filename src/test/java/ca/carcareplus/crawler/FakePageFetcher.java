package ca.carcareplus.crawler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ca.carcareplus.crawler.FetchResult.Outcome;

/**
 * Test double for PageFetcher: returns prepared responses and records every request.
 * Any URL without a prepared response answers HTTP 404 (so robots.txt is "missing" by default).
 */
public class FakePageFetcher implements PageFetcher {

    private final Map<String, FetchResult> responses = new HashMap<>();
    private final List<String> requested = new ArrayList<>();

    public FakePageFetcher html(String url, String body) {
        responses.put(url, FetchResult.ok(url, url, 200, "text/html; charset=UTF-8", body));
        return this;
    }

    public FakePageFetcher status(String url, int status) {
        responses.put(url, FetchResult.httpError(url, url, status, "text/html"));
        return this;
    }

    public FakePageFetcher failure(String url, Outcome outcome) {
        responses.put(url, FetchResult.failure(url, outcome, "simulated " + outcome));
        return this;
    }

    public FakePageFetcher response(String url, FetchResult result) {
        responses.put(url, result);
        return this;
    }

    @Override
    public FetchResult fetch(String url) {
        requested.add(url);
        return responses.getOrDefault(url, FetchResult.httpError(url, url, 404, "text/html"));
    }

    /** Every URL requested, in order, including robots.txt. */
    public List<String> requested() {
        return requested;
    }

    /** Requested URLs except robots.txt files. */
    public List<String> requestedPages() {
        List<String> pages = new ArrayList<>();
        for (String url : requested) {
            if (!url.endsWith("/robots.txt")) {
                pages.add(url);
            }
        }
        return pages;
    }
}
