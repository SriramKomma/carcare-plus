package ca.carcareplus.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import ca.carcareplus.models.ServiceCategory;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.models.VehicleType;

/**
 * How one ServiceDocument looks inside documents.json.
 *
 * Why a separate record instead of letting Gson write ServiceDocument directly?
 * - Gson would fill ServiceDocument's private fields by reflection and skip its
 *   setters, so a damaged file could put null or blank values into a document.
 *   Here every loaded value goes back through the normal setters and their checks.
 * - Categories and vehicle types are stored as plain names ("BATTERY"). An unknown
 *   name (for example after renaming an enum constant) is skipped with a warning;
 *   Gson on its own would silently turn it into null.
 * - The JSON layout stays the same even if ServiceDocument's fields change later.
 *
 * id is an Integer (not int) so that a missing id reads as null, not as a real id 0.
 */
record StoredDocument(
        Integer id,
        String url,
        String sourceName,
        String crawledAt,
        String title,
        String description,
        List<String> headings,
        String bodyText,
        List<String> links,
        List<String> phones,
        List<String> emails,
        List<String> postalCodes,
        String address,
        String city,
        String province,
        List<String> categories,
        List<String> vehicleTypes,
        String contentHash,
        boolean testFixture) {

    private static final Logger LOG = Logger.getLogger(StoredDocument.class.getName());

    /** Copies a document into its stored form. */
    static StoredDocument from(ServiceDocument doc) {
        List<String> categoryNames = new ArrayList<>();
        for (ServiceCategory category : doc.getCategories()) {
            categoryNames.add(category.name());
        }
        List<String> vehicleNames = new ArrayList<>();
        for (VehicleType type : doc.getVehicleTypes()) {
            vehicleNames.add(type.name());
        }
        return new StoredDocument(
                doc.hasId() ? doc.getId() : null,
                doc.getUrl(),
                doc.getSourceName(),
                doc.getCrawledAt(),
                doc.getTitle(),
                doc.getDescription(),
                List.copyOf(doc.getHeadings()),
                doc.getBodyText(),
                List.copyOf(doc.getLinks()),
                List.copyOf(doc.getPhones()),
                List.copyOf(doc.getEmails()),
                List.copyOf(doc.getPostalCodes()),
                doc.getAddress(),
                doc.getCity(),
                doc.getProvince(),
                categoryNames,
                vehicleNames,
                doc.getContentHash(),
                doc.isTestFixture());
    }

    /**
     * Rebuilds a ServiceDocument. Missing (null) values become "" or empty lists
     * through the setters.
     *
     * @throws ca.carcareplus.exceptions.InvalidInputException if the URL is missing
     *         or the id is negative (the caller skips such an entry)
     */
    ServiceDocument toDocument() {
        ServiceDocument doc = new ServiceDocument(url, sourceName);
        if (id != null) {
            doc.setId(id);
        }
        doc.setCrawledAt(crawledAt);
        doc.setTitle(title);
        doc.setDescription(description);
        doc.setHeadings(headings);
        doc.setBodyText(bodyText);
        doc.setLinks(links);
        doc.setPhones(phones);
        doc.setEmails(emails);
        doc.setPostalCodes(postalCodes);
        doc.setAddress(address);
        doc.setCity(city);
        doc.setProvince(province);
        if (categories != null) {
            for (String name : categories) {
                doc.addCategory(enumOrNull(ServiceCategory.class, name));
            }
        }
        if (vehicleTypes != null) {
            for (String name : vehicleTypes) {
                doc.addVehicleType(enumOrNull(VehicleType.class, name));
            }
        }
        doc.setContentHash(contentHash);
        doc.setTestFixture(testFixture);
        return doc;
    }

    /** The enum constant with this name, or null (with a warning) if there is none. */
    private <E extends Enum<E>> E enumOrNull(Class<E> type, String name) {
        if (name == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOG.warning(() -> "Unknown " + type.getSimpleName() + " '" + name + "' in " + url + "; ignored.");
            return null;
        }
    }
}
