package ai.teraq.roadguardian;

import java.util.HashSet;
import java.util.Set;

/**
 * 새로 준비된 비교 결과를 이번 실행 동안만 알린다
 */
public final class ComparisonNoticeState {
    private final Set<String> seen = new HashSet<>();
    private final Set<String> pending = new java.util.TreeSet<>();
    private boolean initialized;
    private long sequence;

    public long getSequence() {
        return sequence;
    }

    public boolean freshForSpeech(long now) {
        return active(now) && now - startedAt < 5000;
    }

    private String activeId;
    private long startedAt = -1, observedAt = -1;

    public void invalidate() {
        initialized = false;
        activeId = null;
        startedAt = observedAt = -1;
    }

    public int pendingCount(long now) {
        return current(now) ? pending.size() : 0;
    }

    public String nextPending(long now) {
        return pendingCount(now) == 0 ? null : pending.iterator().next();
    }

    public void acknowledge(String id) {
        pending.remove(id);
    }

    private boolean current(long now) {
        return initialized && observedAt >= 0 && now >= observedAt && now - observedAt < 10000;
    }

    public void accept(Set<String> eligible, long now) {
        if (now < 0 || (observedAt >= 0 && now < observedAt)) {
            invalidate();
            return;
        }
        pending.retainAll(eligible);
        if (initialized) for (String id : eligible) if (!seen.contains(id)) pending.add(id);
        if (!initialized) {
            seen.addAll(eligible);
            initialized = true;
            activeId = null;
        } else {
            if (activeId != null && (!eligible.contains(activeId) || now - startedAt >= 20000))
                activeId = null;
            for (String id : eligible)
                if (activeId == null && !seen.contains(id)) {
                    activeId = id;
                    if (startedAt < 0 || now - startedAt >= 20000) {
                        startedAt = now;
                        sequence++;
                    }
                    break;
                }
            seen.addAll(eligible);
        }
        observedAt = now;
    }

    public boolean active(long now) {
        return activeId != null && startedAt >= 0 && observedAt >= 0 && now >= observedAt && now >= startedAt
                && now - startedAt < 20000 && now - observedAt < 10000;
    }
}
