package coffeeshout.global.luacommand.core;

/**
 * composite Lua의 실패 반환 코드를 (commandIndex, originalCode)로 분해한 결과.
 */
public record DecodedReturnCode(int commandIndex, int originalCode) {
}
