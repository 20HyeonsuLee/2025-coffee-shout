package coffeeshout.global.luacommand.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 여러 {@link QueuedCommand}를 합성해 단일 Lua 스크립트를 만든다.
 *
 * <p>합성 규칙:
 * <ol>
 *   <li>Phase 1: {@code validation=true}인 명령들의 스크립트를 선언 순서대로 배치</li>
 *   <li>Phase 2: {@code validation=false}인 명령들의 스크립트를 선언 순서대로 배치</li>
 *   <li>마지막에 {@code return 1} 추가</li>
 * </ol>
 *
 * <p>각 명령의 로컬 {@code KEYS[n]}/{@code ARGV[n]}은 composite 전체의 절대 인덱스로 치환.
 * {@code return -N}은 명령별 오프셋({@code cmdIndex * 100})을 더해 어떤 명령에서 실패했는지 구분.
 * KEYS/ARGV 인덱스는 <b>선언 순서(Phase 구분 없이)</b> 기준으로 할당된다.
 */
public final class CompositeLuaBuilder {

    static final int RETURN_CODE_OFFSET_MULTIPLIER = 100;

    private static final Pattern KEYS_PATTERN = Pattern.compile("\\bKEYS\\s*\\[\\s*(\\d+)\\s*\\]");
    private static final Pattern ARGV_PATTERN = Pattern.compile("\\bARGV\\s*\\[\\s*(\\d+)\\s*\\]");
    private static final Pattern RETURN_NEGATIVE_PATTERN = Pattern.compile("\\breturn\\s+-(\\d+)\\b");
    private static final String SUCCESS_RETURN = "return 1\n";

    private CompositeLuaBuilder() {
    }

    public static CompositeScript build(final List<QueuedCommand> commands) {
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("Cannot build composite from empty command list");
        }

        final Offsets offsets = computeOffsets(commands);
        final StringBuilder lua = new StringBuilder();

        appendPhase(lua, "Phase 1: validations", commands, offsets, QueuedCommand::isValidation, true);
        appendPhase(lua, "Phase 2: mutations", commands, offsets, QueuedCommand::isMutation, false);
        lua.append(SUCCESS_RETURN);

        return new CompositeScript(lua.toString(), flatten(commands, QueuedCommand::keys), flatten(commands, QueuedCommand::args));
    }

    public static DecodedReturnCode decodeReturnCode(final long returnCode) {
        if (returnCode >= 0) {
            throw new IllegalArgumentException("Positive return code cannot be decoded: " + returnCode);
        }
        final long abs = Math.abs(returnCode);
        final int cmdIndex = (int) (abs / RETURN_CODE_OFFSET_MULTIPLIER);
        final int originalCode = -(int) (abs % RETURN_CODE_OFFSET_MULTIPLIER);
        return new DecodedReturnCode(cmdIndex, originalCode);
    }

    private static Offsets computeOffsets(final List<QueuedCommand> commands) {
        final int[] keyOffsets = new int[commands.size()];
        final int[] argOffsets = new int[commands.size()];
        int keyAcc = 0;
        int argAcc = 0;
        for (int i = 0; i < commands.size(); i++) {
            keyOffsets[i] = keyAcc;
            argOffsets[i] = argAcc;
            keyAcc += commands.get(i).keys().size();
            argAcc += commands.get(i).args().size();
        }
        return new Offsets(keyOffsets, argOffsets);
    }

    private static void appendPhase(
            final StringBuilder lua,
            final String phaseLabel,
            final List<QueuedCommand> commands,
            final Offsets offsets,
            final Predicate<QueuedCommand> filter,
            final boolean withReturnOffset
    ) {
        lua.append("-- === ").append(phaseLabel).append(" ===\n");
        for (int i = 0; i < commands.size(); i++) {
            final QueuedCommand cmd = commands.get(i);
            if (!filter.test(cmd)) {
                continue;
            }
            final int returnOffset = withReturnOffset ? i * RETURN_CODE_OFFSET_MULTIPLIER : 0;
            lua.append("-- cmd#").append(i).append(" [").append(cmd.name()).append("]\n");
            lua.append(rewriteIndices(cmd.script(), offsets.keyOf(i), offsets.argOf(i), returnOffset));
            lua.append("\n");
        }
    }

    private static List<String> flatten(
            final List<QueuedCommand> commands,
            final Function<QueuedCommand, List<String>> extractor
    ) {
        final List<String> flat = new ArrayList<>();
        for (final QueuedCommand cmd : commands) {
            flat.addAll(extractor.apply(cmd));
        }
        return flat;
    }

    private static String rewriteIndices(
            final String block,
            final int keyOffset,
            final int argOffset,
            final int returnOffset
    ) {
        String result = block;
        result = replaceWithOffset(result, KEYS_PATTERN, "KEYS", keyOffset);
        result = replaceWithOffset(result, ARGV_PATTERN, "ARGV", argOffset);
        if (returnOffset > 0) {
            result = replaceReturnNegative(result, returnOffset);
        }
        return result;
    }

    private static String replaceWithOffset(
            final String block,
            final Pattern pattern,
            final String varName,
            final int offset
    ) {
        if (offset == 0) {
            return block;
        }
        return pattern.matcher(block).replaceAll(match ->
                Matcher.quoteReplacement(
                        varName + "[" + (Integer.parseInt(match.group(1)) + offset) + "]"
                )
        );
    }

    private static String replaceReturnNegative(final String block, final int returnOffset) {
        return RETURN_NEGATIVE_PATTERN.matcher(block).replaceAll(match ->
                Matcher.quoteReplacement(
                        "return -" + (Integer.parseInt(match.group(1)) + returnOffset)
                )
        );
    }

    private record Offsets(int[] keys, int[] args) {
        int keyOf(final int i) {
            return keys[i];
        }

        int argOf(final int i) {
            return args[i];
        }
    }
}
