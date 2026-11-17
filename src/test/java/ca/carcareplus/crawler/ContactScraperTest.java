package ca.carcareplus.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ca.carcareplus.crawler.ContactScraper.Contact;
import ca.carcareplus.crawler.ContactScraper.Site;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.StorageException;
import ca.carcareplus.models.ServiceDocument;

/** Contact scraping with a fake fetcher: no internet, no waiting. All sites are invented. */
class ContactScraperTest {

    private static final String HOME = "https://joesauto.example/";
    private static final String CONTACT = "https://joesauto.example/contact-us";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T20:00:00Z"), ZoneOffset.UTC);

    private final FakePageFetcher fetcher = new FakePageFetcher();
    private final List<Long> waits = new ArrayList<>();
    private final ContactScraper scraper = new ContactScraper(fetcher, waits::add, CLOCK);

    private static Site site() {
        return new Site("https://www.openstreetmap.org/node/1", HOME);
    }

    // ------------------------------------------------------------- one site

    @Test
    void contactPageDetailsComeFirstAndOnlyTwoPagesAreFetched() {
        fetcher.html(HOME, """
                <html><body><p>Joe's Auto - call <a href="tel:+1-519-555-0100">519-555-0100</a></p>
                <a href="/about">About</a> <a href="/contact-us">Contact us</a> <a href="/contact-form">Form</a>
                </body></html>""");
        fetcher.html(CONTACT, """
                <html><body><p>Office: (519) 555-0199</p>
                <a href="mailto:Service@JoesAuto.example?subject=Hello">Email us</a></body></html>""");

        Contact contact = scraper.scrape(site());

        assertTrue(contact.found());
        assertEquals(List.of("(519) 555-0199", "(519) 555-0100"), contact.phones(), "contact page first");
        assertEquals(List.of("service@joesauto.example"), contact.emails(), "mailto: query removed, lower case");
        assertEquals(CONTACT, contact.sourcePage());
        assertEquals("2026-11-17T20:00:00Z", contact.checkedAt());
        assertEquals(List.of("https://joesauto.example/robots.txt", HOME, CONTACT), fetcher.requested(),
                "robots.txt + homepage + one contact page, nothing else");
    }

    @Test
    void waitsAtLeastThreeSecondsOrTheSitesCrawlDelay() {
        fetcher.html("https://joesauto.example/robots.txt", "User-agent: *\nCrawl-delay: 7\n");
        fetcher.html(HOME, "<a href='/contact'>Contact</a> 519-555-0100");
        fetcher.html("https://joesauto.example/contact", "<p>519-555-0100</p>");

        scraper.scrape(site());

        assertEquals(List.of(7000L, 7000L), waits, "one wait before each page");
        waits.clear();
        new ContactScraper(new FakePageFetcher().html(HOME, "519-555-0100"), waits::add, CLOCK).scrape(site());
        assertEquals(List.of(ContactScraper.MIN_DELAY_MILLIS), waits);
    }

    @Test
    void robotsTxtThatDisallowsTheSiteMeansNoPageIsFetched() {
        fetcher.html("https://joesauto.example/robots.txt", "User-agent: *\nDisallow: /\n");
        fetcher.html(HOME, "519-555-0100");

        Contact contact = scraper.scrape(site());

        assertFalse(contact.found());
        assertEquals("robots.txt does not allow it", contact.note());
        assertEquals(List.of("https://joesauto.example/robots.txt"), fetcher.requested());
    }

    @Test
    void contactPageDisallowedByRobotsIsNotFetched() {
        fetcher.html("https://joesauto.example/robots.txt", "User-agent: *\nDisallow: /contact\n");
        fetcher.html(HOME, "<a href='/contact-us'>Contact</a> Call 519-555-0100");

        Contact contact = scraper.scrape(site());

        assertEquals(List.of("(519) 555-0100"), contact.phones());
        assertEquals(HOME, contact.sourcePage(), "found on the homepage");
        assertFalse(fetcher.requested().contains(CONTACT));
    }

    @Test
    void blockedHomepageIsANoteNotAnError() {
        fetcher.status(HOME, 403);
        Contact contact = scraper.scrape(site());
        assertFalse(contact.found());
        assertTrue(contact.note().startsWith("homepage:"), contact.note());
    }

    @Test
    void contactLinksToOtherSitesAreIgnored() {
        fetcher.html(HOME, "<a href='https://facebook.com/joes/contact'>Contact</a> 519-555-0100");
        scraper.scrape(site());
        assertEquals(2, fetcher.requested().size(), "robots.txt + homepage only");
    }

    @Test
    void numbersInsideScriptsAreNotPickedUp() {
        fetcher.html(HOME, "<script>var tracking = '519-555-0111';</script><p>No phone here.</p>");
        Contact contact = scraper.scrape(site());
        assertFalse(contact.found());
        assertEquals("no phone or email on the site", contact.note());
    }

    @Test
    void atMostThreePhonesAreKept() {
        fetcher.html(HOME, "519-555-0101 519-555-0102 519-555-0103 519-555-0104 519-555-0105");
        assertEquals(ContactScraper.MAX_PER_KIND, scraper.scrape(site()).phones().size());
    }

    @Test
    void scrapeAllVisitsEverySiteInOrder() {
        fetcher.html(HOME, "519-555-0100");
        List<Contact> contacts = scraper.scrapeAll(List.of(site(),
                new Site("https://www.openstreetmap.org/node/2", "https://other.example/")));
        assertEquals(2, contacts.size());
        assertTrue(contacts.get(0).found());
        assertFalse(contacts.get(1).found());
    }

    @Test
    void constructorRejectsMissingParts() {
        assertThrows(InvalidInputException.class, () -> new ContactScraper(null));
    }

    // ------------------------------------------------------------- choosing sites

    private static ServiceDocument shop(String id, String website) {
        ServiceDocument doc = new ServiceDocument("https://www.openstreetmap.org/node/" + id, "OpenStreetMap");
        doc.setWebsite(website);
        return doc;
    }

    @Test
    void onlyEachShopsOwnUnsharedWebsiteIsSelected() {
        List<ServiceDocument> shops = List.of(
                shop("1", "https://www.joesauto.example/"),
                shop("2", "www.bobsgarage.example"),                      // no scheme: still usable
                shop("3", "https://chain.example/store/11"),              // chain: 2 shops share this host
                shop("4", "https://www.chain.example/store/12"),
                shop("5", "https://www.facebook.com/somegarage"),         // social media: never
                shop("6", "https://www.yellowpages.ca/bus/123"),          // directory: never
                shop("7", ""),                                            // no website
                shop("8", "https://checked.example/"));                   // already done

        List<Site> sites = ContactScraper.selectSites(shops, Set.of("https://www.openstreetmap.org/node/8"));

        assertEquals(List.of(
                new Site("https://www.openstreetmap.org/node/1", "https://www.joesauto.example/"),
                new Site("https://www.openstreetmap.org/node/2", "https://www.bobsgarage.example/")), sites);
    }

    @Test
    void ownSiteHostDropsWwwAndRefusesNonShopSites() {
        assertEquals("joesauto.example", ContactScraper.ownSiteHost("https://WWW.JoesAuto.example/x"));
        assertNull(ContactScraper.ownSiteHost("https://m.facebook.com/x"));
        assertNull(ContactScraper.ownSiteHost("   "));
        assertNull(ContactScraper.ownSiteHost(null));
    }

    // ------------------------------------------------------------- using the results

    @Test
    void applyToAddsNewDetailsAfterOpenStreetMapsOwn() {
        ServiceDocument joe = shop("1", HOME);
        joe.setPhones(List.of("(519) 555-0100"));
        ServiceDocument other = shop("2", "");
        Map<String, Contact> contacts = Map.of(joe.getUrl(), new Contact(joe.getUrl(), HOME,
                List.of("(519) 555-0100", "(519) 555-0199"), List.of("service@joesauto.example"), CONTACT, "t", ""));

        assertEquals(1, ContactScraper.applyTo(List.of(joe, other), contacts));
        assertEquals(List.of("(519) 555-0100", "(519) 555-0199"), joe.getPhones());
        assertEquals(List.of("service@joesauto.example"), joe.getEmails());
        assertEquals(CONTACT, joe.getContactSource());
        assertEquals("", other.getContactSource());
        assertEquals(0, ContactScraper.applyTo(List.of(joe), contacts), "nothing new the second time");
    }

    @Test
    void tollFreeHeadOfficeNumbersGiveWayToLocalOnes() {
        // as found on 2026-11-17: a branch's local number next to a chain's 1-866 line
        assertEquals(List.of("(519) 455-3743"),
                ContactScraper.forCustomers(List.of("(866) 400-4276", "(519) 455-3743"), List.of()));
        assertEquals(List.of(), ContactScraper.forCustomers(List.of("(888) 988-8468"), List.of("(519) 668-8468")),
                "OSM already has a local number");
        assertEquals(List.of("(844) 469-4262"), ContactScraper.forCustomers(List.of("(844) 469-4262"), List.of()),
                "only a toll-free number: better than nothing");
        assertTrue(ContactScraper.isTollFree("(800) 555-0100"));
        assertFalse(ContactScraper.isTollFree("(519) 555-0100"));
    }

    @Test
    void jobAndNoReplyAddressesAreNotShown() {
        assertEquals(List.of("info@shop.example", "feedback@shop.example"), ContactScraper.customerEmails(
                List.of("info@shop.example", "join@shop.example", "feedback@shop.example", "noreply@shop.example")));
    }

    @Test
    void applyToUsesTheseRules() {
        ServiceDocument branch = shop("1", HOME);
        Map<String, Contact> contacts = Map.of(branch.getUrl(), new Contact(branch.getUrl(), HOME,
                List.of("(866) 400-4276", "(519) 455-3743"), List.of("join@csn.example", "info@csn.example"), HOME, "t", ""));
        ContactScraper.applyTo(List.of(branch), contacts);
        assertEquals(List.of("(519) 455-3743"), branch.getPhones());
        assertEquals(List.of("info@csn.example"), branch.getEmails());
    }

    // ------------------------------------------------------------- store

    @Test
    void storeRoundTripsAndStartsEmpty(@TempDir Path dir) throws Exception {
        ContactStore store = new ContactStore(dir.resolve("contacts").resolve("contacts.json"));
        assertTrue(store.load().isEmpty(), "no file yet");

        Map<String, Contact> contacts = new LinkedHashMap<>();
        contacts.put("a", new Contact("a", HOME, List.of("(519) 555-0100"), List.of(), HOME, "t", ""));
        contacts.put("b", new Contact("b", "https://x.example/", List.of(), List.of(), "", "t", "robots.txt does not allow it"));
        store.save(contacts);

        assertEquals(contacts, store.load());
    }

    @Test
    void corruptStoreIsReported(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("contacts.json");
        Files.writeString(file, "{not json");
        assertThrows(StorageException.class, () -> new ContactStore(file).load());
        assertThrows(InvalidInputException.class, () -> new ContactStore(null));
    }
}
