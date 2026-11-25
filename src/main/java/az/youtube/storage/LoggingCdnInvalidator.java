package az.youtube.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Local stand-in: open-source nginx (the local CDN edge) has no purge API. A deleted video's segments stay in the
 * edge cache until they are evicted (7 days without requests); new viewers cannot find the video, because the API
 * no longer returns its playback URL.
 */
@Component
public class LoggingCdnInvalidator implements CdnInvalidator {

    private static final Logger log = LoggerFactory.getLogger(LoggingCdnInvalidator.class);

    @Override
    public void invalidatePrefix(String pathPrefix) {
        log.info("CDN invalidation requested for /{}* (not supported by the local nginx edge)", pathPrefix);
    }
}
