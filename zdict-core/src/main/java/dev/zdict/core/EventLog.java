package dev.zdict.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory circular buffer of the last 50 lifecycle events, with SLF4J logging
 * and listener dispatch.
 */
public class EventLog {

    private static final Logger log = LoggerFactory.getLogger(EventLog.class);
    private static final int MAX_EVENTS = 50;

    private final Clock clock;
    private final ZdictMetricsListener metrics;
    private final List<ZdictEvent> events = new ArrayList<>();

    public EventLog() {
        this(Clock.systemUTC(), null);
    }

    public EventLog(Clock clock) {
        this(clock, null);
    }

    public EventLog(Clock clock, ZdictMetricsListener metrics) {
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.metrics = metrics;
    }

    public synchronized void record(String type, String group, String dictionaryId, String reason) {
        String timestamp = Instant.now(clock).toString();
        ZdictEvent event = new ZdictEvent(timestamp, type, group, dictionaryId, reason);

        if (events.size() >= MAX_EVENTS) {
            events.remove(0); // circular buffer: drop oldest beyond 50
        }
        events.add(event);

        log.info("zdict event [{}]: group='{}', dict='{}', reason='{}'", type, group, dictionaryId, reason);

        if (metrics != null) {
            metrics.recordEvent(type, group);
        }
    }

    public synchronized List<ZdictEvent> getRecentEvents() {
        return new ArrayList<>(events);
    }

    public synchronized ZdictEvent getLastEvent(String group) {
        if (group == null) return null;
        String slug = ZdictConstants.toGroupSlug(group);
        for (int i = events.size() - 1; i >= 0; i--) {
            ZdictEvent e = events.get(i);
            if (group.equals(e.getGroup()) || slug.equals(ZdictConstants.toGroupSlug(e.getGroup()))) {
                return e;
            }
        }
        return null;
    }

    public synchronized void clear() {
        events.clear();
    }
}
