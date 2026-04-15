package coffeeshout.room.domain.player;

import coffeeshout.room.domain.menu.SelectedMenu;
import coffeeshout.room.domain.roulette.Probability;
import java.util.Objects;
import lombok.Getter;

@Getter
public class Player {

    private final PlayerName name;
    private PlayerType playerType;
    private SelectedMenu selectedMenu;
    private Boolean isReady;
    private Integer colorIndex;
    private Probability probability;

    private Player(
            PlayerName name,
            PlayerType playerType,
            SelectedMenu selectedMenu,
            Boolean isReady,
            Integer colorIndex,
            Probability probability
    ) {
        this.name = name;
        this.playerType = playerType;
        this.selectedMenu = selectedMenu;
        this.isReady = isReady;
        this.colorIndex = colorIndex;
        this.probability = probability;
    }

    public static Player createHost(PlayerName name, SelectedMenu selectedMenu) {
        return new Player(name, PlayerType.HOST, selectedMenu, true, null, null);
    }

    public static Player createGuest(PlayerName name, SelectedMenu selectedMenu) {
        return new Player(name, PlayerType.GUEST, selectedMenu, false, null, null);
    }

    /**
     * 저장소에서 읽어 온 값으로 Player를 복원하는 팩터리.
     */
    public static Player ofStored(
            final PlayerName name,
            final PlayerType playerType,
            final SelectedMenu selectedMenu,
            final Boolean isReady,
            final Integer colorIndex,
            final Probability probability
    ) {
        return new Player(name, playerType, selectedMenu, isReady, colorIndex, probability);
    }

    public void selectMenu(SelectedMenu selectedMenu) {
        this.selectedMenu = selectedMenu;
    }

    public boolean sameName(PlayerName playerName) {
        return Objects.equals(name, playerName);
    }

    public void updateReadyState(Boolean isReady) {
        this.isReady = isReady;
    }

    public void updateProbability(Probability probability) {
        this.probability = probability;
    }

    public void promote() {
        this.playerType = PlayerType.HOST;
        this.isReady = true;
    }

    public void assignColorIndex(int colorIndex) {
        this.colorIndex = colorIndex;
    }

    @Override
    public boolean equals(final Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof Player player)) {
            return false;
        }
        return Objects.equals(name, player.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }
}
