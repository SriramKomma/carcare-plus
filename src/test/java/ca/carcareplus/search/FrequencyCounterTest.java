package ca.carcareplus.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.ServiceDocument;

class FrequencyCounterTest {

    private static final String URL_A = "https://www.example.ca/a";
    private static final String URL_B = "https://www.example.ca/b";

    private static ServiceDocument doc(int id, String url, String title, String body) {
        ServiceDocument doc = new ServiceDocument(url, "Example");
        doc.setId(id);
        doc.setTitle(title);
        doc.setBodyText(body);
        return doc;
    }

    private final FrequencyCounter counter = new FrequencyCounter(InvertedIndex.of(List.of(
            doc(0, URL_A, "Battery service", "Battery testing. New battery. Brake check. Tyre check."),
            doc(1, URL_B, "Brakes", "Brake pads"))));

    @Test
    void countsWordOnSpecificPage() {
        assertEquals(3, counter.countInDocument("battery", URL_A)); // title 1 + body 2
        assertEquals(0, counter.countInDocument("battery", URL_B));
        assertEquals(1, counter.countInDocument("BRAKE", URL_A));
        assertEquals(1, counter.countInDocument("tire", URL_A), "synonym: page says 'Tyre'");
        assertEquals(0, counter.countInDocument("tires", URL_A), "no stemming: plural is a different term");
    }

    @Test
    void unknownWordOrStopWordCountsZero() {
        assertEquals(0, counter.countInDocument("alternator", URL_A));
        assertEquals(0, counter.countInDocument("the", URL_A));
    }

    @Test
    void unknownUrlIsAClearError() {
        InvalidInputException e = assertThrows(InvalidInputException.class,
                () -> counter.countInDocument("battery", "https://www.example.ca/nope"));
        assertTrue(e.getMessage().contains("No indexed page has the URL"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"battery", "brake", "check", "service", "tire", "alternator", "the"})
    void scanningGivesTheSameAnswerAsTheIndex(String word) {
        assertEquals(counter.countInDocument(word, URL_A), counter.countByScanning(word, URL_A));
        assertEquals(counter.countInDocument(word, URL_B), counter.countByScanning(word, URL_B));
    }

    @Test
    void topTermsMostFrequentFirstTiesAlphabetical() {
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("battery", 3);
        expected.put("check", 2);
        expected.put("brake", 1);
        assertEquals(expected, counter.topTerms(URL_A, 3));
        assertEquals(List.copyOf(expected.keySet()), List.copyOf(counter.topTerms(URL_A, 3).keySet()), "order");
    }

    @Test
    void topTermsWithLargeKReturnsAllWords() {
        assertEquals(3, counter.topTerms(URL_B, 50).size()); // brakes, brake, pads
        assertEquals(Map.of(), counter.topTerms(URL_B, 0));
    }

    @Test
    void nullIndexIsRejected() {
        assertThrows(InvalidInputException.class, () -> new FrequencyCounter(null));
    }
}
