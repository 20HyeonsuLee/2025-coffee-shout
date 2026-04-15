package coffeeshout.global.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PubSubEnvelope ↔ JSON 직렬화 유틸.
 * 어댑터 계층에서만 사용. 도메인은 직접 의존 금지.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.context.annotation.Profile("!test")
public class PubSubEnvelopeSerializer {

    private final ObjectMapper objectMapper;

    public String toJson(final PubSubEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            log.error("Envelope 직렬화 실패: {}", envelope, e);
            throw new IllegalStateException("Envelope 직렬화 실패", e);
        }
    }

    public PubSubEnvelope fromJson(final String json) {
        try {
            return objectMapper.readValue(json, PubSubEnvelope.class);
        } catch (JsonProcessingException e) {
            log.error("Envelope 역직렬화 실패: json={}", json, e);
            throw new IllegalStateException("Envelope 역직렬화 실패", e);
        }
    }
}
