package az.youtube.transcoding;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Simulated engine: the original may be any bytes, so nothing is known and every rendition is planned. */
@Component
@ConditionalOnProperty(prefix = "youtube.transcoding", name = "engine", havingValue = "simulated", matchIfMissing = true)
public class SimulatedMediaProbe implements MediaProbe {

    @Override
    public Optional<SourceInfo> probe(String sourceKey) {
        return Optional.empty();
    }
}
