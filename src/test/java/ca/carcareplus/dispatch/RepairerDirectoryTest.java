package ca.carcareplus.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ca.carcareplus.exceptions.InvalidInputException;

class RepairerDirectoryTest {

    @TempDir
    Path dir;

    @Test
    void readsTheRealConfigFile() throws Exception {
        RepairerDirectory directory = RepairerDirectory.load(Path.of("config", "repairers.txt"));
        assertEquals(4, directory.size());
        Repairer sriram = directory.get("sriram");
        assertEquals("Sriram Komma", sriram.name());
        assertEquals("(519) 555-0101", sriram.phone(), "phone is normalized by DataValidator");
        assertEquals("Windsor", sriram.area());
        assertEquals("All types of repairs", sriram.specialties(), "'all' is spelled out");
        assertEquals("(519) 555-0102", directory.get("sarvesh").phone(), "+1 and brackets are accepted");
        assertEquals("London", directory.get("sarvesh").area());
        assertEquals(List.of("sriram", "sarvesh", "saikrishna", "dharanish"),
                directory.all().stream().map(Repairer::id).toList(), "file order is kept");
    }

    @Test
    void idsAreCaseInsensitiveAndUnknownIdsGiveNull() {
        RepairerDirectory directory = RepairerDirectory.parse(List.of("# comment", "", "Ana | Ana Diaz | 519 555 0100 | Windsor | Brakes"));
        assertEquals("Ana Diaz", directory.get(" ANA ").name());
        assertEquals("Brakes", directory.get("ana").specialties());
        assertNull(directory.get("bob"));
        assertNull(directory.get(null));
    }

    @Test
    void badLinesNameTheLineNumber() {
        InvalidInputException e = assertThrows(InvalidInputException.class,
                () -> RepairerDirectory.parse(List.of("# header", "ana | Ana | 519 555 0100 | Windsor")));
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());

        assertThrows(InvalidInputException.class,
                () -> RepairerDirectory.parse(List.of("ana | Ana | 123 | Windsor | all")), "bad phone");
        assertThrows(InvalidInputException.class,
                () -> RepairerDirectory.parse(List.of("an a | Ana | 519 555 0100 | Windsor | all")), "space in id");
        assertThrows(InvalidInputException.class,
                () -> RepairerDirectory.parse(List.of("ana | | 519 555 0100 | Windsor | all")), "no name");
        assertThrows(InvalidInputException.class,
                () -> RepairerDirectory.parse(List.of("ana | Ana | 519 555 0100 | | all")), "no area");
        assertThrows(InvalidInputException.class, () -> RepairerDirectory.parse(List.of(
                "ana | Ana | 519 555 0100 | Windsor | all", "ana | Ana 2 | 519 555 0101 | Windsor | all")), "duplicate id");
    }

    @Test
    void missingFileMeansNoRepairers() throws Exception {
        assertEquals(0, RepairerDirectory.load(dir.resolve("none.txt")).size());
    }
}
