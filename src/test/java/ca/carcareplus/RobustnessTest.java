package ca.carcareplus;

import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;

import ca.carcareplus.crawler.CrawlConfig;
import ca.carcareplus.crawler.LinkExtractor;
import ca.carcareplus.crawler.RobotsTxtParser;
import ca.carcareplus.crawler.UrlNormalizer;
import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.CrawledPage;
import ca.carcareplus.models.SearchRequest;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.models.VehicleType;
import ca.carcareplus.parser.ContentExtractor;
import ca.carcareplus.parser.HTMLParser;
import ca.carcareplus.parser.ServiceCategorizer;
import ca.carcareplus.query.LevenshteinDistance;
import ca.carcareplus.query.SpellChecker;
import ca.carcareplus.query.Trie;
import ca.carcareplus.query.WordCompleter;
import ca.carcareplus.search.FrequencyCounter;
import ca.carcareplus.search.InvertedIndex;
import ca.carcareplus.search.LocationMatcher;
import ca.carcareplus.search.SearchEngine;
import ca.carcareplus.search.Tokenizer;
import ca.carcareplus.validation.DataValidator;
import ca.carcareplus.validation.PatternExtractor;

/**
 * Plan section 12.3: every public method that takes text is called with null, "",
 * whitespace and a 10,000-character string. Each call must either return normally
 * (a safe default) or throw one of our own exceptions with a message. Anything
 * else -- NullPointerException, StringIndexOutOfBounds, OutOfMemoryError,
 * StackOverflowError -- fails the test.
 */
class RobustnessTest {

    private static final String LONG = "a".repeat(10_000);
    private static final String LONG_MIXED = "Battery N9B 3P4 (604) 555-0142 <b>".repeat(300); // ~10,000 chars
    private static final List<String> INPUTS = Arrays.asList(null, "", "   ", LONG, LONG_MIXED);

    /** One method under test, called with a single text argument. */
    private record Target(String name, TextCall call) {
    }

    @FunctionalInterface
    private interface TextCall {
        void accept(String text) throws Exception;
    }

    private static InvertedIndex smallIndex() {
        ServiceDocument doc = new ServiceDocument("https://www.example.ca/a", "Example");
        doc.setId(0);
        doc.setTitle("Battery service");
        doc.setBodyText("Battery replacement in Windsor");
        return InvertedIndex.of(List.of(doc));
    }

    private static List<Target> targets() throws CarCareException {
        InvertedIndex index = smallIndex();
        ServiceDocument doc = index.document(0);
        HTMLParser parser = new HTMLParser(ServiceCategorizer.loadFile(Path.of("config", "categories.txt")));
        Map<String, Integer> vocabulary = Map.of("battery", 3, "replacement", 1, "windsor", 1);
        SpellChecker spell = new SpellChecker(vocabulary);
        WordCompleter completer = new WordCompleter(vocabulary);
        Trie trie = Trie.of(vocabulary);
        FrequencyCounter counter = new FrequencyCounter(index);
        SearchEngine engine = new SearchEngine(index, null, spell);
        RobotsTxtParser robots = RobotsTxtParser.parse("User-agent: *\nDisallow: /private", "CarCarePlus");

        List<Target> t = new ArrayList<>();
        // validation
        t.add(new Target("DataValidator.isValidPostalCode", DataValidator::isValidPostalCode));
        t.add(new Target("DataValidator.isValidCanadianPhone", DataValidator::isValidCanadianPhone));
        t.add(new Target("DataValidator.isValidEmail", DataValidator::isValidEmail));
        t.add(new Target("DataValidator.isValidUrl", DataValidator::isValidUrl));
        t.add(new Target("DataValidator.validateQuery", DataValidator::validateQuery));
        t.add(new Target("DataValidator.normalizePhone", DataValidator::normalizePhone));
        t.add(new Target("DataValidator.provinceForPostalCode", DataValidator::provinceForPostalCode));
        t.add(new Target("PatternExtractor.extractPhones", PatternExtractor::extractPhones));
        t.add(new Target("PatternExtractor.extractEmails", PatternExtractor::extractEmails));
        t.add(new Target("PatternExtractor.extractPostalCodes", PatternExtractor::extractPostalCodes));
        t.add(new Target("PatternExtractor.extractConnectorTypes", PatternExtractor::extractConnectorTypes));
        t.add(new Target("PatternExtractor.extractPowerRatings", PatternExtractor::extractPowerRatings));
        // crawler
        t.add(new Target("UrlNormalizer.normalize", UrlNormalizer::normalize));
        t.add(new Target("UrlNormalizer.normalize(base, href)", s -> UrlNormalizer.normalize("https://www.example.ca/", s)));
        t.add(new Target("UrlNormalizer.hostOf", UrlNormalizer::hostOf));
        t.add(new Target("UrlNormalizer.isInDomain", s -> UrlNormalizer.isInDomain(s, "example.ca")));
        t.add(new Target("LinkExtractor.extractLinks", LinkExtractor::extractLinks));
        t.add(new Target("LinkExtractor.extractSitemapUrls", LinkExtractor::extractSitemapUrls));
        t.add(new Target("RobotsTxtParser.parse", s -> RobotsTxtParser.parse(s, "CarCarePlus")));
        t.add(new Target("RobotsTxtParser.isAllowed", robots::isAllowed));
        t.add(new Target("CrawlConfig.fromLine", CrawlConfig::fromLine));
        // parser
        t.add(new Target("HTMLParser.parse(html)", s -> parser.parse(
                new CrawledPage("https://www.example.ca/x", "Example", 0, 200, "text/html", s, Instant.EPOCH))));
        t.add(new Target("ContentExtractor.cleanText", ContentExtractor::cleanText));
        // models
        t.add(new Target("VehicleType.fromUserInput", VehicleType::fromUserInput));
        t.add(new Target("SearchRequest.of", s -> SearchRequest.of(s, VehicleType.GAS)));
        t.add(new Target("new ServiceDocument", s -> new ServiceDocument(s, s)));
        // search
        t.add(new Target("Tokenizer.tokenize", Tokenizer::tokenize));
        t.add(new Target("Tokenizer.normalizeTerm", Tokenizer::normalizeTerm));
        t.add(new Target("InvertedIndex.lookup", index::lookup));
        t.add(new Target("InvertedIndex.positions", s -> index.positions(s, 0)));
        t.add(new Target("InvertedIndex.idOfUrl", index::idOfUrl));
        t.add(new Target("FrequencyCounter.countInDocument(word)", s -> counter.countInDocument(s, doc.getUrl())));
        t.add(new Target("FrequencyCounter.countInDocument(url)", s -> counter.countInDocument("battery", s)));
        t.add(new Target("LocationMatcher.matches", s -> LocationMatcher.matches(doc, s, index)));
        t.add(new Target("SearchEngine.search(query)", s -> engine.search(new SearchRequest(
                s, VehicleType.GAS, null, "", 10))));
        t.add(new Target("SearchEngine.search(location)", s -> engine.search(new SearchRequest(
                "battery", VehicleType.GAS, null, s, 10))));
        // query
        t.add(new Target("Trie.wordsWithPrefix", s -> trie.wordsWithPrefix(s, 5)));
        t.add(new Target("Trie.contains", trie::contains));
        t.add(new Target("Trie.insert", s -> new Trie().insert(s, 1)));
        t.add(new Target("WordCompleter.complete", s -> completer.complete(s, 5)));
        t.add(new Target("SpellChecker.suggest", s -> spell.suggest(s, 5)));
        t.add(new Target("SpellChecker.isKnown", spell::isKnown));
        t.add(new Target("LevenshteinDistance.distance", s -> LevenshteinDistance.distance(s, "battery")));
        t.add(new Target("LevenshteinDistance.distance(long, long)", s -> LevenshteinDistance.distance(s, s)));
        t.add(new Target("LevenshteinDistance.distanceWithin", s -> LevenshteinDistance.distanceWithin(s, s, 2)));
        return t;
    }

    @TestFactory
    Stream<DynamicTest> everyPublicTextMethodHandlesBadInputSafely() throws CarCareException {
        List<DynamicTest> tests = new ArrayList<>();
        for (Target target : targets()) {
            for (String input : INPUTS) {
                tests.add(DynamicTest.dynamicTest(target.name() + " <- " + describe(input),
                        safely(target, input)));
            }
        }
        return tests.stream();
    }

    private static Executable safely(Target target, String input) {
        return () -> {
            try {
                target.call().accept(input);
            } catch (InvalidInputException | CarCareException e) {
                if (e.getMessage() == null || e.getMessage().isBlank()) {
                    fail(target.name() + " threw " + e.getClass().getSimpleName() + " without a message");
                }
            } catch (Throwable e) {
                fail(target.name() + " threw " + e.getClass().getName() + ": " + e.getMessage());
            }
        };
    }

    private static String describe(String input) {
        if (input == null) {
            return "null";
        }
        return input.length() > 20 ? input.length() + " chars" : "\"" + input + "\"";
    }
}
