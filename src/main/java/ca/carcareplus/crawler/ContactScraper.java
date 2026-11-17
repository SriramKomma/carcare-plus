package ca.carcareplus.crawler;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.validation.DataValidator;
import ca.carcareplus.validation.PatternExtractor;

/**
 * Finds a shop's phone numbers and emails on the shop's OWN website (the address
 * OpenStreetMap lists for it), so drivers can contact more shops from CarCare+.
 *
 * What it visits, per shop: robots.txt, the homepage, and at most one page whose
 * address contains "contact" -- so at most {@value #MAX_PAGES_PER_SITE} pages.
 * Rules (same as the crawler): robots.txt is obeyed with WebCrawler's exact logic,
 * at least {@value #MIN_DELAY_MILLIS} ms between requests (or the site's
 * Crawl-delay if longer), and an error or block simply means "nothing found".
 *
 * What it keeps: only phone numbers and emails, found with our own regular
 * expressions (PatternExtractor) and from tel:/mailto: links -- never the page text.
 *
 * Which sites are skipped (selectSites):
 *  - social media and directories (facebook, yellowpages, ...): not the shop's own
 *    site, and their terms forbid scraping;
 *  - a website shared by several shops (a chain such as themufflerman.com): its
 *    contact page lists the head office, which would be the wrong number for a branch.
 */
public final class ContactScraper {

    static final int MAX_PAGES_PER_SITE = 2;
    static final long MIN_DELAY_MILLIS = 3000;
    /** More than this per kind usually means a list of branches, not one shop. */
    static final int MAX_PER_KIND = 3;

    /** Hosts that are never a shop's own website. */
    static final Set<String> NOT_SHOP_SITES = Set.of(
            "facebook.com", "instagram.com", "twitter.com", "x.com", "linkedin.com", "tiktok.com",
            "youtube.com", "google.com", "goo.gl", "yelp.com", "yelp.ca", "yellowpages.ca", "411.ca");

    /** North American toll-free area codes: usually a head office, not the branch. */
    static final Set<String> TOLL_FREE = Set.of("800", "833", "844", "855", "866", "877", "888");

    /** Email names that are not for customers (jobs, privacy, robots). */
    static final Set<String> NOT_FOR_CUSTOMERS = Set.of(
            "careers", "jobs", "join", "hr", "recruiting", "privacy", "noreply", "no-reply", "donotreply", "webmaster");

    private static final Logger LOG = Logger.getLogger(ContactScraper.class.getName());

    /** One shop to visit: its key (the OpenStreetMap URL) and its website. */
    public record Site(String shopUrl, String website) {
    }

    /**
     * What was found for one shop.
     *
     * @param sourcePage the page the details came from ("" if nothing was found)
     * @param note       why nothing was found, e.g. "robots.txt does not allow it"
     */
    public record Contact(String shopUrl, String website, List<String> phones, List<String> emails,
                          String sourcePage, String checkedAt, String note) {
        public Contact {
            phones = phones == null ? List.of() : List.copyOf(phones);
            emails = emails == null ? List.of() : List.copyOf(emails);
            sourcePage = sourcePage == null ? "" : sourcePage;
            note = note == null ? "" : note;
        }

        public boolean found() {
            return !phones.isEmpty() || !emails.isEmpty();
        }
    }

    private final PageFetcher fetcher;
    private final WebCrawler.Sleeper sleeper;
    private final Clock clock;

    public ContactScraper(PageFetcher fetcher) {
        this(fetcher, Thread::sleep, Clock.systemUTC());
    }

    /** Tests pass a sleeper that does not wait and a fixed clock. */
    ContactScraper(PageFetcher fetcher, WebCrawler.Sleeper sleeper, Clock clock) {
        if (fetcher == null || sleeper == null || clock == null) {
            throw new InvalidInputException("ContactScraper needs a fetcher, a sleeper and a clock.");
        }
        this.fetcher = fetcher;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    // ------------------------------------------------------------- choosing sites

    /**
     * The shops whose own website may be visited, skipping those in alreadyChecked.
     * Two passes over the shops: count shops per host (HashMap), then keep hosts
     * used by exactly one shop. O(S) for S shops.
     */
    public static List<Site> selectSites(List<ServiceDocument> shops, Set<String> alreadyChecked) {
        Map<String, Integer> shopsPerHost = new HashMap<>();
        for (ServiceDocument shop : shops) {
            String host = ownSiteHost(shop.getWebsite());
            if (host != null) {
                shopsPerHost.merge(host, 1, Integer::sum);
            }
        }
        List<Site> sites = new ArrayList<>();
        for (ServiceDocument shop : shops) {
            String host = ownSiteHost(shop.getWebsite());
            if (host == null || shopsPerHost.get(host) > 1
                    || (alreadyChecked != null && alreadyChecked.contains(shop.getUrl()))) {
                continue;
            }
            sites.add(new Site(shop.getUrl(), siteUrl(shop.getWebsite())));
        }
        return sites;
    }

    /** OSM website tags are often written without "https://": "www.joesauto.ca". */
    static String siteUrl(String website) {
        if (website == null || website.isBlank()) {
            return null;
        }
        String text = website.trim();
        return UrlNormalizer.normalize(text.matches("(?i)https?://.*") ? text : "https://" + text);
    }

    /** "www.joesauto.ca" -> "joesauto.ca"; null for no website or a non-shop site. */
    static String ownSiteHost(String website) {
        String url = siteUrl(website);
        String host = url == null ? null : UrlNormalizer.hostOf(url);
        if (host == null || host.isEmpty()) {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        for (String other : NOT_SHOP_SITES) {
            if (host.equals(other) || host.endsWith("." + other)) {
                return null;
            }
        }
        return host;
    }

    // ------------------------------------------------------------- visiting one site

    /** Visits one shop's site; never throws for network problems (they become a note). */
    public Contact scrape(Site site) {
        String checkedAt = Instant.now(clock).toString();
        String home = site.website();
        String origin = UrlNormalizer.originOf(home);
        WebCrawler.RobotsLookup robots = WebCrawler.fetchRobots(fetcher, origin);
        if (robots.problem() != null || !robots.rules().isAllowed(UrlNormalizer.pathAndQuery(home))) {
            return nothing(site, checkedAt, "robots.txt does not allow it");
        }
        long delay = Math.max(MIN_DELAY_MILLIS, robots.rules().crawlDelayMillis());

        if (!politeWait(delay)) {
            return nothing(site, checkedAt, "interrupted");
        }
        FetchResult homePage = fetcher.fetch(home);
        if (!homePage.isOk() || !homePage.isHtml()) {
            return nothing(site, checkedAt, "homepage: " + homePage.describe());
        }
        Found fromHome = extract(homePage.body());

        Found fromContact = new Found(List.of(), List.of());
        String contactUrl = contactPageUrl(homePage, robots.rules());
        if (contactUrl != null && politeWait(delay)) {
            FetchResult contactPage = fetcher.fetch(contactUrl);
            if (contactPage.isOk() && contactPage.isHtml()) {
                fromContact = extract(contactPage.body());
            }
        }

        // The contact page is the better source, so its details come first
        List<String> phones = firstFew(fromContact.phones(), fromHome.phones());
        List<String> emails = firstFew(fromContact.emails(), fromHome.emails());
        if (phones.isEmpty() && emails.isEmpty()) {
            return nothing(site, checkedAt, "no phone or email on the site");
        }
        String source = !fromContact.phones().isEmpty() || !fromContact.emails().isEmpty()
                ? contactUrl : homePage.finalUrl();
        return new Contact(site.shopUrl(), site.website(), phones, emails, source, checkedAt, "");
    }

    /** Visits every site in order (one at a time, with the delay between requests). */
    public List<Contact> scrapeAll(List<Site> sites) {
        List<Contact> contacts = new ArrayList<>();
        for (int i = 0; i < sites.size(); i++) {
            Site site = sites.get(i);
            int number = i + 1;
            LOG.info(() -> "[" + number + "/" + sites.size() + "] " + site.website());
            contacts.add(scrape(site));
        }
        return contacts;
    }

    private static Contact nothing(Site site, String checkedAt, String note) {
        return new Contact(site.shopUrl(), site.website(), List.of(), List.of(), "", checkedAt, note);
    }

    /** First link on the same site whose address contains "contact", if robots.txt allows it. */
    static String contactPageUrl(FetchResult homePage, RobotsTxtParser robots) {
        String base = homePage.finalUrl();
        String host = UrlNormalizer.hostOf(base);
        if (host == null) {
            return null;
        }
        for (String href : LinkExtractor.extractLinks(homePage.body())) {
            String url = UrlNormalizer.normalize(base, href);
            if (url == null || !host.equals(UrlNormalizer.hostOf(url)) || url.equals(base)) {
                continue;
            }
            String path = UrlNormalizer.pathAndQuery(url);
            if (path.toLowerCase(Locale.ROOT).contains("contact") && robots.isAllowed(path)) {
                return url;
            }
        }
        return null;
    }

    private record Found(List<String> phones, List<String> emails) {
    }

    /**
     * Phones and emails in a page: from tel:/mailto: links (the shop's own markup,
     * most reliable) and from the visible text. Scripts and styles are not text in
     * jsoup, so numbers inside JavaScript are not picked up.
     */
    static Found extract(String html) {
        Document page = Jsoup.parse(html);
        Set<String> phones = new LinkedHashSet<>();
        Set<String> emails = new LinkedHashSet<>();
        for (Element link : page.select("a[href]")) {
            String href = link.attr("href").trim();
            String lower = href.toLowerCase(Locale.ROOT);
            if (lower.startsWith("tel:")) {
                String phone = DataValidator.normalizePhone(href.substring(4));
                if (phone != null) {
                    phones.add(phone);
                }
            } else if (lower.startsWith("mailto:")) {
                String address = href.substring(7);
                int query = address.indexOf('?');
                String email = DataValidator.normalizeEmail(query < 0 ? address : address.substring(0, query));
                if (email != null) {
                    emails.add(email);
                }
            }
        }
        String text = page.text();
        phones.addAll(PatternExtractor.extractPhones(text));
        emails.addAll(PatternExtractor.extractEmails(text));
        return new Found(new ArrayList<>(phones), new ArrayList<>(emails));
    }

    private static List<String> firstFew(List<String> first, List<String> second) {
        Set<String> all = new LinkedHashSet<>(first);
        all.addAll(second);
        List<String> list = new ArrayList<>(all);
        return list.size() > MAX_PER_KIND ? list.subList(0, MAX_PER_KIND) : list;
    }

    /** @return false if the thread was interrupted */
    private boolean politeWait(long millis) {
        try {
            sleeper.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ------------------------------------------------------------- using the results

    /**
     * Scraped phones worth showing: toll-free numbers (usually a chain's head office)
     * only when no local number is known for the shop at all.
     */
    static List<String> forCustomers(List<String> scraped, List<String> known) {
        List<String> local = new ArrayList<>();
        for (String phone : scraped) {
            if (!isTollFree(phone)) {
                local.add(phone);
            }
        }
        boolean knowsLocal = known.stream().anyMatch(phone -> !isTollFree(phone));
        return local.isEmpty() && !knowsLocal ? scraped : local;
    }

    /** "(866) 400-4276" -> true. Phones are stored as "(XXX) XXX-XXXX". */
    static boolean isTollFree(String phone) {
        return phone.length() >= 4 && TOLL_FREE.contains(phone.substring(1, 4));
    }

    /** Drops addresses such as careers@ or noreply@: a driver should not write there. */
    static List<String> customerEmails(List<String> scraped) {
        List<String> kept = new ArrayList<>();
        for (String email : scraped) {
            String name = email.substring(0, Math.max(0, email.indexOf('@')));
            if (!NOT_FOR_CUSTOMERS.contains(name)) {
                kept.add(email);
            }
        }
        return kept;
    }

    /**
     * Adds found phones/emails to the matching shops (OpenStreetMap's own values stay
     * first) and records where they came from. Returns how many shops got new details.
     */
    public static int applyTo(List<ServiceDocument> shops, Map<String, Contact> contacts) {
        int updated = 0;
        for (ServiceDocument shop : shops) {
            Contact contact = contacts.get(shop.getUrl());
            if (contact == null || !contact.found()) {
                continue;
            }
            Set<String> phones = new LinkedHashSet<>(shop.getPhones());
            Set<String> emails = new LinkedHashSet<>(shop.getEmails());
            boolean added = phones.addAll(forCustomers(contact.phones(), shop.getPhones()))
                    | emails.addAll(customerEmails(contact.emails()));
            if (added) {
                shop.setPhones(new ArrayList<>(phones));
                shop.setEmails(new ArrayList<>(emails));
                shop.setContactSource(contact.sourcePage());
                updated++;
            }
        }
        return updated;
    }
}
