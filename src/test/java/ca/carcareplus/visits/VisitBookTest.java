package ca.carcareplus.visits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.StorageException;

class VisitBookTest {

    @TempDir
    Path dir;

    private static VisitRequest request(String name) {
        return new VisitRequest(null, "2026-11-16T10:30", "https://www.openstreetmap.org/node/1", "Test Garage",
                "(519) 555-0101", "", "2026-11-17T14:00", "Tomorrow, 2:00 PM", true, "my brakes are squealing",
                "Brake problem", "Gas Vehicle", name, "(519) 555-0100", "", "", null);
    }

    @Test
    void referencesCountUpAndRequestsSurviveARestart() throws StorageException {
        Path file = dir.resolve("visits").resolve("visits.json");
        VisitBook book = VisitBook.open(file);
        assertEquals("CC-1001", book.add(request("Sam Lee")).id());
        assertEquals("CC-1002", book.add(request("Ana Diaz")).id());
        assertEquals(VisitRequest.REQUESTED, book.get("CC-1001").status());

        VisitBook again = VisitBook.open(file);
        assertEquals(2, again.size());
        assertEquals("Ana Diaz", again.get("cc-1002").name(), "references ignore case");
        assertEquals("CC-1003", again.add(request("Lee Wong")).id(), "numbering continues after a restart");
    }

    @Test
    void cancelIsSavedAndCanBeRepeated() throws StorageException {
        Path file = dir.resolve("visits.json");
        VisitBook book = VisitBook.open(file);
        book.add(request("Sam Lee"));
        assertTrue(book.cancel("CC-1001").isCancelled());
        assertTrue(book.cancel("CC-1001").isCancelled());
        assertTrue(VisitBook.open(file).get("CC-1001").isCancelled());
        assertThrows(InvalidInputException.class, () -> book.cancel("CC-9999"));
    }

    @Test
    void findSkipsUnknownReferences() throws StorageException {
        VisitBook book = new VisitBook();
        book.add(request("Sam Lee"));
        book.add(request("Ana Diaz"));
        assertEquals(List.of("CC-1002", "CC-1001"),
                book.find(List.of("CC-1002", "CC-0000", "CC-1001", "CC-1002")).stream().map(VisitRequest::id).toList());
        assertNull(book.get(null));
    }

    @Test
    void memoryOnlyBookWritesNoFile() throws Exception {
        new VisitBook().add(request("Sam Lee"));
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void brokenFileGivesStorageException() throws Exception {
        Path file = dir.resolve("visits.json");
        Files.writeString(file, "{ not json");
        assertThrows(StorageException.class, () -> VisitBook.open(file));
    }

    @Test
    void messageHasEverythingTheShopNeeds() throws StorageException {
        VisitRequest saved = new VisitBook().add(request("Sam Lee"));
        String message = saved.message();
        assertTrue(message.startsWith("Hello Test Garage,"), message);
        assertTrue(message.contains("Reference: CC-1001"), message);
        assertTrue(message.contains("Preferred time: Tomorrow, 2:00 PM\n"), message);
        assertTrue(message.contains("Problem: my brakes are squealing (Brake problem)"), message);
        assertTrue(message.contains("Phone: (519) 555-0100"), message);
        assertFalse(message.contains("Email:"), "no empty lines for missing details");
        assertEquals("Repair request CC-1001: Brake problem, Tomorrow, 2:00 PM", saved.subject());
    }

    @Test
    void unknownHoursAskTheShopForAnExactTime() throws StorageException {
        VisitRequest r = request("Sam Lee");
        VisitRequest morning = new VisitBook().add(new VisitRequest(null, r.createdAt(), r.shopUrl(), r.shopTitle(),
                r.shopPhone(), r.shopEmail(), "2026-11-17T09:00", "Tomorrow, Morning (9 AM – 12 PM)", false,
                r.problem(), r.service(), r.vehicle(), r.name(), r.phone(), r.email(), r.note(), null));
        assertTrue(morning.message().contains("Tomorrow, Morning (9 AM – 12 PM) (please suggest an exact time)"));
    }
}
