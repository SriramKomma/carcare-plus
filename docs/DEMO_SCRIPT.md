# CarCare+ demo script

Exact keystrokes for the live demo (plan §13.2, slot 4:45–7:30) and for the report screenshots (R15: "screenshots of each feature").
The same scenes run automatically with `scripts/capture-demo.sh`, which saves one transcript per scene in `docs/demo-transcripts/`. Use those transcripts as a **backup** if the live demo fails, and to check that your screenshots match.

> All scenes below use the **6 invented test pages** (`[TEST FIXTURE]`). When an approved crawl exists, re-run the scenes on real data and replace the screenshots. Say this openly in the demo.

## Before the demo (not live)

```bash
mvn -q compile exec:java -Dexec.args="fixtures"   # 6 test pages -> data/raw-pages
mvn -q compile exec:java -Dexec.args="parse"      # -> data/parsed-pages (index is built at start-up)
mv data/search-history.txt data/search-history.backup.txt   # optional: start with an empty history
```

Never crawl live during the demo (plan §13.2).

## Scenes

Start the menu with `mvn -q compile exec:java`. Each line in **Type** is one input followed by Enter; `⏎` means Enter on its own.

| # | Feature(s) shown | Type | What to point at |
|---|---|---|---|
| 1 | Inverted index, page ranking, regex pattern finding, word completion | `1` `1` `2` `bat?` `1` `V2S 1A1` | Story: *"My EV battery is dead near Abbotsford."* The `bat?` completion list, then the score breakdown (`category +5`, `location +3`), and the phone, email and postal code pulled out by regex |
| 2 | Spell checking (no-result suggestions), frequency count per URL | `1` `2` `5` `breke pads` `⏎` `y` | `Did you mean: breke -> brake` with `breke=0` in the word counts, then the corrected search with `phrase +10` |
| 3 | EV branch, location matching | `1` `1` `10` `⏎` `Windsor` | Charging stations exist only in the EV menu; Enter uses the category name as the query |
| 4 | Word completion (Trie) | `2` `bra` | Most frequent words starting with `bra` |
| 5 | Spell checking (edit distance) | `3` `battrey` | `battery (edit distance 2, …)`. Be ready to fill the Levenshtein table on the board (plan §13.3 Q7) |
| 6 | Frequency count | `4` `1` `brake`, then `4` `1` `⏎` | Count of one word on one URL, then the page's most frequent words |
| 7 | Search frequency | `5` | Words from scenes 1–3 with their counts (saved to a file on exit) |
| 8 | Regex data validation | `1` `2` `5` `<script>` `brake` `N9B 3P` `n9b3p4` | Both bad inputs are rejected with a message; `n9b3p4` is normalized to `N9B 3P4` |
| — | Exit | `0` | `Search history saved. Goodbye!` |

Menu numbers used above: EV categories `2` = Battery problem, `10` = Find EV charging stations; Gas categories `5` = Brake problem. Page `1` in option 4 = Brake Repair Calgary. These numbers come from the current fixtures and categories. If either changes, check the numbers again.

## Not in the menu (show with commands or slides)

- **Web crawler:** `mvn -q compile exec:java -Dexec.args="crawl"` lists the sources and limits. For the crawl itself, show `WebCrawlerTest` (fake fetcher: 403, timeouts, robots.txt) instead of crawling live.
- **HTML parser:** run `fixtures` then `parse` and show the report line; open `fixtures/html/malformed.html` to show that broken HTML still parses.
- **Performance:** `docs/performance-results.md` (made with the `benchmark` command).

## Screenshot checklist for the report

- [ ] Scene 1: completion list + ranked EV results with breakdown and contact details
- [ ] Scene 2: "Did you mean" + corrected search
- [ ] Scene 3: EV charging search
- [ ] Scene 4: word completion
- [ ] Scene 5: spell check with edit distances
- [ ] Scene 6: word count on one URL + top words
- [ ] Scene 7: search history
- [ ] Scene 8: validation errors
- [ ] `fixtures` + `parse` output (parser)
- [ ] `crawl` source list + crawler test run (crawler)
- [ ] `mvn test` summary line (testing)
