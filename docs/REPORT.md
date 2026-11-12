# CarCare+ — Final Project Report

**COMP 8547 Advanced Computing Concepts · University of Windsor · Fall 2026**

> **Draft status.** Everything technical below is taken from the code and real test and benchmark runs (as of 2026-11-12). Places marked **TODO (group)** need facts only the group can give. Do not fill them with guesses. Before submitting, search this file for `TODO` and resolve every one.

---

## 1. Group, members and variant

| | |
|---|---|
| Group name | **TODO (group)** |
| Members (name, student number) | **TODO (group)** |
| Project / variant | CarCare+ — Intelligent Roadside Assistance and Service Discovery System |

**Task (copied from the approved variant proposal):**

> **TODO (group):** paste the exact variant text that was submitted and approved.

**Summary.** CarCare+ is a Java console backend. It crawls selected Canadian car-repair and EV-charging websites, turns their pages into service documents and lets a driver search them. The driver chooses **EV or gas vehicle → problem category → words → location** and gets ranked pages with phone numbers, emails and postal codes taken from the page text. It implements all ten listed features: web crawler, HTML parser, inverted indexing, frequency count, search frequency, page ranking, word completion, spell checking, regex data validation and regex pattern finding.

---

## 2. Contributions

The assignment asks for each member's contribution **with Java file names** (Info p.3).

> **TODO (group):** fill this table with what each person **actually** did. List the Java files each member wrote or owns and can explain in Q&A. If the work was not shared equally, say so honestly and follow the instructor's guidance on how to report it (plan §14, question 7).

| Member | Features | Java files |
|---|---|---|
| TODO | | |
| TODO | | |
| TODO | | |
| TODO | | |
| TODO | | |

**Use of AI assistance:** **TODO (group)** — state whether and how AI tools were used, as required by the course policy (plan §14 lists this as an open question for the instructor).

**File list by feature** (to help fill the table):

| Feature | Java files |
|---|---|
| Web crawler | `crawler/WebCrawler`, `URLFrontier`, `UrlNormalizer`, `RobotsTxtParser`, `PageFetcher`, `HttpPageFetcher`, `FetchResult`, `LinkExtractor`, `CrawlConfig`, `CrawlResult`, `CrawlStats`, `app/CrawlPipeline`, `storage/RawPageStore` |
| HTML parser | `parser/HTMLParser`, `ContentExtractor`, `ServiceCategorizer`, `app/ParsePipeline` |
| Inverted indexing | `search/InvertedIndex`, `Tokenizer` |
| Frequency count | `search/FrequencyCounter` |
| Search frequency | `search/SearchFrequency` |
| Page ranking | `search/PageRanker`, `TopK`, `LocationMatcher`, `SearchEngine`, `SearchOutcome` |
| Word completion | `query/Trie`, `TrieNode`, `WordCompleter`, `Vocabulary` |
| Spell checking | `query/SpellChecker`, `LevenshteinDistance`, `Vocabulary`, `search/SpellingSuggester` |
| Regex data validation | `validation/DataValidator`, `RegexPatterns` |
| Regex pattern finding | `validation/PatternExtractor`, `RegexPatterns` |
| Shared | `models/*`, `exceptions/*`, `storage/DocumentStorage`, `StoredDocument`, `app/Main`, `ConsoleMenu`, `SearchSystem`, `ResultFormatter`, `FixtureLoader`, `perf/*` |

---

## 3. Architecture

```
OFFLINE   config/sources.txt -> WebCrawler (BFS, robots.txt, politeness delay) -> data/raw-pages/
          raw pages -> HTMLParser -> ContentExtractor + PatternExtractor (regex) + ServiceCategorizer
                    -> DocumentStorage -> data/parsed-pages/ (documents.json + texts/<id>.txt)

START-UP  documents -> InvertedIndex       texts/*.txt -> Vocabulary -> Trie (completion)
                                                                    -> SpellChecker
          data/search-history.txt -> SearchFrequency

SEARCH    ConsoleMenu -> DataValidator -> Tokenizer -> InvertedIndex lookup -> vehicle-type filter
                      -> PageRanker (TopK min-heap) -> ranked results
                      -> unknown words -> SpellChecker -> "Did you mean ...?" -> corrected search
```

Crawling and searching are separate. A search only uses the in-memory structures built at start-up and never touches the network. `Tokenizer` is shared by the index, the vocabulary and the query, so a word on a page and the same word in a query always become the same term ("Tyres" → `tire`, "batteries" → `battery`).

---

## 4. Additional features

- **robots.txt compliance (RFC 9309):** longest-match rule, `Allow` beats an equally long `Disallow`, `*` and `$` wildcards, and the `Crawl-delay` setting.
- **Vehicle-aware menu:** EV drivers get "Find EV charging stations". Gas-only categories (engine, fuel) are hidden from them, and EV-only categories are hidden from gas drivers.
- **Recommendation bonuses in ranking:** +5 when the page matches the chosen category and +3 when it matches the typed location (postal-code area, province or city).
- **EV connector extraction:** CCS, CHAdeMO, J1772 and NACS plus charging power ("350 kW") are found in page text.
- **Duplicate detection:** the same URL, or the same text under another URL (SHA-256 content hash), is stored once.
- **Corrected search:** when a word is misspelled, the menu offers to search again with the suggestions.
- **Performance experiments:** five experiments compare each data structure with the simple alternative (§7).

---

## 5. Features: problem, data structure, algorithm, complexity

### 5.1 Web crawler

**Problem.** Download the pages of one website without visiting a page twice, without leaving the site, and without breaking the site's rules.

**Data structures.** `URLFrontier` = `ArrayDeque` (FIFO queue) + `HashSet` of every URL ever queued. `UrlNormalizer` makes one canonical form per page: it lower-cases the host and removes default ports, `#fragment`, `utm_*` parameters and `.`/`..` segments. This lets the set recognise duplicates.

**Algorithm (breadth-first search).**
```
check robots.txt of the site (stop if the site refuses)
add seeds at depth 0
while frontier not empty and saved < maxPages:
    url = frontier.next()                      // oldest first -> BFS
    skip if robots.txt disallows url
    wait max(source delay, robots Crawl-delay), then fetch (15 s timeout)
    failure      -> count it; stop after 3 blocks (403/429) in a row
    sitemap XML  -> queue the URLs it lists
    HTML page    -> save it; if depth < maxDepth: queue unseen in-domain links at depth+1
```

**Complexity.** O(V + E) queue and set operations for V pages and E links. In practice, time is dominated by V × (network latency + politeness delay).

**Why BFS, not DFS.** BFS visits pages level by level, so the depth limit is natural and the most important (shallow) pages come first. DFS could spend the whole page budget on one deep branch.

**Ethics.** The delay is never below 1 s and is 3 s for all configured sources. The crawler never bypasses bot protection: a 403 challenge counts as "skip". See §9.

### 5.2 HTML parser

**Problem.** Turn messy real HTML into a clean document: title, description, headings, main text, links, contact details and categories.

**How.** jsoup builds a DOM tree and repairs broken HTML the way a browser does. Our own `ContentExtractor` decides what to trust:
- the `<title>`, or the first `<h1>` if the title is missing
- the meta description
- `h1`–`h3` headings
- main text with script, style, navigation and site-wide header and footer removed

`PatternExtractor` adds phones, emails, postal codes and the province. `ServiceCategorizer` scores keywords from `config/categories.txt`: 3 points in a title or heading, 1 point in the body, and a category needs 2 points.

**Complexity.** O(n) for HTML length n per extraction pass. Categorizing is about O(K · n) for K keywords.

**Robustness.** A page with missing parts gets empty values instead of an exception, so one bad page never stops a parse run (tested with `fixtures/html/malformed.html`).

### 5.3 Inverted indexing

**Problem.** Find the pages containing a word without reading every page.

**Data structure.** A positional inverted index: `HashMap<term, HashMap<docId, ArrayList<position>>>`.
```
"battery" -> { 0: [3, 17, 40], 5: [0] }     // doc 0 at token positions 3, 17, 40
```
Each document is indexed as one token sequence (title, description, headings, body). The index records where each part starts, so the ranker knows whether a hit is in the title or the body. The positions also make exact-phrase detection possible.

**Complexity.** Build O(N) for N tokens in all pages. Lookup O(1) average + O(p) for p matching pages, compared with O(N) for scanning. Measured speed-up: **119–131×** (§7).

### 5.4 Frequency count

**Problem.** How often does a word occur on one specific URL (R9)?

**How.** `FrequencyCounter` maps the URL to its document id (`HashMap`) and reads the size of that word's position list from the index: **O(1) average**. A scanning version (`countByScanning`) is kept, and tests check that both give the same answer. `topTerms(url, k)` lists a page's most frequent words using the `TopK` heap.

### 5.5 Search frequency

**Problem.** Show previously searched words and how often each was searched (R10), and remember them between runs.

**Data structure.** `HashMap<term, count>`. Recording happens on every search, so it must be cheap: O(1) average. Sorting happens only when someone asks: `topSearches(k)` is O(u log k) with the heap, for u distinct terms. The counts are saved to `data/search-history.txt` (one `term<TAB>count` line each). Damaged lines are skipped with a warning.

### 5.6 Page ranking

**Problem.** Put the most relevant pages first, with more occurrences giving a higher rank (R11).

**Score (small whole numbers so it can be checked by hand):**
```
for every query word:  5 x (times in title) + 3 x (times in headings)
                     + 2 x (times in description) + 1 x (times in body)
+ 10 if the whole query appears as an exact phrase (2+ words)
+  5 if the page has the chosen category
+  3 if the page is in the typed location
```
Every result prints its breakdown, e.g. `title 1x5 + headings 1x3 + description 3x2 + body 6x1 + phrase +10 + category +5 = 35`.

**Top-k selection.** `TopK` is a min-heap (`PriorityQueue`) that holds at most k items. Its top is the worst current winner and is removed when a better item arrives. That costs **O(n log k)** time and O(k) memory, compared with O(n log n) for sorting everything. Ties are broken by more title hits, then URL, so the order is the same on every run.

**Not PageRank.** This is keyword relevance. Google's PageRank scores pages by the links between them.

### 5.7 Word completion

**Problem.** Suggest whole words while the user types the beginning (`bat` → `battery`).

**Data structure.** A Trie. Each `TrieNode` keeps its children in a `TreeMap<Character, TrieNode>`, plus the frequency of the word ending there. Words with the same beginning share nodes.

**Algorithm.** Walk the prefix (O(P)), then visit only the subtree below it and keep the k most frequent words with the heap (O(S log k) for S nodes in the subtree). A linear scan must test all W words (O(W · P)). Measured speed-up: **7.7–59×** (§7). In the menu, typing `bat?` lists completions to pick from.

### 5.8 Spell checking

**Problem.** When a word matches nothing, suggest similar words (R8). The vocabulary comes from **all words in the text files** (`data/parsed-pages/texts/*.txt`).

**Algorithm.** Levenshtein edit distance by dynamic programming:
```
dp[i][0] = i,  dp[0][j] = j
dp[i][j] = dp[i-1][j-1]                                   if a[i] == b[j]
         = 1 + min(dp[i-1][j], dp[i][j-1], dp[i-1][j-1])  otherwise
```
Worked example, `cat` → `cut` = **1**:

|   | ε | c | u | t |
|---|---|---|---|---|
| ε | 0 | 1 | 2 | 3 |
| c | 1 | **0** | 1 | 2 |
| a | 2 | 1 | **1** | 2 |
| t | 3 | 2 | 2 | **1** |

**Optimisations** (`distanceWithin`):
- skip a word whose length differs by more than the limit
- keep only two rows (O(n) memory)
- stop early as soon as a whole row exceeds the limit

The limit is 1 edit for words of up to 4 letters and 2 edits otherwise; with 2 edits, "car" would match "bar", "cap", "jar" and so on. Suggestions are ordered by distance, then frequency. Measured speed-up over brute force: **3.1–3.5×** (§7).

**Note.** A swap such as `battrey` → `battery` costs 2 (two substitutions). The Damerau variant would count it as 1, which is a possible extension.

### 5.9 Regex data validation

**Problem.** Reject bad input with a clear message, and normalize good input.

All patterns are compiled once in `RegexPatterns`. `DataValidator` checks the following:

| Input | Rule | Example |
|---|---|---|
| Search query | letters, digits, space, `'`, `-`; at most 100 characters | `<script>` is rejected |
| Location | a postal code, or a place name (letters, space, `. , ' -`; at most 50 characters) | `n9b3p4` → `N9B 3P4`; `N9B 3P` is rejected |
| Postal code | `[ABCEGHJ-NPRSTVXY]\d[ABCEGHJ-NPRSTV-Z][ -]?\d[ABCEGHJ-NPRSTV-Z]\d` | D, F, I, O, Q, U are never used; W and Z never come first |
| Phone (NANP) | optional `+1`; area code and exchange start with 2–9; brackets balanced | normalized to `(604) 859-2555` |
| Email, URL | practical subset of RFC 5322; `http(s)` and checked again with `java.net.URI` | |

**ReDoS safety.** There are no nested quantifiers, every repetition has an upper bound, and input length is capped before matching, so matching takes roughly O(n). A test checks that a 1,000,000-character input is rejected in under 1 second.

### 5.10 Regex pattern finding

`PatternExtractor` runs `matcher.find()` in a loop over the page text. It re-checks and normalizes every match with `DataValidator`, then removes duplicates with a `LinkedHashSet`, which keeps the first-seen order. It extracts:
- phones (lookarounds stop it from cutting 10 digits out of a longer number)
- emails
- postal codes (upper case only in page text, so product codes such as `a1b2c3` are not matched)
- EV connectors `CCS|CHAdeMO|J1772|NACS`
- power ratings `\d{2,3}\s?kW`

The bare word "Tesla" is deliberately not treated as a connector, because "Tesla Model 3" is a car.

---

## 6. Screenshots

> **TODO (group):** take the screenshots listed in `docs/DEMO_SCRIPT.md` and save them as `docs/screenshots/<scene>.png`. The text each one should show is in `docs/demo-transcripts/` (made by `scripts/capture-demo.sh`). Say clearly that these use the invented test pages unless an approved real crawl has been done.

| Feature | Scene | Image |
|---|---|---|
| Inverted index, ranking, pattern finding, completion | 1 — EV battery near V2S 1A1 | `screenshots/01-ev-battery-completion-ranking.png` |
| Spell checking + corrected search | 2 — `breke pads` | `screenshots/02-spell-check-corrected-search.png` |
| EV branch + location | 3 — charging stations near Windsor | `screenshots/03-ev-charging-windsor.png` |
| Word completion | 4 — `bra` | `screenshots/04-word-completion.png` |
| Spell checking (edit distance) | 5 — `battrey` | `screenshots/05-spell-check-edit-distance.png` |
| Frequency count | 6 — `brake` on Brake Repair Calgary | `screenshots/06-word-frequency.png` |
| Search frequency | 7 — history | `screenshots/07-search-history.png` |
| Regex data validation | 8 — bad query and postal code | `screenshots/08-input-validation.png` |
| HTML parser | `fixtures` + `parse` output | `screenshots/09-parse.png` |
| Web crawler | `crawl` source list + crawler tests | `screenshots/10-crawler.png` |
| Testing | `mvn test` summary | `screenshots/11-tests.png` |

---

## 7. Testing and performance

### 7.1 Unit tests (JUnit 5)

**916 tests, 0 failures** (`mvn test`, 2026-11-12). None of them use the internet.

| Package | Tests | What is covered |
|---|---|---|
| crawler | 138 | URL normalization, BFS order, depth and page limits, robots.txt rules, 403/429/timeouts with a fake fetcher and a local HTTP server |
| validation | 131 | valid and invalid tables for every pattern; extraction from fixture text; 1,000,000-character input |
| search | 123 | tokenizer, index, hand-computed ranking scores, top-k, search history file (including damaged lines) |
| models | 78 | equality, null-safety, vehicle/category rules |
| query | 62 | trie, completion, Levenshtein (matrix and bounded versions agree on random words), spell checker |
| parser | 61 | empty, malformed, script-heavy pages, missing title or description |
| app | 56 | end-to-end (fixtures → index → search), result formatting, whole scripted menu sessions |
| storage | 35 | save/load round trip, duplicate URL and content, missing or corrupt files |
| perf | 7 | benchmarks run and both methods agree |
| (root) | 225 | `RobustnessTest`: public methods × `null` / `""` / whitespace / 10,000 characters |

**A bug the tests found.** `RobustnessTest` showed that the full Levenshtein matrix on two 10,000-character strings needed about 400 MB. The matrix is now limited to 1,000 characters. Spell checking uses the two-row version, which has no such limit.

### 7.2 Performance experiments

Synthetic data (fixed random seed), `System.nanoTime`, median of 5 runs after 3 warm-ups. Every row was checked: both methods gave the same answer. Full tables are in `docs/performance-results.md`.

| Experiment | Size | Fast method | Simple method | Speed-up |
|---|---|---|---|---|
| Query: index vs linear scan | 5,000 docs | 34.59 µs | 4,517 µs | 130.6× |
| Top-10: heap vs full sort | 100,000 candidates | 1.99 ms | 12.92 ms | 6.5× |
| Spell check: optimised vs brute force | 20,000 words | 2.48 ms | 8.06 ms | 3.3× |
| Completion: Trie vs scan | 50,000 words | 15.12 µs | 898 µs | 59.4× |

Index build time grows linearly, as expected for O(N): about 0.39–0.53 ms per 1,000 words from 100 to 5,000 documents. The speed-ups grow with input size, which matches the complexity analysis: O(1)+O(p) vs O(N), and O(n log k) vs O(n log n).

---

## 8. Data status

> **TODO (group):** update this section after the instructor answers about crawling.

As of 2026-11-12, real crawling is **paused**. The terms of use of some sources (Electrify Canada, Fountain Tire) restrict copying content, and we asked the instructor before crawling. The crawler is complete and tested against a fake fetcher and a local HTTP server. All demo output uses **6 invented test pages** (`fixtures/html/`). They are flagged `testFixture = true` and printed with a `[TEST FIXTURE]` label, so they are never presented as real businesses.

Configured sources (`config/sources.txt`; robots.txt checked 2026-10-31):

| Source | Domain | Content | Limits |
|---|---|---|---|
| Fountain Tire | fountaintire.com | repair services, store pages | depth 2, 80 pages, 3 s delay |
| Electrify Canada | electrify-canada.ca | EV charger locations (from sitemap) | depth 1, 70 pages, 3 s delay |
| Kal Tire | kaltire.com | service descriptions | depth 1, 25 pages, 3 s delay |

---

## 9. Ethics and compliance

- robots.txt is read and obeyed on every run, including `Disallow`, `Allow` and `Crawl-delay`.
- Every request waits for a politeness delay and is identified by the crawler's own User-Agent.
- 403 and 429 answers are treated as "not welcome". After 3 in a row, the source is stopped. Bot protection (for example, a Cloudflare challenge) is never bypassed.
- Terms of use were read, and crawling waits for the instructor's confirmation (§8).
- No personal data is collected. Only business contact details that are published on public pages are extracted.
- Invented test data is always labelled.

---

## 10. Limitations and future work

- Real crawled data is not yet used (§8).
- City and street addresses are not extracted. The location comes from postal codes and page text, and the province from the postal code.
- No distance or ETA: this would need coordinates or a routing service.
- No live availability, booking, payment or ratings.
- Spell checking does not treat swapped letters as one edit (Damerau–Levenshtein would).
- Plural folding uses a fixed word list rather than a stemmer such as Porter's.
- Console only. A GUI was not required and earns no marks (R6).

---

## 11. References

- Oracle, *Java SE 21 API Specification* (`java.util`, `java.util.regex`, `java.net.http`). https://docs.oracle.com/en/java/javase/21/docs/api/
- jsoup 1.23.2, *Java HTML Parser*. https://jsoup.org/
- Gson 2.14.0. https://github.com/google/gson
- JUnit 5 User Guide. https://junit.org/junit5/docs/current/user-guide/
- M. Koster, G. Illyes, H. Zeller, L. Sassman, *RFC 9309: Robots Exclusion Protocol*, IETF, 2022. https://www.rfc-editor.org/rfc/rfc9309
- V. I. Levenshtein, "Binary codes capable of correcting deletions, insertions, and reversals," *Soviet Physics Doklady*, 10(8), 1966.
- Canada Post, *Addressing Guidelines* (postal code format). https://www.canadapost-postescanada.ca/
- Source websites (robots.txt checked 2026-10-31): fountaintire.com, electrify-canada.ca, kaltire.com.
- **TODO (group):** COMP 8547 lecture notes and any other material used, with access dates.
