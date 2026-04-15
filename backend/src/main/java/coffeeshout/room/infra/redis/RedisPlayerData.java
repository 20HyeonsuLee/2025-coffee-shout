package coffeeshout.room.infra.redis;

import coffeeshout.room.domain.player.Player;

/**
 * Redis Hash에 저장되는 플레이어 데이터 직렬화용 record (DTO).
 * 도메인 ↔ DTO 재조립은 RedisRoomMapper가 담당한다.
 */
public record RedisPlayerData(
        String playerName,
        String playerType,
        String menuName,
        String menuCategoryImageUrl,
        String temperature,
        Boolean isReady,
        Integer colorIndex,
        Integer probability
) {

    public static RedisPlayerData from(final Player player) {
        return new RedisPlayerData(
                player.getName().value(),
                player.getPlayerType().name(),
                player.getSelectedMenu().menu().getName(),
                player.getSelectedMenu().menu().getCategoryImageUrl(),
                player.getSelectedMenu().menuTemperature().name(),
                player.getIsReady(),
                player.getColorIndex(),
                player.getProbability() != null ? player.getProbability().value() : 0
        );
    }
}
