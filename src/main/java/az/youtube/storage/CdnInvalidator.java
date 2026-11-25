package az.youtube.storage;

/**
 * Removes cached copies of a path from the CDN. Rendition files are cached as immutable for a year, so after a
 * delete the edge would keep serving them; production calls the CDN's invalidation API here (CloudFront
 * {@code CreateInvalidation} for {@code /streams/{id}/*}).
 */
public interface CdnInvalidator {

    void invalidatePrefix(String pathPrefix);
}
