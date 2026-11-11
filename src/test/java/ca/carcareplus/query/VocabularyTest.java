package ca.carcareplus.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ca.carcareplus.models.SearchRequest;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.models.VehicleType;
import ca.carcareplus.search.InvertedIndex;
import ca.carcareplus.search.SearchEngine;
import ca.carcareplus.search.SearchOutcome;
import ca.carcareplus.storage.DocumentStorage;

class VocabularyTest {

    @TempDir
    Path dir;

    /** Invented test document. */
    private static ServiceDocument doc(String path, String title, List<String> headings, String body) {
        ServiceDocument doc = new ServiceDocument("https://www.example.ca/" + path, "Example");
        doc.setTitle(title);
        doc.setDescription("Service in Windsor");
        doc.setHeadings(headings);
        doc.setBodyText(body);
        doc.setContentHash(path);
        return doc;
    }

    @Test
    void countsWordsInTextFiles() throws Exception {
        Files.writeString(dir.resolve("0.txt"), "Battery service\nBatteries and brakes");
        Files.writeString(dir.resolve("1.txt"), "Brake pads");
        Files.writeString(dir.resolve("notes.md"), "battery battery battery"); // not a .txt file

        assertEquals(Map.of("battery", 2, "service", 1, "brake", 2, "pad", 1), Vocabulary.fromTextFiles(dir));
    }

    @Test
    void missingFolderGivesEmptyVocabulary() throws Exception {
        assertEquals(Map.of(), Vocabulary.fromTextFiles(dir.resolve("nope")));
        assertEquals(Map.of(), Vocabulary.fromTextFiles(null));
    }

    @Test
    void textFilesAndIndexGiveTheSameVocabulary() throws Exception {
        DocumentStorage storage = new DocumentStorage(dir);
        storage.addIfNew(doc("a", "Battery replacement", List.of("Batteries", "Contact us"), "Call for battery tests."));
        storage.addIfNew(doc("b", "Brake service", List.of(), "Brake pads and rotors."));
        storage.saveAll();

        Map<String, Integer> fromFiles = Vocabulary.fromTextFiles(storage.textDir());
        Map<String, Integer> fromIndex = Vocabulary.fromIndex(InvertedIndex.of(storage.documents()));

        assertEquals(fromIndex, fromFiles);
        assertEquals(3, fromFiles.get("battery"));
    }

    @Test
    void spellCheckerPlugsIntoSearchEngine() {
        ServiceDocument battery = doc("a", "Battery replacement", List.of(), "New battery fitted.");
        battery.setId(0);
        InvertedIndex index = InvertedIndex.of(List.of(battery));
        SearchEngine engine = new SearchEngine(index, null, new SpellChecker(Vocabulary.fromIndex(index)));

        SearchOutcome outcome = engine.search(SearchRequest.of("battrey", VehicleType.GAS));

        assertEquals(Map.of("battrey", List.of("battery")), outcome.suggestions());
    }
}
