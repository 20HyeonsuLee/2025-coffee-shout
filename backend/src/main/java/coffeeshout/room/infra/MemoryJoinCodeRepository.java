package coffeeshout.room.infra;

import coffeeshout.room.domain.JoinCode;
import coffeeshout.room.domain.repository.JoinCodeRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
public class MemoryJoinCodeRepository implements JoinCodeRepository {

    private final Map<JoinCode, Instant> store = new ConcurrentHashMap<>();
    private final Duration ttl;

    public MemoryJoinCodeRepository(@Value("${room.removalDelay}") final Duration ttl) {
        this.ttl = ttl;
    }

    @Override
    public synchronized boolean save(final JoinCode joinCode) {
        final Instant now = Instant.now();
        final Instant expiry = store.get(joinCode);
        if (expiry != null && expiry.isAfter(now)) {
            return false;
        }
        store.put(joinCode, now.plus(ttl));
        return true;
    }
}
