package coffeeshout.global.luacommand.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 현재 스레드의 {@link coffeeshout.global.luacommand.annotation.RedisTransactional} 스코프 큐 관리.
 *
 * <p>{@link #begin()}으로 새 스코프를 시작하고, {@link #enqueue(QueuedCommand)}로 명령을 쌓으며,
 * {@link #drain()}으로 큐 내용을 꺼내고 {@link #end()}로 정리한다.
 *
 * <p>중첩 호출 시 outermost만 실제 스코프를 여닫으며, 내부는 depth counter로 무시한다.
 */
public final class CommandContext {

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private CommandContext() {
    }

    public static boolean begin() {
        final Scope scope = CURRENT.get();
        if (scope == null) {
            CURRENT.set(new Scope());
            return true;
        }
        scope.incrementDepth();
        return false;
    }

    public static void enqueue(final QueuedCommand cmd) {
        requireScope().add(cmd);
    }

    public static boolean isActive() {
        return CURRENT.get() != null;
    }

    public static List<QueuedCommand> drain() {
        return requireScope().drain();
    }

    public static boolean end() {
        final Scope scope = CURRENT.get();
        if (scope == null) {
            return false;
        }
        if (scope.hasNestedDepth()) {
            scope.decrementDepth();
            return false;
        }
        CURRENT.remove();
        return true;
    }

    public static void clear() {
        final Scope scope = CURRENT.get();
        if (scope != null) {
            scope.clearQueue();
        }
    }

    private static Scope requireScope() {
        final Scope scope = CURRENT.get();
        if (scope == null) {
            throw new IllegalStateException("No active RedisTransactional scope on this thread");
        }
        return scope;
    }

    static List<QueuedCommand> peekForTest() {
        final Scope scope = CURRENT.get();
        return scope == null ? Collections.emptyList() : scope.snapshot();
    }

    private static final class Scope {
        private final List<QueuedCommand> queue = new ArrayList<>();
        private int depth = 0;

        void add(final QueuedCommand cmd) {
            queue.add(cmd);
        }

        List<QueuedCommand> drain() {
            final List<QueuedCommand> copy = List.copyOf(queue);
            queue.clear();
            return copy;
        }

        void clearQueue() {
            queue.clear();
        }

        List<QueuedCommand> snapshot() {
            return List.copyOf(queue);
        }

        void incrementDepth() {
            depth++;
        }

        void decrementDepth() {
            depth--;
        }

        boolean hasNestedDepth() {
            return depth > 0;
        }
    }
}
