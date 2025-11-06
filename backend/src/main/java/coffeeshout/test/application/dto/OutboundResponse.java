package coffeeshout.test.application.dto;

import java.time.Instant;

public record OutboundResponse(Long id, Instant timestamp) {

    public static OutboundResponse from(Long id) {
        return new OutboundResponse(id, Instant.now());
    }
}
