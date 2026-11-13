package ca.carcareplus.models;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import ca.carcareplus.exceptions.InvalidInputException;

/**
 * The structured, searchable version of one crawled page.
 *
 * Data flow:
 *   HTMLParser fills the text fields -> PatternExtractor adds phones/emails/postal codes
 *   -> ServiceCategorizer adds categories and vehicle types -> DocumentStorage assigns the id
 *   -> InvertedIndex indexes title/description/headings/bodyText by id.
 *
 * Rules that keep the rest of the code simple:
 *  - text fields are never null (null becomes "") and are trimmed
 *  - list fields never contain null or blank entries
 *  - getters return read-only views, so other classes cannot change a document by accident
 *  - two documents are equal when they have the same URL (used for duplicate detection)
 */
public class ServiceDocument {

    /** Id value before DocumentStorage assigns a real one. */
    public static final int UNASSIGNED_ID = -1;

    private int id = UNASSIGNED_ID;
    private String url;
    private String sourceName = "unknown";
    private String crawledAt = "";

    private String title = "";
    private String description = "";
    private List<String> headings = new ArrayList<>();
    private String bodyText = "";
    private List<String> links = new ArrayList<>();

    private List<String> phones = new ArrayList<>();
    private List<String> emails = new ArrayList<>();
    private List<String> postalCodes = new ArrayList<>();
    private String address = "";
    private String city = "";
    private String province = "";

    private Set<ServiceCategory> categories = EnumSet.noneOf(ServiceCategory.class);
    private Set<VehicleType> vehicleTypes = EnumSet.noneOf(VehicleType.class);

    private String contentHash = "";
    private boolean testFixture;

    // Only known for places from OpenStreetMap; web pages leave them empty
    private GeoPoint location;
    private String openingHours = "";
    private String website = "";

    /** Used only by the JSON library when loading saved documents (Phase 6). */
    private ServiceDocument() {
    }

    /**
     * @param url        page URL; required
     * @param sourceName source website name, e.g. "Electrify Canada"
     * @throws InvalidInputException if url is null or blank
     */
    public ServiceDocument(String url, String sourceName) {
        if (url == null || url.isBlank()) {
            throw new InvalidInputException("ServiceDocument URL must not be blank.");
        }
        this.url = url.trim();
        this.sourceName = (sourceName == null || sourceName.isBlank()) ? "unknown" : sourceName.trim();
    }

    // ---------- helpers ----------

    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }

    /** Copies a collection, dropping null/blank entries and trimming the rest. */
    private static List<String> cleanList(Collection<String> values) {
        List<String> result = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    result.add(value.trim());
                }
            }
        }
        return result;
    }

    // ---------- identity ----------

    public int getId() {
        return id;
    }

    public boolean hasId() {
        return id != UNASSIGNED_ID;
    }

    /** @throws InvalidInputException if id is negative */
    public void setId(int id) {
        if (id < 0) {
            throw new InvalidInputException("Document id must be 0 or greater: " + id);
        }
        this.id = id;
    }

    public String getUrl() {
        return url;
    }

    public String getSourceName() {
        return sourceName;
    }

    /** ISO-8601 time the page was crawled, e.g. "2026-11-06T14:30:00Z". */
    public String getCrawledAt() {
        return crawledAt;
    }

    public void setCrawledAt(String crawledAt) {
        this.crawledAt = clean(crawledAt);
    }

    // ---------- page text ----------

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = clean(title);
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = clean(description);
    }

    public List<String> getHeadings() {
        return Collections.unmodifiableList(headings);
    }

    public void setHeadings(Collection<String> headings) {
        this.headings = cleanList(headings);
    }

    public String getBodyText() {
        return bodyText;
    }

    public void setBodyText(String bodyText) {
        this.bodyText = clean(bodyText);
    }

    public List<String> getLinks() {
        return Collections.unmodifiableList(links);
    }

    public void setLinks(Collection<String> links) {
        this.links = cleanList(links);
    }

    // ---------- contact and location ----------

    public List<String> getPhones() {
        return Collections.unmodifiableList(phones);
    }

    public void setPhones(Collection<String> phones) {
        this.phones = cleanList(phones);
    }

    public List<String> getEmails() {
        return Collections.unmodifiableList(emails);
    }

    public void setEmails(Collection<String> emails) {
        this.emails = cleanList(emails);
    }

    public List<String> getPostalCodes() {
        return Collections.unmodifiableList(postalCodes);
    }

    public void setPostalCodes(Collection<String> postalCodes) {
        this.postalCodes = cleanList(postalCodes);
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = clean(address);
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = clean(city);
    }

    /** Two-letter province code, e.g. "ON"; empty if unknown. */
    public String getProvince() {
        return province;
    }

    public void setProvince(String province) {
        this.province = clean(province).toUpperCase(Locale.ROOT);
    }

    // ---------- classification ----------

    public Set<ServiceCategory> getCategories() {
        return Collections.unmodifiableSet(categories);
    }

    public void setCategories(Collection<ServiceCategory> categories) {
        this.categories = EnumSet.noneOf(ServiceCategory.class);
        if (categories != null) {
            for (ServiceCategory category : categories) {
                addCategory(category);
            }
        }
    }

    /** Adds one category; null is ignored. */
    public void addCategory(ServiceCategory category) {
        if (category != null) {
            categories.add(category);
        }
    }

    public Set<VehicleType> getVehicleTypes() {
        return Collections.unmodifiableSet(vehicleTypes);
    }

    public void setVehicleTypes(Collection<VehicleType> vehicleTypes) {
        this.vehicleTypes = EnumSet.noneOf(VehicleType.class);
        if (vehicleTypes != null) {
            for (VehicleType type : vehicleTypes) {
                addVehicleType(type);
            }
        }
    }

    /** Adds one vehicle type; null is ignored. */
    public void addVehicleType(VehicleType vehicleType) {
        if (vehicleType != null) {
            vehicleTypes.add(vehicleType);
        }
    }

    /**
     * True if this document is relevant for the given vehicle type.
     * A document with no vehicle types recorded is treated as relevant to both.
     */
    public boolean appliesTo(VehicleType vehicleType) {
        if (vehicleType == null) {
            return false;
        }
        return vehicleTypes.isEmpty() || vehicleTypes.contains(vehicleType);
    }

    /** True if the document was tagged with this category. */
    public boolean hasCategory(ServiceCategory category) {
        return category != null && categories.contains(category);
    }

    // ---------- bookkeeping ----------

    /** Hash of the cleaned text, used to detect the same content under two URLs. */
    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = clean(contentHash);
    }

    /** True for hand-made test data; such documents are labelled "[TEST FIXTURE]" in output. */
    /** @return the map position, or null if unknown */
    public GeoPoint getLocation() {
        return location;
    }

    public boolean hasLocation() {
        return location != null;
    }

    public void setLocation(GeoPoint location) {
        this.location = location;
    }

    /** OpenStreetMap format, e.g. "Mo-Fr 08:00-17:00; Sa 09:00-13:00"; "" if unknown. */
    public String getOpeningHours() {
        return openingHours;
    }

    public void setOpeningHours(String openingHours) {
        this.openingHours = clean(openingHours);
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = clean(website);
    }

    public boolean isTestFixture() {
        return testFixture;
    }

    public void setTestFixture(boolean testFixture) {
        this.testFixture = testFixture;
    }

    // ---------- equality ----------

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ServiceDocument document)) {
            return false;
        }
        return url.equals(document.url);
    }

    @Override
    public int hashCode() {
        return url.hashCode();
    }

    @Override
    public String toString() {
        return "ServiceDocument[id=" + id + ", title=" + title + ", url=" + url
                + ", source=" + sourceName + (testFixture ? ", TEST FIXTURE" : "") + "]";
    }
}
