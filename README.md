# CarCare+

Intelligent Roadside Assistance and Service Discovery System — COMP 8547 Advanced Computing Concepts, University of Windsor, Fall 2026.

A Java backend that crawls selected Canadian car-repair and EV-charging websites, parses them into service documents, and lets users search them with an inverted index, occurrence-based ranking, spell checking, word completion and regex validation.

Full plan and progress tracker: [docs/PROJECT_PLAN.md](docs/PROJECT_PLAN.md)

## Requirements

- JDK 21 (`/opt/homebrew/opt/openjdk@21` on macOS with Homebrew)
- Maven 3.9+
- VS Code with the "Extension Pack for Java" (or Eclipse: *File → Import → Existing Maven Projects*)

Maven must run on JDK 21. Check with `mvn -v` — the `Java version:` line should start with `21`.

## Build, test, run

```bash
mvn test                      # compile and run all unit tests
mvn -q compile exec:java      # run the application (ca.carcareplus.app.Main)
mvn test -Dgroups=network     # run only the tests that access real websites
```

## Project layout

```
config/               crawler seed configuration (sources.txt, ...)
data/raw-pages/       downloaded HTML (generated, not in Git)
data/parsed-pages/    parsed ServiceDocuments (generated, not in Git)
docs/                 project plan
src/main/java/ca/carcareplus/
    app/  crawler/  parser/  validation/  storage/  search/  query/  models/  exceptions/
src/test/java/ca/carcareplus/   tests, mirroring the main packages
src/test/resources/html/        labelled HTML test fixtures
```
