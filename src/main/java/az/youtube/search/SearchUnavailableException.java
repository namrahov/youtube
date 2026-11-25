package az.youtube.search;

/** The search engine could not be reached or refused a request. Retried by consumers, 503 for the API. */
public class SearchUnavailableException extends RuntimeException {

    public SearchUnavailableException(String message) {
        super(message);
    }

    public SearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
