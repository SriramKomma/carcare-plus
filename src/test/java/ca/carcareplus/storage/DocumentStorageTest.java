package ca.carcareplus.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.StorageException;
import ca.carcareplus.models.ServiceCategory;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.models.VehicleType;

class DocumentStorageTest {

    @TempDir
    Path dir;

    private DocumentStorage storage;

    @BeforeEach
    void setUp() {
        storage = new DocumentStorage(dir);
    }

    /** A fully filled test document (invented data). */
    private static ServiceDocument doc(String path, String body) {
        ServiceDocument doc = new ServiceDocument("https://www.example.ca/" + path, "Example Tire");
        doc.setCrawledAt("2026-11-11T12:00:00Z");
        doc.setTitle("Title " + path);
        doc.setDescription("Brake & battery service <fast>");
        doc.setHeadings(List.of("Brakes", "Contact"));
        doc.setBodyText(body);
        doc.setLinks(List.of("https://www.example.ca/a", "https://www.example.ca/b"));
        doc.setPhones(List.of("(604) 555-0142"));
        doc.setEmails(List.of("info@example.ca"));
        doc.setPostalCodes(List.of("V2S 1A1"));
        doc.setAddress("123 Example Way");
        doc.setCity("Abbotsford");
        doc.setProvince("BC");
        doc.setCategories(EnumSet.of(ServiceCategory.BRAKES, ServiceCategory.BATTERY));
        doc.setVehicleTypes(EnumSet.of(VehicleType.EV, VehicleType.GAS));
        doc.setContentHash(body.isEmpty() ? "" : "hash-of-" + body);
        doc.setTestFixture(true);
        return doc;
    }

    private Path documentsFile() {
        return dir.resolve(DocumentStorage.DOCUMENTS_FILE);
    }

    private void writeDocumentsFile(String json) throws IOException {
        Files.writeString(documentsFile(), json, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ adding

    @Test
    void newDocumentsGetIdsZeroOneTwo() {
        assertEquals(DocumentStorage.AddResult.ADDED, storage.addIfNew(doc("a", "text a")));
        assertEquals(DocumentStorage.AddResult.ADDED, storage.addIfNew(doc("b", "text b")));
        assertEquals(DocumentStorage.AddResult.ADDED, storage.addIfNew(doc("c", "text c")));

        assertEquals(List.of(0, 1, 2), storage.documents().stream().map(ServiceDocument::getId).toList());
    }

    @Test
    void sameUrlIsSkipped() {
        storage.addIfNew(doc("a", "text a"));
        assertEquals(DocumentStorage.AddResult.DUPLICATE_URL, storage.addIfNew(doc("a", "different text")));
        assertEquals(1, storage.size());
    }

    @Test
    void sameTextUnderAnotherUrlIsSkipped() {
        storage.addIfNew(doc("stores/windsor", "same text"));
        assertEquals(DocumentStorage.AddResult.DUPLICATE_CONTENT,
                storage.addIfNew(doc("stores/windsor/index.html", "same text")));
        assertEquals(1, storage.size());
    }

    @Test
    void emptyPagesAreNotTreatedAsDuplicatesOfEachOther() {
        assertEquals(DocumentStorage.AddResult.ADDED, storage.addIfNew(doc("empty1", "")));
        assertEquals(DocumentStorage.AddResult.ADDED, storage.addIfNew(doc("empty2", "")));
    }

    @Test
    void storageReplacesAnyIdAlreadySetOnTheDocument() {
        ServiceDocument doc = doc("a", "text");
        doc.setId(99);
        storage.addIfNew(doc);
        assertEquals(0, doc.getId());
    }

    @Test
    void nullDocumentAndNullFolderAreRejected() {
        assertThrows(InvalidInputException.class, () -> storage.addIfNew(null));
        assertThrows(InvalidInputException.class, () -> new DocumentStorage(null));
    }

    @Test
    void documentsListIsReadOnly() {
        storage.addIfNew(doc("a", "text"));
        assertThrows(UnsupportedOperationException.class, () -> storage.documents().clear());
    }

    // --------------------------------------------------------- round trip

    @Test
    void saveThenLoadGivesBackEveryField() throws StorageException {
        ServiceDocument original = doc("stores/abbotsford", "Body text with \"quotes\", é and a\nnew line");
        storage.addIfNew(original);
        storage.saveAll();

        DocumentStorage reopened = new DocumentStorage(dir);
        List<ServiceDocument> loaded = reopened.loadAll();

        assertEquals(1, loaded.size());
        ServiceDocument copy = loaded.get(0);
        assertEquals(original.getId(), copy.getId());
        assertEquals(original.getUrl(), copy.getUrl());
        assertEquals(original.getSourceName(), copy.getSourceName());
        assertEquals(original.getCrawledAt(), copy.getCrawledAt());
        assertEquals(original.getTitle(), copy.getTitle());
        assertEquals(original.getDescription(), copy.getDescription());
        assertEquals(original.getHeadings(), copy.getHeadings());
        assertEquals(original.getBodyText(), copy.getBodyText());
        assertEquals(original.getLinks(), copy.getLinks());
        assertEquals(original.getPhones(), copy.getPhones());
        assertEquals(original.getEmails(), copy.getEmails());
        assertEquals(original.getPostalCodes(), copy.getPostalCodes());
        assertEquals(original.getAddress(), copy.getAddress());
        assertEquals(original.getCity(), copy.getCity());
        assertEquals(original.getProvince(), copy.getProvince());
        assertEquals(original.getCategories(), copy.getCategories());
        assertEquals(original.getVehicleTypes(), copy.getVehicleTypes());
        assertEquals(original.getContentHash(), copy.getContentHash());
        assertTrue(copy.isTestFixture());
    }

    @Test
    void jsonIsReadableAndKeepsSpecialCharacters() throws Exception {
        storage.addIfNew(doc("a", "text"));
        storage.saveAll();
        String json = Files.readString(documentsFile());

        assertTrue(json.contains("\"formatVersion\": 1"), json);
        assertTrue(json.contains("Brake & battery service <fast>"), "no \\u0026 escaping");
        assertTrue(json.contains("\"BRAKES\""));
        assertFalse(Files.exists(dir.resolve(DocumentStorage.DOCUMENTS_FILE + ".tmp")), "temp file moved away");
    }

    @Test
    void duplicatesAreStillDetectedAfterReloading() throws StorageException {
        storage.addIfNew(doc("a", "text a"));
        storage.saveAll();

        DocumentStorage reopened = new DocumentStorage(dir);
        reopened.loadAll();
        assertEquals(DocumentStorage.AddResult.DUPLICATE_URL, reopened.addIfNew(doc("a", "new")));
        assertEquals(DocumentStorage.AddResult.DUPLICATE_CONTENT, reopened.addIfNew(doc("other", "text a")));
        assertEquals(DocumentStorage.AddResult.ADDED, reopened.addIfNew(doc("b", "text b")));
        assertEquals(1, reopened.documents().get(1).getId(), "ids continue after the highest saved id");
    }

    @Test
    void loadAllReplacesWhatIsInMemory() throws StorageException {
        storage.addIfNew(doc("a", "text a"));
        storage.saveAll();
        storage.addIfNew(doc("b", "text b")); // not saved

        assertEquals(1, storage.loadAll().size());
    }

    // --------------------------------------------------------- text files

    @Test
    void writesOneTextFilePerDocument() throws Exception {
        storage.addIfNew(doc("a", "Body of a"));
        storage.addIfNew(doc("b", "Body of b"));
        storage.saveAll();

        String text = Files.readString(storage.textFile(0));
        assertEquals(String.join(System.lineSeparator(),
                "Title a", "Brake & battery service <fast>", "Brakes", "Contact", "Body of a")
                + System.lineSeparator(), text);
        assertTrue(Files.exists(storage.textFile(1)));
    }

    @Test
    void emptyFieldsAreLeftOutOfTextFile() {
        ServiceDocument doc = new ServiceDocument("https://www.example.ca/x", "Example");
        doc.setBodyText("Only body");
        assertEquals("Only body" + System.lineSeparator(), DocumentStorage.plainText(doc));
    }

    @Test
    void staleTextFilesAreDeletedButOtherFilesAreKept() throws Exception {
        Files.createDirectories(storage.textDir());
        Files.writeString(storage.textDir().resolve("7.txt"), "old document");
        Files.writeString(storage.textDir().resolve("notes.md"), "my notes");

        storage.addIfNew(doc("a", "text"));
        storage.saveAll();

        assertFalse(Files.exists(storage.textDir().resolve("7.txt")));
        assertTrue(Files.exists(storage.textDir().resolve("notes.md")));
        assertTrue(Files.exists(storage.textFile(0)));
    }

    // ------------------------------------------------------ missing / damaged

    @Test
    void missingFileGivesEmptyStorage() throws StorageException {
        assertEquals(List.of(), storage.loadAll());
        assertEquals(0, storage.size());
    }

    @Test
    void savingCreatesMissingFolders() throws StorageException {
        DocumentStorage nested = new DocumentStorage(dir.resolve("data").resolve("parsed-pages"));
        nested.addIfNew(doc("a", "text"));
        nested.saveAll();
        assertTrue(Files.exists(dir.resolve("data/parsed-pages/documents.json")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{ \"formatVersion\": 1, \"documents\": [ ",  // cut off half-way
        "this is not json",
        "[1, 2, 3]",                                    // valid JSON, wrong shape
        "{ \"formatVersion\": 1 } trailing junk",
        ""
    })
    void damagedFileGivesClearStorageException(String content) throws IOException {
        writeDocumentsFile(content);
        StorageException e = assertThrows(StorageException.class, () -> storage.loadAll());
        assertTrue(e.getMessage().contains("cannot be loaded"), e.getMessage());
        assertTrue(e.getMessage().contains("Delete the file and run the parser again"), e.getMessage());
    }

    @Test
    void otherFormatVersionIsRejected() throws IOException {
        writeDocumentsFile("{ \"formatVersion\": 2, \"documents\": [] }");
        StorageException e = assertThrows(StorageException.class, () -> storage.loadAll());
        assertTrue(e.getMessage().contains("format version is 2"), e.getMessage());
    }

    @Test
    void fileWithoutDocumentsListIsEmpty() throws Exception {
        writeDocumentsFile("{ \"formatVersion\": 1 }");
        assertEquals(List.of(), storage.loadAll());
    }

    @Test
    void badEntriesAreSkippedAndGoodOnesKept() throws Exception {
        writeDocumentsFile("""
                { "formatVersion": 1, "documents": [
                  { "id": 0, "url": "https://www.example.ca/good", "title": "Good" },
                  { "id": 1, "title": "No URL" },
                  null,
                  { "id": 0, "url": "https://www.example.ca/same-id" },
                  { "id": 2, "url": "https://www.example.ca/good" },
                  { "id": -5, "url": "https://www.example.ca/negative-id" },
                  { "id": 3, "url": "https://www.example.ca/also-good" }
                ] }""");

        List<ServiceDocument> loaded = storage.loadAll();
        assertEquals(List.of("https://www.example.ca/good", "https://www.example.ca/also-good"),
                loaded.stream().map(ServiceDocument::getUrl).toList());
    }

    @Test
    void missingFieldsBecomeEmptyNotNull() throws Exception {
        writeDocumentsFile("""
                { "formatVersion": 1, "documents": [
                  { "id": 4, "url": "https://www.example.ca/minimal", "title": null, "headings": null }
                ] }""");

        ServiceDocument doc = storage.loadAll().get(0);
        assertEquals("", doc.getTitle());
        assertEquals(List.of(), doc.getHeadings());
        assertEquals("unknown", doc.getSourceName());
        assertEquals(Set.of(), doc.getCategories());
        assertFalse(doc.isTestFixture());
    }

    @Test
    void unknownCategoryNamesAreIgnored() throws Exception {
        writeDocumentsFile("""
                { "formatVersion": 1, "documents": [
                  { "id": 0, "url": "https://www.example.ca/x",
                    "categories": ["BRAKES", "WHEELS", "battery", null],
                    "vehicleTypes": ["EV", "HOVERCRAFT"] }
                ] }""");

        ServiceDocument doc = storage.loadAll().get(0);
        assertEquals(EnumSet.of(ServiceCategory.BRAKES, ServiceCategory.BATTERY), doc.getCategories());
        assertEquals(Set.of(VehicleType.EV), doc.getVehicleTypes());
    }

    @Test
    void entriesWithoutIdAreNumberedAfterTheHighestId() throws Exception {
        writeDocumentsFile("""
                { "formatVersion": 1, "documents": [
                  { "url": "https://www.example.ca/no-id" },
                  { "id": 5, "url": "https://www.example.ca/five" },
                  { "url": "https://www.example.ca/five" }
                ] }""");

        List<ServiceDocument> loaded = storage.loadAll();
        assertEquals(2, loaded.size(), "repeated URL without id is skipped too");
        assertEquals(5, loaded.get(0).getId());
        assertEquals("https://www.example.ca/no-id", loaded.get(1).getUrl());
        assertEquals(6, loaded.get(1).getId());
    }

    @Test
    void twoEntriesWithoutIdAndSameUrlLoadOnce() throws Exception {
        writeDocumentsFile("""
                { "formatVersion": 1, "documents": [
                  { "url": "https://www.example.ca/twice", "title": "First" },
                  { "url": "https://www.example.ca/twice", "title": "Second" }
                ] }""");

        List<ServiceDocument> loaded = storage.loadAll();
        assertEquals(1, loaded.size());
        assertEquals("First", loaded.get(0).getTitle());
    }
}
