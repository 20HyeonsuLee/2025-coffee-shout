package coffeeshout.room.domain.repository;

import coffeeshout.room.domain.JoinCode;
import coffeeshout.room.domain.Room;
import coffeeshout.room.domain.player.Player;
import coffeeshout.room.domain.player.PlayerName;
import java.util.Optional;

public interface RoomRepository {

    Optional<Room> findByJoinCode(JoinCode joinCode);

    boolean existsByJoinCode(JoinCode joinCode);

    Room save(Room room);

    void deleteByJoinCode(JoinCode joinCode);

    void addPlayer(JoinCode joinCode, Player player);

    void updatePlayerReady(JoinCode joinCode, PlayerName playerName, boolean ready);

    void removePlayer(JoinCode joinCode, PlayerName playerName);
}
