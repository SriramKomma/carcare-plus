# CarCare+

Intelligent Roadside Assistance and Service Discovery System — COMP 8547 Advanced Computing Concepts, University of Windsor, Fall 2026.

A Java console backend that crawls selected Canadian car-repair and EV-charging websites, parses them into service documents, and lets a driver search them: choose **EV or gas vehicle → problem category → words → location** and get ranked pages with phone numbers, emails and postal codes pulled out by regular expressions.

Full plan and progress tracker: [docs/PROJECT_PLAN.md](docs/PROJECT_PLAN.md) · Performance results: [docs/performance-results.md](docs/performance-results.md) · Demo script: [docs/DEMO_SCRIPT.md](docs/DEMO_SCRIPT.md) · Report draft: [docs/REPORT.md](docs/REPORT.md)

> **Data status:** real crawling is paused until the instructor confirms the source websites' terms of use allow it (plan §10). All searches currently run on **6 invented test pages** in `fixtures/html/`. Every result from them is labelled `[TEST FIXTURE]`.

## Requirements

- JDK 21 or newer (the code is compiled with `--release 21`, so it also builds on newer JDKs)
- Maven 3.9+
- VS Code with the "Extension Pack for Java" (or Eclipse: *File → Import → Existing Maven Projects*)

Check with `mvn -v`: the `Java version:` line must be 21 or higher.

## Quick start

```bash
mvn test                                          # compile and run all unit tests
mvn -q compile exec:java -Dexec.args="fixtures"   # copy the invented test pages into data/raw-pages
mvn -q compile exec:java -Dexec.args="parse"      # raw pages -> searchable documents
mvn -q compile exec:java                          # start the interactive menu
```

## Commands

Run with `mvn -q compile exec:java -Dexec.args="<command>"`. With no command, the interactive menu starts.

| Command | What it does |
|---|---|
| *(none)* or `menu` | Interactive menu (needs parsed documents) |
| `help` | List of commands |
| `crawl` | List the sources in `config/sources.txt` |
| `crawl "<source>" [maxPages]` | Crawl one source and save raw HTML (only after approval, see above) |
| `fixtures` | Load the invented test pages as raw pages |
| `parse` | Turn all saved raw pages into documents (full rebuild) |
| `search ev\|gas <words> [location=<place>]` | One search without the menu |
| `complete <start of a word>` | Word completion |
| `count <word> <url>` | How often a word occurs on one page |
| `history` | Most searched words |
| `benchmark` | Performance experiments → `docs/performance-results.md` (about a minute) |

### The menu

```
1. Find help for my vehicle   EV / Gas -> category -> words -> location -> ranked results
                              "bat?" lists completions to pick from; Enter uses the category name;
                              a misspelled word offers "Search again for ...?"
2. Word completion
3. Spell check a word         suggestions with their edit distance
4. Word frequency on a page   count one word, or list the page's most frequent words
5. Search history             most searched words (saved to data/search-history.txt on exit)
0. Exit
```

## How it works

```
OFFLINE   config/sources.txt -> WebCrawler (BFS, robots.txt, delay) -> data/raw-pages/
          raw pages -> HTMLParser + PatternExtractor (regex) + ServiceCategorizer
                    -> DocumentStorage -> data/parsed-pages/ (documents.json + texts/*.txt)

START-UP  documents -> InvertedIndex         texts/*.txt -> Vocabulary -> Trie, SpellChecker
          search-history.txt -> SearchFrequency

SEARCH    query -> DataValidator -> Tokenizer -> InvertedIndex lookup -> filter by vehicle type
                -> PageRanker (TopK heap) -> results; unknown words -> SpellChecker suggestions
```

Crawling and searching are separate: a search never touches the network.

## Features → classes

| Feature | Main classes | Data structure / algorithm | Cost |
|---|---|---|---|
| Web crawler | `crawler/WebCrawler`, `URLFrontier`, `UrlNormalizer`, `RobotsTxtParser`, `HttpPageFetcher` | Breadth-first search: `ArrayDeque` queue + `HashSet` of seen URLs; robots.txt rules; politeness delay | O(V + E) queue/set operations |
| HTML parser | `parser/HTMLParser`, `ContentExtractor`, `ServiceCategorizer` | jsoup DOM; removes script/style/nav/footer; keyword rules from `config/categories.txt` | O(page size) |
| Inverted indexing | `search/InvertedIndex`, `Tokenizer` | `HashMap<term, HashMap<docId, positions>>` (positional index) | build O(N) words; lookup O(1) + O(p) |
| Frequency count | `search/FrequencyCounter` | Term frequency read from the index | O(1) average per word and page |
| Search frequency | `search/SearchFrequency` | `HashMap<term, count>` + text file; top-k via heap | record O(1); top-k O(u log k) |
| Page ranking | `search/PageRanker`, `TopK`, `LocationMatcher` | Weighted occurrence score; min-heap `PriorityQueue` of size k | O(n log k) |
| Word completion | `query/Trie`, `TrieNode`, `WordCompleter` | Trie with word frequencies | O(P) + O(S log k) |
| Spell checking | `query/SpellChecker`, `LevenshteinDistance`, `Vocabulary` | Edit distance (dynamic programming), length filter + early exit; vocabulary from the text files | O(W · m · n) worst case |
| Regex validation | `validation/DataValidator`, `RegexPatterns` | Exact-match patterns for query, location, postal code, phone, email, URL | O(input length) |
| Regex pattern finding | `validation/PatternExtractor`, `RegexPatterns` | `find()` loops for phones, emails, postal codes, EV connectors (CCS, CHAdeMO, J1772, NACS, kW) | O(text length) |

### Ranking formula (`PageRanker`)

```
for every query word:  5 x (times in title) + 3 x (times in headings)
                     + 2 x (times in description) + 1 x (times in body)
+ 10 if the whole query appears as an exact phrase (2 or more words)
+  5 if the page has the category the user chose
+  3 if the page is in the location the user typed
```

Every result prints its breakdown, e.g. `title 1x5 + headings 1x3 + description 3x2 + body 6x1 + phrase +10 + category +5 = 35`. This is keyword relevance, not Google's PageRank (which uses links between pages).

## Testing

```bash
mvn test                       # all tests; none of them contact real websites
mvn test -Dtest=ConsoleMenuTest # one test class
```

- Crawler tests use a fake fetcher and a local HTTP server (404, 403, timeouts, redirects) — no internet needed.
- Parser and end-to-end tests use labelled HTML fixtures.
- `RobustnessTest` calls the public methods with `null`, `""`, whitespace and 10,000-character input.
- `ConsoleMenuTest` runs whole scripted menu sessions and checks the printed output.
- Performance experiments (synthetic data, median of 5 runs after 3 warm-ups): [docs/performance-results.md](docs/performance-results.md).

## Project layout

```
config/                 sources.txt (crawl sources), categories.txt (category keywords)
fixtures/html/          6 invented pages for demos and tests (labelled [TEST FIXTURE])
data/raw-pages/         downloaded / fixture HTML (generated, not in Git)
data/parsed-pages/      documents.json + texts/*.txt (generated, not in Git)
data/search-history.txt search counts (generated, not in Git)
docs/                   project plan, performance results, demo script
src/main/java/ca/carcareplus/
    app/  crawler/  parser/  validation/  storage/  search/  query/  perf/  models/  exceptions/
src/test/java/ca/carcareplus/   tests, mirroring the main packages
src/test/resources/             HTML and text fixtures used only by tests
```

## Dependencies

| Library | Version | Used for |
|---|---|---|
| jsoup | 1.23.2 | HTML → DOM tree (our own extraction logic runs on top) |
| Gson | 2.14.0 | Reading/writing `documents.json` |
| JUnit Jupiter | 5.12.2 | Tests |

## Ethics and data

- The crawler reads and obeys each site's `robots.txt` (`Disallow`, `Crawl-delay`), waits between requests (the longer of the source's delay — 3 s for every configured source, never below 1 s — and robots.txt `Crawl-delay`), and stops a source after repeated 403/429 answers. It never bypasses bot protection.
- Real crawling waits for the instructor's confirmation on terms of use (plan §10).
- Test pages are invented and flagged `testFixture = true`; they are never presented as real businesses.
