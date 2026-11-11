package ca.carcareplus.app;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import ca.carcareplus.crawler.CrawlConfig;
import ca.carcareplus.crawler.CrawlResult;
import ca.carcareplus.crawler.HttpPageFetcher;
import ca.carcareplus.crawler.WebCrawler;
import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.storage.RawPageStore;

/**
 * Entry point of CarCare+.
 *
 * Commands (until the console menu arrives in Phase 11):
 * <pre>
 * mvn -q compile exec:java                                      banner and usage
 * mvn -q compile exec:java -Dexec.args="crawl"                  list configured sources
 * mvn -q compile exec:java -Dexec.args="crawl 'Fountain Tire' 10"  crawl one source (optional page limit)
 * </pre>
 */
public class Main {

    static final String APP_NAME = "CarCare+";
    static final Path SOURCES_FILE = Path.of("config", "sources.txt");
    static final Path RAW_PAGES_DIR = Path.of("data", "raw-pages");

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
        System.out.println("  crawl                       list configured sources");
        System.out.println("  crawl \"<source>\" [maxPages] crawl one source and save raw HTML");
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
