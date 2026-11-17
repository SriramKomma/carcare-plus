# CarCare+

Intelligent Roadside Assistance and Service Discovery System — COMP 8547 Advanced Computing Concepts, University of Windsor, Fall 2026.

A Java console backend that crawls selected Canadian car-repair and EV-charging websites, parses them into service documents, and lets a driver search them: choose **EV or gas vehicle → problem category → words → location** and get ranked pages with phone numbers, emails and postal codes pulled out by regular expressions.

Full plan and progress tracker: [docs/PROJECT_PLAN.md](docs/PROJECT_PLAN.md) · Performance results: [docs/performance-results.md](docs/performance-results.md) · Demo script: [docs/DEMO_SCRIPT.md](docs/DEMO_SCRIPT.md) · Report draft: [docs/REPORT.md](docs/REPORT.md)

> **Data status:** real Ontario shop data comes from **OpenStreetMap** (`osm` command, Open Database Licence, "© OpenStreetMap contributors"). The crawler runs only on **Natural Resources Canada** driver pages, whose terms allow non-commercial reproduction with credit (checked 2026-11-17); Fountain Tire, Electrify Canada and Kal Tire stay disabled in `config/sources.txt` because their terms restrict copying (plan §10). The **6 invented test pages** in `fixtures/html/` are labelled `[TEST FIXTURE]` in every result.
>
> **Describe the problem:** the web page takes the driver's own words ("my car won't start", "brakes are squealing"). `query/SymptomMatcher` maps them to a service with the rules in `config/symptoms.txt` and lists the nearest shops for that service, plus crawled NRCan guides on the topic.

**Location search:** a typed location is a filter, not just a bonus. "Windsor" shows only places whose city is Windsor (OpenStreetMap's `addr:city`, or else the nearest city/town/village within 25 km). "Windsor, ON", a postal code (same first three characters) or a province ("ON") also work.

## Requirements

- JDK 21 or newer (the code is compiled with `--release 21`, so it also builds on newer JDKs)
- Maven 3.9+
- VS Code with the "Extension Pack for Java" (or Eclipse: *File → Import → Existing Maven Projects*)

Check with `mvn -v`: the `Java version:` line must be 21 or higher.

## Quick start

```bash
mvn test                                          # compile and run all unit tests
mvn -q compile exec:java -Dexec.args="osm"        # real Ontario shops from OpenStreetMap (one download)
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
| `web [port]` | Web page with every feature at http://localhost:8080/ (local only; Ctrl+C stops it) |

**Web page extras:** "Use my location" (browser location, 2–50 km radius, nearest-first sorting), a map of results (Leaflet + OpenStreetMap tiles), and a **Live nearby** tab with Google Places ratings, reviews and opening hours. Live nearby is off unless the server is started with a Google Places API key: `export GOOGLE_MAPS_API_KEY=...` (Places API (New), billing enabled). Google results are fetched per search and never stored or indexed, as Google's terms require; the graded search features run on OpenStreetMap / crawled data. Once real data is loaded, the invented test pages are hidden on the web page.
| `crawl` | List the sources in `config/sources.txt` |
| `crawl "<source>" [maxPages]` | Crawl one source and save raw HTML, e.g. `crawl "Natural Resources Canada"` |
| `fixtures` | Load the invented test pages as raw pages |
| `contacts [maxSites]` | Visit each shop's own website (from OpenStreetMap) and keep only phones/emails found there, in `data/contacts/contacts.json` (then run `parse`) |
| `osm` | Download Ontario car-repair shops, tyre shops, EV chargers and places from OpenStreetMap into `data/osm/` (then run `parse`) |
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
- Only sites whose terms allow reuse are active in `config/sources.txt` (Natural Resources Canada: non-commercial reproduction allowed with credit; the page shows that credit under each guide). A test (`CrawlConfigTest`) fails if a restricted site is switched back on.
- Contact scraping (`crawler/ContactScraper`) visits only a shop's **own** website: robots.txt obeyed with the crawler's rules, at most the homepage and one "contact" page, 3 s between requests. It keeps only phone numbers and emails (our regex extractor plus `tel:`/`mailto:` links), never page text. Social media, directories and websites shared by several shops (chains) are skipped; toll-free head-office numbers give way to local ones, and job/no-reply addresses are dropped. The web page's **Contact** button only opens the driver's own phone or email app (`tel:`, `mailto:` with the problem filled in) — CarCare+ never sends messages itself.
- The OpenStreetMap download rejects answers that were cut short (fewer elements than all of Ontario must have) and downloads them again on the next run. Some networks are refused by the public Overpass servers (HTTP 406); run `osm` from another network then.
- Test pages are invented and flagged `testFixture = true`; they are never presented as real businesses.
- OpenStreetMap data is used under the ODbL; the app shows "© OpenStreetMap contributors" wherever OSM places appear. The download is two requests per run, and the files stay out of Git (`data/osm/`).
