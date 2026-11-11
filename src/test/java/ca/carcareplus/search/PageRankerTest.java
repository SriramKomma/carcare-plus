package ca.carcareplus.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.SearchRequest;
import ca.carcareplus.models.SearchResult;
import ca.carcareplus.models.ServiceCategory;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.models.VehicleType;

/** Scores in these tests were worked out by hand from the formula in PageRanker. */
class PageRankerTest {

    private static ServiceDocument doc(int id, String path, String title, String description,
                                       List<String> headings, String body) {
        ServiceDocument doc = new ServiceDocument("https://www.example.ca/" + path, "Example");
        doc.setId(id);
        doc.setTitle(title);
        doc.setDescription(description);
        doc.setHeadings(headings);
        doc.setBodyText(body);
        return doc;
    }

    private static SearchRequest request(String query, ServiceCategory category, String location) {
        return new SearchRequest(query, VehicleType.GAS, category, location, 10);
    }

    private static List<String> urls(List<SearchResult> results) {
        return results.stream().map(r -> r.document().getUrl().replace("https://www.example.ca/", "")).toList();
    }

    // Invented test documents
    private final ServiceDocument batteryPage = doc(0, "battery-windsor", "Battery replacement", "Car battery service",
            List.of("Battery testing"), "We replace your battery fast. Battery replacement in Windsor.");
    private final ServiceDocument brakePage = doc(1, "brakes", "Brake service", "", List.of(),
            "Battery check with every brake service.");
    private final ServiceDocument shortPage = doc(2, "battery", "Battery", "", List.of(), "");

    private final InvertedIndex index;
    private final PageRanker ranker;

    PageRankerTest() {
        batteryPage.setProvince("ON");
        batteryPage.setPostalCodes(List.of("N9B 3P4"));
        batteryPage.addCategory(ServiceCategory.BATTERY);
        index = InvertedIndex.of(List.of(batteryPage, brakePage, shortPage));
        ranker = new PageRanker(index);
    }

    private List<SearchResult> rank(String query, ServiceCategory category, String location) {
        List<String> terms = Tokenizer.tokenize(query);
        return ranker.rank(index.lookupAny(terms), terms, request(query, category, location), 10);
    }

    // ------------------------------------------------------- hand-computed

    @Test
    void scoresMatchHandCalculation() {
        // battery page, query "battery replacement":
        //   battery:     title 1, headings 1, description 1, body 2
        //   replacement: title 1,                            body 1   ("replace" is a different word)
        //   title 2x5=10 + headings 1x3=3 + description 1x2=2 + body 3x1=3 + phrase 10 = 28
        // short page:  title "Battery" -> 1x5 = 5
        // brake page:  body "Battery"  -> 1x1 = 1
        List<SearchResult> results = rank("battery replacement", null, "");

        assertEquals(List.of("battery-windsor", "battery", "brakes"), urls(results));
        assertEquals(List.of(28.0, 5.0, 1.0), results.stream().map(SearchResult::score).toList());
        assertEquals("title 2x5 + headings 1x3 + description 1x2 + body 3x1 + phrase +10 = 28",
                results.get(0).scoreBreakdown());
        assertEquals("title 1x5 = 5", results.get(1).scoreBreakdown());
        assertEquals("body 1x1 = 1", results.get(2).scoreBreakdown());
    }

    @Test
    void termCountsAreGivenPerWordInQueryOrder() {
        SearchResult top = rank("replacement battery", null, "").get(0);
        assertEquals(List.of("replacement", "battery"), List.copyOf(top.termCounts().keySet()));
        assertEquals(Map.of("replacement", 2, "battery", 5), top.termCounts());
    }

    @Test
    void phraseBonusOnlyWhenWordsAreAdjacentInOrder() {
        // "replacement battery" is not a phrase on the page -> 28 - 10 = 18
        assertEquals(18.0, rank("replacement battery", null, "").get(0).score());
    }

    @Test
    void oneWordQueryNeverGetsPhraseBonus() {
        assertEquals("title 1x5 = 5", rank("battery", null, "").stream()
                .filter(r -> r.document() == shortPage).findFirst().orElseThrow().scoreBreakdown());
    }

    @Test
    void categoryAndLocationBonuses() {
        SearchResult top = rank("battery replacement", ServiceCategory.BATTERY, "Windsor").get(0);
        assertEquals(28 + 5 + 3, top.score());
        assertTrue(top.scoreBreakdown().endsWith("phrase +10 + category +5 + location +3 = 36"), top.scoreBreakdown());
    }

    @Test
    void wrongCategoryOrPlaceGivesNoBonus() {
        assertEquals(28.0, rank("battery replacement", ServiceCategory.BRAKES, "Calgary").get(0).score());
    }

    @Test
    void wordTypedTwiceCountsOnce() {
        // short page has "battery" once in the title: 1x5 = 5, not 2x5 = 10
        SearchResult result = ranker.rank(List.of(2), List.of("battery", "battery"),
                request("battery battery", null, ""), 10).get(0);
        assertEquals(5.0, result.score());
        assertEquals(Map.of("battery", 1), result.termCounts());
    }

    // ---------------------------------------------------------------- ties

    @Test
    void equalScoresAreBrokenByTitleHitsThenUrl() {
        InvertedIndex tieIndex = InvertedIndex.of(List.of(
                doc(0, "z-body", "Shop", "", List.of(), "wiper wiper wiper wiper wiper"), // body 5x1 = 5
                doc(1, "y-title", "Wiper", "", List.of(), ""),                            // title 1x5 = 5
                doc(2, "b-title", "Wiper", "", List.of(), ""),                            // title 1x5 = 5
                doc(3, "a-body", "Shop", "", List.of(), "wiper wiper wiper wiper wiper"))); // body 5x1 = 5
        List<SearchResult> results = new PageRanker(tieIndex)
                .rank(List.of(0, 1, 2, 3), List.of("wiper"), request("wiper", null, ""), 10);

        assertEquals(List.of("b-title", "y-title", "a-body", "z-body"), urls(results));
    }

    // ------------------------------------------------------------ edge cases

    @Test
    void noCandidatesGivesEmptyList() {
        assertEquals(List.of(), ranker.rank(List.of(), List.of("alternator"), request("alternator", null, ""), 10));
    }

    @Test
    void unknownIdsAreIgnored() {
        assertEquals(1, ranker.rank(List.of(2, 99), List.of("battery"), request("battery", null, ""), 10).size());
    }

    @Test
    void kLimitsTheNumberOfResults() {
        assertEquals(1, ranker.rank(List.of(0, 1, 2), List.of("battery"), request("battery", null, ""), 1).size());
    }

    @Test
    void missingArgumentsAreRejected() {
        assertThrows(InvalidInputException.class, () -> new PageRanker(null));
        assertThrows(InvalidInputException.class, () -> ranker.rank(null, List.of("x"), request("x", null, ""), 1));
    }

    // ---------------------------------------------------------- large input

    @Test
    void tenThousandSyntheticDocsGiveExactlyKSortedAndSameAsFullSort() {
        // Synthetic documents (not real pages) with random numbers of "battery"
        Random random = new Random(7);
        List<ServiceDocument> docs = new ArrayList<>();
        for (int id = 0; id < 10_000; id++) {
            String body = "battery ".repeat(random.nextInt(20)) + "shop";
            String title = random.nextBoolean() ? "Battery shop" : "Shop";
            docs.add(doc(id, "doc" + id, title, "", List.of(), body));
        }
        InvertedIndex big = InvertedIndex.of(docs);
        PageRanker bigRanker = new PageRanker(big);
        List<String> terms = List.of("battery");
        SearchRequest req = request("battery", null, "");

        List<SearchResult> heap = bigRanker.rank(big.lookup("battery"), terms, req, 10);
        List<SearchResult> sorted = bigRanker.rankWithFullSort(big.lookup("battery"), terms, req, 10);

        assertEquals(10, heap.size());
        for (int i = 1; i < heap.size(); i++) {
            assertTrue(heap.get(i - 1).score() >= heap.get(i).score(), "scores must not increase");
        }
        assertEquals(urls(sorted), urls(heap));
    }
}
