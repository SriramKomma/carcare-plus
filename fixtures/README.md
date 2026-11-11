# Test fixtures — invented pages, NOT crawled data

Every page in `html/` was written by hand for testing and demos. Names, addresses,
emails and phone numbers are made up (phone numbers use the 555-01xx range reserved
for fiction). They let the parser, search and console be tested before real
crawling is approved.

- Unit tests read these files directly (`HTMLParserTest`, integration tests).
- `mvn -q compile exec:java -Dexec.args="fixtures"` copies them into
  `data/raw-pages/test-fixtures/` under the source name **Test Fixtures**.
  Documents parsed from them are flagged as test fixtures and shown with
  a `[TEST FIXTURE]` label, so they can never be mistaken for real results.
