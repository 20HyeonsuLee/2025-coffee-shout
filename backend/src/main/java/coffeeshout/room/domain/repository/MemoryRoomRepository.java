package coffeeshout.room.domain.repository;

import static org.springframework.util.Assert.notNull;

import coffeeshout.global.exception.GlobalErrorCode;
import coffeeshout.global.exception.custom.NotExistElementException;
import coffeeshout.room.domain.JoinCode;
import coffeeshout.room.domain.Room;
import coffeeshout.room.domain.player.Player;
import coffeeshout.room.domain.player.PlayerName;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class MemoryRoomRepository implements RoomRepository {

    private final Map<JoinCode, Room> rooms;

    public MemoryRoomRepository() {
        this.rooms = new ConcurrentHashMap<>();
    }

    @Override
    public Optional<Room> findByJoinCode(JoinCode joinCode) {
        return Optional.ofNullable(rooms.get(joinCode));
    }

    @Override
    public boolean existsByJoinCode(JoinCode joinCode) {
        return rooms.containsKey(joinCode);
    }

    @Override
    public Room save(Room room) {
        rooms.put(room.getJoinCode(), room);
        return rooms.get(room.getJoinCode());
    }

    @Override
    public void deleteByJoinCode(JoinCode joinCode) {
        notNull(joinCode, "JoinCode는 null일 수 없습니다.");

        rooms.remove(joinCode);
    }

    @Override
    public void addPlayer(final JoinCode joinCode, final Player player) {
        // 메모리 구현에서는 Room 자체가 참조로 보관되므로 이미 join된 상태.
        // 정합성을 위해 존재 확인만 수행.
        requireRoom(joinCode);
    }

    @Override
    public void updatePlayerReady(final JoinCode joinCode, final PlayerName playerName, final boolean ready) {
        // Room 도메인 메서드로 이미 상태 반영됨. 존재 확인만.
        requireRoom(joinCode);
    }

    @Override
    public void removePlayer(final JoinCode joinCode, final PlayerName playerName) {
        requireRoom(joinCode);
    }

    private Room requireRoom(final JoinCode joinCode) {
        final Room room = rooms.get(joinCode);
        if (room == null) {
            throw new NotExistElementException(GlobalErrorCode.NOT_EXIST, "방이 존재하지 않습니다.");
        }
        return room;
    }
}
