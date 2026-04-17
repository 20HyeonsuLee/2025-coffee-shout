package coffeeshout.room.application;

import coffeeshout.minigame.domain.MiniGameResult;
import coffeeshout.minigame.domain.MiniGameScore;
import coffeeshout.minigame.domain.MiniGameType;
import coffeeshout.room.domain.JoinCode;
import coffeeshout.room.domain.Playable;
import coffeeshout.room.domain.Room;
import coffeeshout.room.domain.event.PlayerKickEvent;
import coffeeshout.room.domain.event.RoomCreateEvent;
import coffeeshout.room.domain.player.Player;
import coffeeshout.room.domain.player.PlayerName;
import coffeeshout.room.domain.player.PlayerType;
import coffeeshout.room.domain.player.Winner;
import coffeeshout.room.domain.roulette.Roulette;
import coffeeshout.room.domain.roulette.RoulettePicker;
import coffeeshout.room.domain.service.JoinCodeGenerator;
import coffeeshout.room.domain.service.RoomCommandService;
import coffeeshout.room.domain.service.RoomQueryService;
import coffeeshout.room.infra.persistence.RoomEntity;
import coffeeshout.room.infra.persistence.RoomJpaRepository;
import coffeeshout.room.ui.response.ProbabilityResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class RoomService {

    private static final long LOCK_LEASE_SECONDS = 5;
    private static final String LOCK_PREFIX = "lock:room:";

    private final RoomQueryService roomQueryService;
    private final RoomCommandService roomCommandService;
    private final JoinCodeGenerator joinCodeGenerator;
    private final ApplicationEventPublisher roomEventPublisher;
    private final RoomJpaRepository roomJpaRepository;
    private final RedissonClient redissonClient;

    public RoomService(
            final RoomQueryService roomQueryService,
            final RoomCommandService roomCommandService,
            final JoinCodeGenerator joinCodeGenerator,
            final ApplicationEventPublisher roomEventPublisher,
            final RoomJpaRepository roomJpaRepository,
            final RedissonClient redissonClient
    ) {
        this.roomQueryService = roomQueryService;
        this.roomCommandService = roomCommandService;
        this.joinCodeGenerator = joinCodeGenerator;
        this.roomEventPublisher = roomEventPublisher;
        this.roomJpaRepository = roomJpaRepository;
        this.redissonClient = redissonClient;
    }

    @Transactional
    public Room createRoom(String hostName) {
        final JoinCode joinCode = joinCodeGenerator.generate();

        final Room room;
        final RLock lock = acquireLock(joinCode.getValue());
        try {
            room = roomCommandService.saveIfAbsentRoom(joinCode, new PlayerName(hostName));
        } finally {
            lock.unlock();
        }

        final RoomCreateEvent event = new RoomCreateEvent(hostName, joinCode.getValue());
        roomEventPublisher.publishEvent(event);
        saveRoomEntity(joinCode.getValue());
        log.info("방 생성 완료: eventId={}, joinCode={}", event.eventId(), event.joinCode());
        return room;
    }

    public List<Player> changePlayerReadyState(String joinCode, String playerName, Boolean isReady) {
        return changePlayerReadyStateInternal(joinCode, playerName, isReady);
    }

    public Winner spinRoulette(String joinCode, String hostName) {
        final Room room = roomQueryService.getByJoinCode(new JoinCode(joinCode));
        Player host = room.findPlayer(new PlayerName(hostName));
        return room.spinRoulette(host, new Roulette(new RoulettePicker()));
    }

    public Room getRoomByJoinCode(String joinCode) {
        return roomQueryService.getByJoinCode(new JoinCode(joinCode));
    }

    public List<Player> getPlayersInternal(String joinCode) {
        final Room room = roomQueryService.getByJoinCode(new JoinCode(joinCode));
        return room.getPlayers();
    }

    public void saveRoomEntity(String joinCodeValue) {
        final RoomEntity roomEntity = new RoomEntity(joinCodeValue);
        roomJpaRepository.save(roomEntity);
    }

    public Room enterRoom(String joinCode, String guestName) {
        final RLock lock = acquireLock(joinCode);
        try {
            return roomCommandService.joinGuest(new JoinCode(joinCode), new PlayerName(guestName));
        } finally {
            lock.unlock();
        }
    }

    public List<Player> changePlayerReadyStateInternal(String joinCode, String playerName, Boolean isReady) {
        final RLock lock = acquireLock(joinCode);
        try {
            final Room room = roomQueryService.getByJoinCode(new JoinCode(joinCode));
            final Player player = room.findPlayer(new PlayerName(playerName));

            if (player.getPlayerType() == PlayerType.HOST) {
                return room.getPlayers();
            }

            player.updateReadyState(isReady);
            roomCommandService.updatePlayerReady(new JoinCode(joinCode), new PlayerName(playerName), isReady);
            return room.getPlayers();
        } finally {
            lock.unlock();
        }
    }

    public List<MiniGameType> updateMiniGamesInternal(String joinCode, String hostName,
                                                      List<MiniGameType> miniGameTypes) {
        final RLock lock = acquireLock(joinCode);
        try {
            final Room room = roomQueryService.getByJoinCode(new JoinCode(joinCode));
            room.clearMiniGames();

            miniGameTypes.forEach(miniGameType -> {
                final Playable miniGame = miniGameType.createMiniGame(joinCode);
                room.addMiniGame(new PlayerName(hostName), miniGame);
            });

            roomCommandService.save(room);

            return room.getAllMiniGame().stream()
                    .map(Playable::getMiniGameType)
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    public List<Player> getAllPlayers(String joinCode) {
        final Room room = roomQueryService.getByJoinCode(new JoinCode(joinCode));
        return room.getPlayers();
    }

    public List<MiniGameType> updateMiniGames(String joinCode, String hostName, List<MiniGameType> miniGameTypes) {
        return updateMiniGamesInternal(joinCode, hostName, miniGameTypes);
    }

    public List<MiniGameType> getAllMiniGames() {
        return Arrays.stream(MiniGameType.values()).toList();
    }

    public boolean roomExists(String joinCode) {
        return roomQueryService.existsByJoinCode(new JoinCode(joinCode));
    }

    public boolean isGuestNameDuplicated(String joinCode, String guestName) {
        final Room room = roomQueryService.getByJoinCode(new JoinCode(joinCode));
        return room.hasDuplicatePlayerName(new PlayerName(guestName));
    }

    public List<ProbabilityResponse> getProbabilities(String joinCode) {
        final Room room = roomQueryService.getByJoinCode(new JoinCode(joinCode));
        return room.getPlayers().stream()
                .map(ProbabilityResponse::from)
                .toList();
    }

    public Map<Player, MiniGameScore> getMiniGameScores(String joinCode, MiniGameType miniGameType) {
        final Room room = roomQueryService.getByJoinCode(new JoinCode(joinCode));
        final Playable miniGame = room.findMiniGame(miniGameType);
        return miniGame.getScores();
    }

    public MiniGameResult getMiniGameRanks(String joinCode, MiniGameType miniGameType) {
        final Room room = roomQueryService.getByJoinCode(new JoinCode(joinCode));
        final Playable miniGame = room.findMiniGame(miniGameType);
        return miniGame.getResult();
    }

    public List<MiniGameType> getSelectedMiniGames(String joinCode) {
        final Room room = roomQueryService.getByJoinCode(new JoinCode(joinCode));
        return room.getSelectedMiniGameTypes();
    }

    public boolean removePlayer(String joinCode, String playerName) {
        final RLock lock = acquireLock(joinCode);
        try {
            final JoinCode code = new JoinCode(joinCode);
            final Room room = roomQueryService.getByJoinCode(code);
            final PlayerName name = new PlayerName(playerName);

            final boolean isRemoved = room.removePlayer(name);
            if (!isRemoved) {
                return false;
            }
            roomCommandService.removePlayer(code, name);
            if (room.isEmpty()) {
                roomCommandService.delete(code);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean isReadyState(String joinCode) {
        final Room room = roomQueryService.getByJoinCode(new JoinCode(joinCode));
        return room.isReadyState();
    }

    public Room showRoulette(String joinCode) {
        final Room room = roomQueryService.getByJoinCode(new JoinCode(joinCode));
        room.showRoulette();
        return room;
    }

    public boolean kickPlayer(String joinCode, String playerName) {
        final boolean exists = hasPlayer(joinCode, playerName);
        if (exists) {
            final PlayerKickEvent event = new PlayerKickEvent(joinCode, playerName);
            roomEventPublisher.publishEvent(event);
        }
        return exists;
    }

    private boolean hasPlayer(String joinCode, String playerName) {
        final Room room = roomQueryService.getByJoinCode(new JoinCode(joinCode));
        return room.hasPlayer(new PlayerName(playerName));
    }

    public List<Playable> getRemainingMiniGames(String joinCode) {
        final Room room = roomQueryService.getByJoinCode(new JoinCode(joinCode));
        return room.getMiniGames().stream().toList();
    }

    private RLock acquireLock(final String joinCode) {
        final RLock lock = redissonClient.getLock(LOCK_PREFIX + joinCode);
        lock.lock(LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        return lock;
    }
}
