package coffeeshout.global.messaging;

/**
 * Redis Pub/Sub 채널 `coffeeshout:events`로 전달되는 flat envelope.
 * 도메인 이벤트 계층(RoomBaseEvent 다형)과 분리된 전파용 메시지.
 *
 * @param eventType        RoomEventType 문자열화 (예: "PLAYER_READY")
 * @param joinCode         대상 방 코드
 * @param payloadJson      eventType별 페이로드 (Jackson 직렬화)
 * @param publishedAt      발행 epoch millis (관측용)
 * @param originInstanceId 발행 WAS 식별자 (자기 메시지 필터용)
 */
public record PubSubEnvelope(
        String eventType,
        String joinCode,
        String payloadJson,
        long publishedAt,
        String originInstanceId
) {
}
