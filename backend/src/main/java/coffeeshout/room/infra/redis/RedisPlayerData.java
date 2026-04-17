package coffeeshout.room.infra.redis;

import coffeeshout.room.domain.player.Player;

/**
 * Redis Hash에 저장되는 플레이어 정적 데이터 직렬화용 record (DTO).
 * 도메인 ↔ DTO 재조립은 RedisRoomMapper가 담당한다.
 * ready 상태는 READY_KEY Hash를 단일 출처로 분리해 여기서는 보관하지 않는다.
 */
public record RedisPlayerData(
        String playerName,
        String playerType,
        Integer colorIndex,
        Integer probability
) {

    public static RedisPlayerData from(final Player player) {
        return new RedisPlayerData(
                player.getName().value(),
                player.getPlayerType().name(),
                player.getColorIndex(),
                player.getProbability() != null ? player.getProbability().value() : 0
        );
    }
}
