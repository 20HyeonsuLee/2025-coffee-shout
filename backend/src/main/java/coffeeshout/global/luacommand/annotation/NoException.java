package coffeeshout.global.luacommand.annotation;

/**
 * {@link ReturnCode#throwsException()}의 기본값 마커. "예외 안 던짐"을 의미한다.
 * 이 클래스는 실제로 인스턴스화되지 않는다.
 */
public final class NoException extends RuntimeException {

    private NoException() {
        super("NoException marker");
    }
}
