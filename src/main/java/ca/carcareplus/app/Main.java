package ca.carcareplus.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import ca.carcareplus.crawler.CrawlConfig;
import ca.carcareplus.crawler.CrawlResult;
import ca.carcareplus.crawler.HttpPageFetcher;
import ca.carcareplus.crawler.WebCrawler;
import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.SearchRequest;
import ca.carcareplus.models.VehicleType;
import ca.carcareplus.parser.HTMLParser;
import ca.carcareplus.perf.Benchmarks;
import ca.carcareplus.parser.ServiceCategorizer;
import ca.carcareplus.search.SearchFrequency.TermCount;
import ca.carcareplus.search.SearchOutcome;
import ca.carcareplus.storage.RawPageStore;

/**
 * Entry point of CarCare+.
 *
 * Commands (until the console menu arrives in Phase 11):
 * <pre>
 * mvn -q compile exec:java                                      banner and usage
 * mvn -q compile exec:java -Dexec.args="crawl"                  list configured sources
 * mvn -q compile exec:java -Dexec.args="crawl 'Fountain Tire' 10"  crawl one source (optional page limit)
 * mvn -q compile exec:java -Dexec.args="fixtures"               load invented test pages as raw pages
 * mvn -q compile exec:java -Dexec.args="parse"                  raw pages -> documents (rebuild)
 * mvn -q compile exec:java -Dexec.args="search gas battery"     search (ev|gas, words, optional location=...)
 * mvn -q compile exec:java -Dexec.args="complete bat"           word completion
 * mvn -q compile exec:java -Dexec.args="count battery URL"      frequency of a word on one page
 * mvn -q compile exec:java -Dexec.args="history"                most searched words
 * mvn -q compile exec:java -Dexec.args="benchmark"              performance experiments -> docs/performance-results.md
 * </pre>
 */
public class Main {

    static final String APP_NAME = "CarCare+";
    static final Path SOURCES_FILE = Path.of("config", "sources.txt");
    static final Path RAW_PAGES_DIR = Path.of("data", "raw-pages");
    static final Path PARSED_DIR = Path.of("data", "parsed-pages");
    static final Path HISTORY_FILE = Path.of("data", "search-history.txt");
    static final Path CATEGORIES_FILE = Path.of("config", "categories.txt");
    static final Path FIXTURES_DIR = Path.of("fixtures", "html");
    static final Path BENCHMARK_FILE = Path.of("docs", "performance-results.md");

    public static void main(String[] args) {
        // One-line log format: "[INFO] message"
        System.setProperty("java.util.logging.SimpleFormatter.format", "[%4$s] %5$s%6$s%n");

        System.out.println(banner());
        System.out.println("Running on Java " + Runtime.version().feature());
        if (args.length == 0) {
            printUsage();
            return;
        }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "crawl" -> runCrawl(args);
                case "fixtures" -> runFixtures();
                case "parse" -> runParse();
                case "search" -> runSearch(args);
                case "complete" -> runComplete(args);
                case "count" -> runCount(args);
                case "history" -> runHistory();
                case "benchmark" -> runBenchmark();
                default -> {
                    System.out.println("Unknown command: " + args[0]);
                    printUsage();
                }
            }
        } catch (CarCareException | InvalidInputException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    /** Returns the start-up banner. Kept as a method so it can be unit tested. */
    static String banner() {
        return APP_NAME + " - Intelligent Roadside Assistance and Service Discovery";
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  crawl                        list configured sources");
        System.out.println("  crawl \"<source>\" [maxPages]  crawl one source and save raw HTML");
        System.out.println("  fixtures                     load the invented test pages (fixtures/html)");
        System.out.println("  parse                        turn saved raw pages into searchable documents");
        System.out.println("  search ev|gas <words> [location=<place>]");
        System.out.println("  complete <start of a word>   word completion");
        System.out.println("  count <word> <url>           how often a word occurs on one page");
        System.out.println("  history                      most searched words");
        System.out.println("  benchmark                    run performance experiments (synthetic data)");
    }

    private static void runCrawl(String[] args) throws CarCareException {
        List<CrawlConfig> configs = CrawlConfig.loadFile(SOURCES_FILE);
        if (args.length < 2) {
            System.out.println("Configured sources in " + SOURCES_FILE + ":");
            for (CrawlConfig config : configs) {
                System.out.println("  " + config.sourceName() + "  (" + config.allowedDomain()
                        + ", up to " + config.maxPages() + " pages)");
            }
            return;
        }

        CrawlConfig config = findSource(configs, args[1]);
        if (args.length >= 3) {
            config = config.withMaxPages(parsePageLimit(args[2]));
        }

        RawPageStore store = new RawPageStore(RAW_PAGES_DIR);
        CrawlPipeline pipeline = new CrawlPipeline(
                new WebCrawler(new HttpPageFetcher(WebCrawler.USER_AGENT)), store);
        CrawlResult result = pipeline.crawlAndSave(config);

        System.out.println(result.stats());
        System.out.println("Raw HTML saved in " + store.sourceDir(config.sourceName()));
    }

    private static void runFixtures() throws CarCareException {
        int count = FixtureLoader.load(FIXTURES_DIR, new RawPageStore(RAW_PAGES_DIR));
        System.out.println("Loaded " + count + " invented test pages as source '" + FixtureLoader.SOURCE_NAME
                + "'. Next: parse");
    }

    private static void runParse() throws CarCareException {
        List<String> sources = new ArrayList<>();
        for (CrawlConfig config : CrawlConfig.loadFile(SOURCES_FILE)) {
            sources.add(config.sourceName());
        }
        sources.add(FixtureLoader.SOURCE_NAME);
        HTMLParser parser = new HTMLParser(ServiceCategorizer.loadFile(CATEGORIES_FILE));
        ParsePipeline.ParseReport report =
                new ParsePipeline(new RawPageStore(RAW_PAGES_DIR), parser, PARSED_DIR).parseSources(sources);
        System.out.println(report);
        if (report.pagesRead() == 0) {
            System.out.println("No raw pages found. Run 'crawl' (when approved) or 'fixtures' first.");
        }
    }

    private static void runSearch(String[] args) throws CarCareException {
        if (args.length < 3) {
            throw new InvalidInputException("Usage: search ev|gas <words> [location=<place>]");
        }
        VehicleType vehicle = VehicleType.fromUserInput(args[1]);
        List<String> words = new ArrayList<>();
        String location = "";
        for (String arg : Arrays.copyOfRange(args, 2, args.length)) {
            if (arg.toLowerCase(Locale.ROOT).startsWith("location=")) {
                location = arg.substring("location=".length());
            } else {
                words.add(arg);
            }
        }
        SearchSystem system = loadSearchSystem();
        SearchRequest request = new SearchRequest(String.join(" ", words), vehicle, null, location,
                SearchRequest.DEFAULT_MAX_RESULTS);
        SearchOutcome outcome = system.engine().search(request);
        System.out.print(ResultFormatter.format(request, outcome));
        system.saveHistory();
    }

    private static void runComplete(String[] args) throws CarCareException {
        if (args.length < 2) {
            throw new InvalidInputException("Usage: complete <start of a word>");
        }
        String input = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        List<String> completions = loadSearchSystem().completer().complete(input, 5);
        System.out.println(completions.isEmpty() ? "No completions for '" + input + "'."
                : "Completions: " + String.join(", ", completions));
    }

    private static void runCount(String[] args) throws CarCareException {
        if (args.length != 3) {
            throw new InvalidInputException("Usage: count <word> <url>");
        }
        int count = loadSearchSystem().frequencyCounter().countInDocument(args[1], args[2]);
        System.out.println("'" + args[1] + "' occurs " + count + " time(s) on " + args[2]);
    }

    private static void runHistory() throws CarCareException {
        List<TermCount> top = loadSearchSystem().searchFrequency().topSearches(10);
        if (top.isEmpty()) {
            System.out.println("No searches yet.");
        }
        for (TermCount entry : top) {
            System.out.println("  " + entry.term() + "  " + entry.count());
        }
    }

    private static void runBenchmark() throws CarCareException {
        System.out.println("Running performance experiments on synthetic data (about a minute)...");
        String results = Benchmarks.run(Benchmarks.FULL);
        System.out.println(results);
        try {
            Files.writeString(BENCHMARK_FILE, results, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CarCareException("Could not write " + BENCHMARK_FILE + ": " + e.getMessage(), e);
        }
        System.out.println("Saved to " + BENCHMARK_FILE);
    }

    private static SearchSystem loadSearchSystem() throws CarCareException {
        SearchSystem system = SearchSystem.load(PARSED_DIR, HISTORY_FILE);
        if (system.isEmpty()) {
            throw new CarCareException("No documents yet. Run 'fixtures' and 'parse' first.");
        }
        return system;
    }

    static CrawlConfig findSource(List<CrawlConfig> configs, String name) {
        for (CrawlConfig config : configs) {
            if (config.sourceName().equalsIgnoreCase(name.trim())) {
                return config;
            }
        }
        throw new InvalidInputException("No source named '" + name + "' in " + SOURCES_FILE + ".");
    }

    static int parsePageLimit(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new InvalidInputException("Page limit must be a whole number, but was '" + text + "'.");
        }
    }
}
