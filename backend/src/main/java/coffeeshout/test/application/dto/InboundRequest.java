package coffeeshout.test.application.dto;

import java.time.Instant;

public record InboundRequest(Long id, Instant timestamp) {

    public static InboundRequest from(Long id) {
        return new InboundRequest(id, Instant.now());
    }
}
