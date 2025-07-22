package coffeeshout.minigame.ui;

import coffeeshout.minigame.domain.MiniGameResult;
import coffeeshout.player.domain.Player;
import java.util.List;
import java.util.Map.Entry;
import lombok.NonNull;

public record MiniGameRanksMessage(
        List<MiniGameRankMessage> ranks
) {
    public record MiniGameRankMessage(
            String playerName,
            Integer rank
    ) {
        public static MiniGameRankMessage from(@NonNull Entry<Player, Integer> rankEntry) {
           return new MiniGameRankMessage(rankEntry.getKey().getName(), rankEntry.getValue());

        }
    }

    public static MiniGameRanksMessage from(@NonNull MiniGameResult miniGameResult) {
        final List<MiniGameRankMessage> message = miniGameResult.getRank().entrySet()
                .stream()
                .map(MiniGameRankMessage::from)
                .toList();
        return new MiniGameRanksMessage(message);
    }
}
