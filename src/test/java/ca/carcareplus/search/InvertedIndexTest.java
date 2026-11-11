package ca.carcareplus.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.search.InvertedIndex.Field;

class InvertedIndexTest {

    private InvertedIndex index;

    /** Invented test document. */
    private static ServiceDocument doc(int id, String title, String description, List<String> headings, String body) {
        ServiceDocument doc = new ServiceDocument("https://www.example.ca/page" + id, "Example");
        doc.setId(id);
        doc.setTitle(title);
        doc.setDescription(description);
        doc.setHeadings(headings);
        doc.setBodyText(body);
        return doc;
    }

    @BeforeEach
    void setUp() {
        index = InvertedIndex.of(List.of(
                // tokens: battery(0) service(1) | battery(2) testing(3) | brakes(4) | battery(5) brake(6) pads(7) rotors(8)
                doc(0, "Battery service", "Battery testing", List.of("Brakes"), "Battery, brake pads and rotors"),
                doc(1, "Brake service", "", List.of(), "Brake pads replaced. Tyres too."),
                doc(2, "EV charging", "", List.of("Level 2"), "350 kW chargers")));
    }

    // ------------------------------------------------------------- lookup

    @Test
    void lookupFindsDocumentsContainingTerm() {
        assertEquals(Set.of(0), index.lookup("battery"));
        assertEquals(Set.of(0, 1), index.lookup("pads"));
        assertEquals(Set.of(0, 1), index.lookup("service"));
    }

    @Test
    void lookupIsCaseInsensitiveAndUsesSynonyms() {
        assertEquals(Set.of(0), index.lookup("BATTERY"));
        assertEquals(Set.of(1), index.lookup("tires"), "page says 'Tyres'");
        assertEquals(Set.of(1), index.lookup("Tyres"));
    }

    @Test
    void missingTermOrStopWordGivesEmptySet() {
        assertEquals(Set.of(), index.lookup("alternator"));
        assertEquals(Set.of(), index.lookup("and"));
        assertEquals(Set.of(), index.lookup(""));
        assertEquals(Set.of(), index.lookup(null));
    }

    @Test
    void lookupAllIntersectsAndLookupAnyUnites() {
        assertEquals(Set.of(0, 1), index.lookupAll(List.of("brake", "pads")));
        assertEquals(Set.of(0), index.lookupAll(List.of("battery", "pads")));
        assertEquals(Set.of(), index.lookupAll(List.of("battery", "kw")));
        assertEquals(Set.of(0, 2), index.lookupAny(List.of("battery", "kw")));
        assertEquals(Set.of(), index.lookupAll(List.of()));
    }

    // ---------------------------------------------------- positions & counts

    @Test
    void positionsAreStoredInOrderAcrossFields() {
        assertEquals(List.of(0, 2, 5), index.positions("battery", 0));
        assertEquals(List.of(), index.positions("battery", 1));
        assertEquals(List.of(), index.positions("battery", 99));
    }

    @Test
    void termFrequencyCountsRepeatedTerms() {
        assertEquals(3, index.termFrequency("battery", 0));
        assertEquals(2, index.termFrequency("brake", 1));
        assertEquals(0, index.termFrequency("battery", 2));
    }

    @Test
    void termFrequencyPerField() {
        assertEquals(1, index.termFrequency("battery", 0, Field.TITLE));
        assertEquals(1, index.termFrequency("battery", 0, Field.DESCRIPTION));
        assertEquals(0, index.termFrequency("battery", 0, Field.HEADINGS));
        assertEquals(1, index.termFrequency("battery", 0, Field.BODY));
        assertEquals(1, index.termFrequency("brakes", 0, Field.HEADINGS));
    }

    @Test
    void emptyFieldsDoNotConfuseFieldBoundaries() {
        // doc 1 has no description and no headings: "brake" at 0 is title, at 2 is body
        assertEquals(1, index.termFrequency("brake", 1, Field.TITLE));
        assertEquals(0, index.termFrequency("brake", 1, Field.DESCRIPTION));
        assertEquals(1, index.termFrequency("brake", 1, Field.BODY));
    }

    @Test
    void documentAndTotalFrequency() {
        assertEquals(2, index.documentFrequency("brake"));
        assertEquals(4, index.totalFrequency("brake"), "doc 0 'Brakes' + 'brake', doc 1 'Brake' x2");
        assertEquals(0, index.documentFrequency("alternator"));
        assertEquals(0, index.totalFrequency("alternator"));
    }

    // ------------------------------------------------------------- phrases

    @Test
    void phraseMustBeAdjacentAndInOrder() {
        assertTrue(index.containsPhrase(0, List.of("brake", "pads")));
        assertTrue(index.containsPhrase(1, List.of("brake", "pads", "replaced")));
        assertFalse(index.containsPhrase(0, List.of("pads", "brake")), "wrong order");
        assertFalse(index.containsPhrase(0, List.of("battery", "rotors")), "not adjacent");
        assertFalse(index.containsPhrase(2, List.of("brake", "pads")), "terms missing");
        assertFalse(index.containsPhrase(0, List.of()));
    }

    @Test
    void phraseDoesNotCrossFieldBoundaries() {
        // doc 0: title ends with "service", description starts with "battery"
        assertFalse(index.containsPhrase(0, List.of("service", "battery")));
    }

    @Test
    void stopWordsBetweenTermsAreIgnoredInPhrases() {
        // body "brake pads and rotors": "and" is not indexed, so "pads rotors" is adjacent
        assertTrue(index.containsPhrase(0, List.of("pads", "rotors")));
    }

    // ------------------------------------------------------- add / remove

    @Test
    void removeDocumentDeletesItsPostingsAndUnusedTerms() {
        assertTrue(index.removeDocument(2));
        assertEquals(Set.of(), index.lookup("kw"));
        assertFalse(index.vocabulary().contains("kw"));
        assertEquals(2, index.documentCount());
        assertFalse(index.removeDocument(2), "already removed");
    }

    @Test
    void removingOneDocumentKeepsTermsOfOthers() {
        index.removeDocument(1);
        assertEquals(Set.of(0), index.lookup("pads"));
    }

    @Test
    void reAddingSameIdReplacesInsteadOfDoubleCounting() {
        index.addDocument(doc(1, "Brake service", "", List.of(), "Brake pads replaced. Tyres too."));
        assertEquals(2, index.termFrequency("brake", 1));

        index.addDocument(doc(1, "Wipers", "", List.of(), "New wiper blades"));
        assertEquals(Set.of(0), index.lookup("brake"));
        assertEquals(Set.of(1), index.lookup("wiper"));
    }

    @Test
    void emptyDocumentIsIndexedWithoutTerms() {
        index.addDocument(doc(7, "", "", List.of(), ""));
        assertEquals(4, index.documentCount());
        assertEquals(0, index.documentLength(7));
    }

    @Test
    void documentWithoutIdIsRejected() {
        ServiceDocument noId = new ServiceDocument("https://www.example.ca/x", "Example");
        assertThrows(InvalidInputException.class, () -> index.addDocument(noId));
        assertThrows(InvalidInputException.class, () -> index.addDocument(null));
    }

    // ------------------------------------------------------- document info

    @Test
    void documentInfoByIdAndUrl() {
        assertEquals("EV charging", index.document(2).getTitle());
        assertNull(index.document(42));
        assertEquals(2, index.idOfUrl("https://www.example.ca/page2"));
        assertNull(index.idOfUrl("https://www.example.ca/nope"));
        assertNull(index.idOfUrl(null));
        assertEquals(9, index.documentLength(0));
    }

    @Test
    void vocabularyIsSortedAndReadOnly() {
        Set<String> vocabulary = index.vocabulary();
        assertEquals("2", vocabulary.iterator().next(), "digits sort before letters");
        assertTrue(vocabulary.containsAll(Set.of("battery", "brake", "tire", "kw")));
        assertFalse(vocabulary.contains("brakes"), "plurals are stored in singular form");
        assertThrows(UnsupportedOperationException.class, () -> vocabulary.add("x"));
    }

    @Test
    void indexKeepsTheSameDocumentObject() {
        ServiceDocument doc = doc(9, "Wipers", "", List.of(), "");
        index.addDocument(doc);
        assertSame(doc, index.document(9));
    }
}
