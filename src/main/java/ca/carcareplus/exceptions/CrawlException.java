package ca.carcareplus.exceptions;

/**
 * A crawl could not continue for a whole source (for example robots.txt forbids it,
 * the seed URL is invalid, or the site keeps answering 403/429).
 * Single failed pages are logged and skipped instead of throwing this.
 */
public class CrawlException extends CarCareException {

    private static final long serialVersionUID = 1L;

    public CrawlException(String message) {
        super(message);
    }

    public CrawlException(String message, Throwable cause) {
        super(message, cause);
    }
}
