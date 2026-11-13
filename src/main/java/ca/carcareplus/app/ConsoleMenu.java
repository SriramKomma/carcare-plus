package ca.carcareplus.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.StorageException;
import ca.carcareplus.models.SearchRequest;
import ca.carcareplus.models.ServiceCategory;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.models.VehicleType;
import ca.carcareplus.query.SpellChecker;
import ca.carcareplus.search.SearchFrequency.TermCount;
import ca.carcareplus.search.SearchOutcome;
import ca.carcareplus.validation.DataValidator;

/**
 * The interactive console of CarCare+ (presentation only: every feature it shows
 * lives in SearchSystem and the classes it wires together).
 *
 * <pre>
 * Main menu
 *  1 Find help        vehicle type -> category -> problem words -> location -> ranked results
 *                     ("bat?" shows completions; misspelled words offer a corrected search)
 *  2 Word completion  Trie
 *  3 Spell check      Levenshtein edit distance
 *  4 Word frequency   how often a word occurs on one page / most frequent words on it
 *  5 Search history   most searched words (saved to a file on exit)
 *  0 Exit
 * </pre>
 *
 * Input and output are passed in (not System.in / System.out directly), so tests
 * can run a whole session from a string and check what was printed.
 */
public class ConsoleMenu {

    static final int COMPLETIONS_SHOWN = 5;
    static final int SPELLING_SUGGESTIONS_SHOWN = 5;
    static final int TOP_ITEMS_SHOWN = 10;

    /** Typed at the end of a word to ask for completions, e.g. "bat?". */
    static final String COMPLETION_MARK = "?";

    /**
     * Thrown when the input ends (Ctrl+D, or the end of a test script) so that
     * every nested prompt can stop at once and the menu can still save history.
     */
    private static final class EndOfInput extends RuntimeException {
        EndOfInput() {
            super("End of input");
        }
    }

    private final SearchSystem system;
    private final BufferedReader in;
    private final PrintStream out;

    public ConsoleMenu(SearchSystem system, BufferedReader in, PrintStream out) {
        if (system == null || in == null || out == null) {
            throw new InvalidInputException("ConsoleMenu needs a search system, an input and an output.");
        }
        this.system = system;
        this.in = in;
        this.out = out;
    }

    /**
     * Shows the main menu until the user chooses Exit or the input ends, then
     * saves the search history.
     *
     * @throws StorageException if the search history cannot be saved
     */
    public void run() throws StorageException {
        out.println("Loaded " + system.documents().size() + " pages and "
                + system.spellChecker().vocabularySize() + " distinct words.");
        try {
            boolean running = true;
            while (running) {
                printMainMenu();
                String choice = ask("Choose an option: ");
                try {
                    switch (choice) {
                        case "1" -> findHelp();
                        case "2" -> wordCompletion();
                        case "3" -> spellCheck();
                        case "4" -> wordFrequencyOnPage();
                        case "5" -> searchHistory();
                        case "0" -> running = false;
                        default -> out.println("Please enter a number from 0 to 5.");
                    }
                } catch (InvalidInputException e) {
                    // A bad value never ends the program: show the message, back to the menu
                    out.println("Error: " + e.getMessage());
                }
            }
        } catch (EndOfInput e) {
            out.println();
        }
        system.saveHistory();
        out.println("Search history saved. Goodbye!");
    }

    private void printMainMenu() {
        out.println();
        out.println("===== CarCare+ main menu =====");
        out.println("1. Find help for my vehicle");
        out.println("2. Word completion");
        out.println("3. Spell check a word");
        out.println("4. Word frequency on a page");
        out.println("5. Search history (most searched words)");
        out.println("0. Exit");
    }

    // ---------------------------------------------------------------- 1. search

    private void findHelp() {
        VehicleType vehicle = chooseVehicle();
        if (vehicle == null) {
            return;
        }
        ServiceCategory category = chooseCategory(vehicle);
        if (category == null) {
            return;
        }
        String query = askQuery(defaultQueryFor(category));
        String location = askLocation();

        SearchRequest request = new SearchRequest(query, vehicle, category, location,
                SearchRequest.DEFAULT_MAX_RESULTS);
        SearchOutcome outcome = search(request);

        // Spell checking: offer the corrected search once
        if (!outcome.suggestions().isEmpty()) {
            String corrected = correctedQuery(outcome.terms(), outcome.suggestions());
            if (askYesNo("Search again for \"" + corrected + "\"? (y/n): ")) {
                search(new SearchRequest(corrected, vehicle, category, location, SearchRequest.DEFAULT_MAX_RESULTS));
            }
        }
    }

    private SearchOutcome search(SearchRequest request) {
        SearchOutcome outcome = system.engine().search(request);
        out.println();
        out.print(ResultFormatter.format(request, outcome));
        return outcome;
    }

    /** @return the chosen vehicle type, or null for "back" */
    private VehicleType chooseVehicle() {
        out.println();
        out.println("What do you drive?");
        out.println("1. " + VehicleType.EV.displayName());
        out.println("2. " + VehicleType.GAS.displayName());
        out.println("0. Back");
        while (true) {
            switch (ask("Choose: ")) {
                case "1": return VehicleType.EV;
                case "2": return VehicleType.GAS;
                case "0": return null;
                default: out.println("Please enter 1, 2 or 0.");
            }
        }
    }

    /** @return the chosen category, or null for "back" */
    private ServiceCategory chooseCategory(VehicleType vehicle) {
        List<ServiceCategory> options = categoryOptions(vehicle);
        out.println();
        out.println("What do you need help with?");
        for (int i = 0; i < options.size(); i++) {
            ServiceCategory category = options.get(i);
            String label = category == ServiceCategory.EV_CHARGING ? "Find EV charging stations" : category.displayName();
            out.println((i + 1) + ". " + label);
        }
        out.println("0. Back");
        int choice = askNumber("Choose: ", options.size());
        return choice == 0 ? null : options.get(choice - 1);
    }

    /** Repair categories for the vehicle; EV drivers also get "Find EV charging stations". */
    static List<ServiceCategory> categoryOptions(VehicleType vehicle) {
        List<ServiceCategory> options = new ArrayList<>(ServiceCategory.repairCategoriesFor(vehicle));
        if (ServiceCategory.EV_CHARGING.appliesTo(vehicle)) {
            options.add(ServiceCategory.EV_CHARGING);
        }
        return options;
    }

    /**
     * Asks for the problem words until they are valid. Enter alone uses the
     * category name; a word ending in "?" shows completions to pick from.
     */
    private String askQuery(String defaultQuery) {
        String hint = defaultQuery.isEmpty() ? "" : " (Enter = \"" + defaultQuery + "\")";
        out.println("Describe the problem" + hint + ". End a word with " + COMPLETION_MARK
                + " to see completions, e.g. bat" + COMPLETION_MARK);
        while (true) {
            String line = ask("Search: ");
            if (line.isEmpty()) {
                if (!defaultQuery.isEmpty()) {
                    return defaultQuery;
                }
                out.println("Please type a few words, e.g. \"battery dead\".");
                continue;
            }
            if (line.endsWith(COMPLETION_MARK)) {
                String picked = pickCompletion(line.substring(0, line.length() - COMPLETION_MARK.length()));
                if (picked == null) {
                    continue;
                }
                line = picked;
            }
            try {
                return DataValidator.validateQuery(line);
            } catch (InvalidInputException e) {
                out.println("Error: " + e.getMessage());
            }
        }
    }

    /** @return the completion the user picked, or null to type again */
    private String pickCompletion(String typed) {
        List<String> completions = system.completer().complete(typed, COMPLETIONS_SHOWN);
        if (completions.isEmpty()) {
            out.println("No completions for '" + typed + "'. Please type again.");
            return null;
        }
        for (int i = 0; i < completions.size(); i++) {
            out.println("  " + (i + 1) + ". " + completions.get(i));
        }
        out.println("  0. None of these (type again)");
        int choice = askNumber("Pick one: ", completions.size());
        if (choice == 0) {
            return null;
        }
        out.println("Searching for: " + completions.get(choice - 1));
        return completions.get(choice - 1);
    }

    private String askLocation() {
        while (true) {
            String line = ask("Location - city or postal code (Enter = anywhere): ");
            try {
                return DataValidator.validateLocation(line);
            } catch (InvalidInputException e) {
                out.println("Error: " + e.getMessage());
            }
        }
    }

    /**
     * Search words for a category when the user just presses Enter:
     * "Brake problem" -> "brake problem repair". OTHER has no useful words, so it
     * returns "" (the user must type something).
     *
     * Repair categories add "repair": the search uses OR, so shops that list
     * brakes rank first, and general repair shops (most OpenStreetMap shops list
     * no specific services) still appear after them.
     */
    static String defaultQueryFor(ServiceCategory category) {
        if (category == null || category == ServiceCategory.OTHER) {
            return "";
        }
        // Keep only characters a query may contain (see DataValidator.validateQuery)
        String words = category.displayName().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 '\\-]", " ")
                .trim()
                .replaceAll("\\s+", " ");
        return category.isRepair() && !words.contains("repair") ? words + " repair" : words;
    }

    /**
     * Replaces every misspelled term with its best suggestion:
     * terms [battrey, dead], suggestions {battrey=[battery]} -> "battery dead".
     */
    static String correctedQuery(List<String> terms, Map<String, List<String>> suggestions) {
        List<String> words = new ArrayList<>();
        for (String term : terms) {
            List<String> options = suggestions.get(term);
            words.add(options == null || options.isEmpty() ? term : options.get(0));
        }
        return String.join(" ", words);
    }

    // ------------------------------------------------------- 2. word completion

    private void wordCompletion() {
        String typed = ask("Start of a word: ");
        List<String> completions = system.completer().complete(typed, COMPLETIONS_SHOWN);
        if (completions.isEmpty()) {
            out.println("No completions for '" + typed + "'.");
            return;
        }
        out.println("Most frequent words starting with '" + typed + "':");
        for (String completion : completions) {
            out.println("  " + completion);
        }
    }

    // ------------------------------------------------------------ 3. spell check

    private void spellCheck() {
        String word = ask("Word to check: ");
        if (word.isEmpty() || word.contains(" ")) {
            throw new InvalidInputException("Please type exactly one word.");
        }
        SpellChecker checker = system.spellChecker();
        if (checker.isKnown(word)) {
            out.println("'" + word + "' appears in the pages - spelled correctly.");
            return;
        }
        int maxDistance = SpellChecker.defaultMaxDistance(word);
        List<SpellChecker.Suggestion> suggestions =
                checker.suggestions(word, maxDistance, SPELLING_SUGGESTIONS_SHOWN);
        if (suggestions.isEmpty()) {
            out.println("'" + word + "' is not in the pages, and no word is within "
                    + maxDistance + " edit(s) of it.");
            return;
        }
        out.println("'" + word + "' is not in the pages. Did you mean:");
        for (SpellChecker.Suggestion s : suggestions) {
            out.println("  " + s.word() + "  (edit distance " + s.distance()
                    + ", appears " + s.frequency() + " time(s))");
        }
    }

    // --------------------------------------------------------- 4. word frequency

    private void wordFrequencyOnPage() {
        List<ServiceDocument> pages = system.documents();
        out.println();
        out.println("Pages:");
        for (int i = 0; i < pages.size(); i++) {
            ServiceDocument page = pages.get(i);
            out.println((i + 1) + ". " + (page.isTestFixture() ? ResultFormatter.FIXTURE_LABEL : "")
                    + page.getTitle() + "  " + page.getUrl());
        }
        out.println("0. Back");
        int choice = askNumber("Choose a page: ", pages.size());
        if (choice == 0) {
            return;
        }
        String url = pages.get(choice - 1).getUrl();
        String word = ask("Word to count (Enter = most frequent words on the page): ");
        if (word.isEmpty()) {
            out.println("Most frequent words on " + url + ":");
            system.frequencyCounter().topTerms(url, TOP_ITEMS_SHOWN)
                    .forEach((term, count) -> out.println("  " + term + "  " + count));
        } else {
            int count = system.frequencyCounter().countInDocument(word, url);
            out.println("'" + word + "' occurs " + count + " time(s) on " + url);
        }
    }

    // ---------------------------------------------------------- 5. search history

    private void searchHistory() {
        List<TermCount> top = system.searchFrequency().topSearches(TOP_ITEMS_SHOWN);
        if (top.isEmpty()) {
            out.println("No searches yet.");
            return;
        }
        out.println("Most searched words:");
        for (TermCount entry : top) {
            out.println("  " + entry.term() + "  " + entry.count());
        }
    }

    // --------------------------------------------------------------- input helpers

    /** Prints the prompt and returns the next line, trimmed. */
    private String ask(String prompt) {
        out.print(prompt);
        out.flush();
        String line;
        try {
            line = in.readLine();
        } catch (IOException e) {
            line = null; // a broken input is treated like the end of input
        }
        if (line == null) {
            throw new EndOfInput();
        }
        return line.trim();
    }

    /** Asks until the user types a whole number from 0 to max. */
    private int askNumber(String prompt, int max) {
        while (true) {
            String line = ask(prompt);
            try {
                int number = Integer.parseInt(line);
                if (number >= 0 && number <= max) {
                    return number;
                }
            } catch (NumberFormatException e) {
                // fall through to the message below
            }
            out.println("Please enter a number from 0 to " + max + ".");
        }
    }

    private boolean askYesNo(String prompt) {
        while (true) {
            String line = ask(prompt).toLowerCase(Locale.ROOT);
            if (line.equals("y") || line.equals("yes")) {
                return true;
            }
            if (line.equals("n") || line.equals("no")) {
                return false;
            }
            out.println("Please answer y or n.");
        }
    }
}
