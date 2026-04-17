package coffeeshout.room.infra.redis;

/**
 * {@link RoomLuaCommands}에서 던지는 Lua 반환 코드 매핑 예외.
 *
 * <p>라이브러리 계약(String 생성자)에 맞춰 단일 타입으로 묶고, {@code kind} 로 원인을 구분한다.
 * 상위 계층({@link RedisRoomRepository})이 kind 를 도메인 예외로 번역한다.
 */
public class RoomLuaException extends RuntimeException {

    public enum Kind {
        ROOM_NOT_FOUND,
        ROOM_ALREADY_EXISTS,
        ROOM_NOT_READY,
        ROOM_FULL,
        DUPLICATE_NAME,
        PLAYER_NOT_FOUND,
    }

    private final Kind kind;

    public RoomLuaException(final Kind kind, final String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static class RoomNotFound extends RoomLuaException {
        public RoomNotFound(final String message) {
            super(Kind.ROOM_NOT_FOUND, message);
        }
    }

    public static class RoomAlreadyExists extends RoomLuaException {
        public RoomAlreadyExists(final String message) {
            super(Kind.ROOM_ALREADY_EXISTS, message);
        }
    }

    public static class RoomNotReady extends RoomLuaException {
        public RoomNotReady(final String message) {
            super(Kind.ROOM_NOT_READY, message);
        }
    }

    public static class RoomFull extends RoomLuaException {
        public RoomFull(final String message) {
            super(Kind.ROOM_FULL, message);
        }
    }

    public static class DuplicateName extends RoomLuaException {
        public DuplicateName(final String message) {
            super(Kind.DUPLICATE_NAME, message);
        }
    }

    public static class PlayerNotFound extends RoomLuaException {
        public PlayerNotFound(final String message) {
            super(Kind.PLAYER_NOT_FOUND, message);
        }
    }
}
