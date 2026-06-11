package coffeeshout.room.domain.service;

import coffeeshout.room.domain.JoinCode;
import coffeeshout.room.domain.Room;
import coffeeshout.room.domain.player.Player;
import coffeeshout.room.domain.player.PlayerName;
import coffeeshout.room.domain.repository.RoomRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
@Slf4j
public class RoomCommandService {

    private final RoomRepository roomRepository;
    private final RoomQueryService roomQueryService;

    public Room save(Room room) {
        return roomRepository.save(room);
    }

    public void delete(@NonNull JoinCode joinCode) {
        roomRepository.deleteByJoinCode(joinCode);
    }

    public void updatePlayerReady(final JoinCode joinCode, final PlayerName playerName, final boolean ready) {
        roomRepository.updatePlayerReady(joinCode, playerName, ready);
    }

    public void removePlayer(final JoinCode joinCode, final PlayerName playerName) {
        roomRepository.removePlayer(joinCode, playerName);
    }

    public Room joinGuest(JoinCode joinCode, PlayerName playerName) {
        log.info("JoinCode[{}] 게스트 입장 - 게스트 이름: {}", joinCode, playerName);
        final Room room = roomQueryService.getByJoinCode(joinCode);

        room.joinGuest(playerName);

        final Player guest = room.findPlayer(playerName);
        roomRepository.addPlayer(joinCode, guest);
        return room;
    }

    public Room saveIfAbsentRoom(JoinCode joinCode, PlayerName hostName) {
        if (roomRepository.existsByJoinCode(joinCode)) {
            log.warn("JoinCode[{}] 방 생성 실패 - 이미 존재하는 방", joinCode);
            return roomQueryService.getByJoinCode(joinCode);
        }

        log.info("JoinCode[{}] 방 생성 - 호스트 이름: {}", joinCode, hostName);

        final Room room = Room.createNewRoom(joinCode, hostName);

        return save(room);
    }
}
