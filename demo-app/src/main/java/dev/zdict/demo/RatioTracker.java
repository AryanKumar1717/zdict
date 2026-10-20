package dev.zdict.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks compression ratios (original / compressed) and failures per (group, dictId)
 * in a rolling window of size 500, and evaluates whether endpoint groups are drifting.
 * State changes per group are synchronized.
 */
@Component
public class RatioTracker {

    private final DictionaryRegistry registry;
    private final ZdictProperties props;
    private final Clock clock;
    private final ZdictMetrics metrics;

    // group slug -> GroupRatioStats
    private final ConcurrentHashMap<String, GroupRatioStats> groupStats = new ConcurrentHashMap<>();

    @Autowired
    public RatioTracker(DictionaryRegistry registry,
                        ZdictProperties props,
                        @Autowired(required = false) Clock clock,
                        @Autowired(required = false) ZdictMetrics metrics) {
        this.registry = registry;
        this.props = props;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.metrics = metrics;
    }

    public RatioTracker(DictionaryRegistry registry, ZdictProperties props, Clock clock) {
        this(registry, props, clock, null);
    }

    public RatioTracker(DictionaryRegistry registry, ZdictProperties props) {
        this(registry, props, Clock.systemUTC(), null);
    }

    public RatioTracker(ZdictProperties props) {
        this(null, props, Clock.systemUTC(), null);
    }

    private GroupRatioStats getGroupStats(String group) {
        String slug = ZstdDictFilter.toGroupSlug(group);
        return groupStats.computeIfAbsent(slug, k -> new GroupRatioStats(props.getDrift().getWindowSize()));
    }

    public void recordSuccess(String group, String dictId, long origBytes, long compBytes) {
        if (group == null || dictId == null || compBytes <= 0) {
            return;
        }
        double ratio = (double) origBytes / compBytes;
        GroupRatioStats gs = getGroupStats(group);
        double mean;
        synchronized (gs) {
            gs.recordSuccess(dictId, ratio, origBytes, compBytes);
            mean = gs.getRollingMean(dictId);
        }
        if (metrics != null) {
            metrics.updateRollingRatio(group, mean);
        }
    }

    public void recordSuccess(String group, String dictId, double ratio) {
        if (group == null || dictId == null) {
            return;
        }
        GroupRatioStats gs = getGroupStats(group);
        double mean;
        synchronized (gs) {
            gs.recordSuccess(dictId, ratio);
            mean = gs.getRollingMean(dictId);
        }
        if (metrics != null) {
            metrics.updateRollingRatio(group, mean);
        }
    }

    public void recordFailure(String group, String dictId) {
        if (group == null || dictId == null) {
            return;
        }
        GroupRatioStats gs = getGroupStats(group);
        synchronized (gs) {
            gs.recordFailure(dictId);
        }
    }

    public double getRollingMean(String group, String dictId) {
        if (group == null || dictId == null) {
            return 0.0;
        }
        GroupRatioStats gs = getGroupStats(group);
        synchronized (gs) {
            return gs.getRollingMean(dictId);
        }
    }

    public int getResponseCount(String group, String dictId) {
        if (group == null || dictId == null) {
            return 0;
        }
        GroupRatioStats gs = getGroupStats(group);
        synchronized (gs) {
            return gs.getResponseCount(dictId);
        }
    }

    public long getFailureCount(String group, String dictId) {
        if (group == null || dictId == null) {
            return 0;
        }
        GroupRatioStats gs = getGroupStats(group);
        synchronized (gs) {
            return gs.getFailureCount(dictId);
        }
    }

    public double getFailureRate(String group, String dictId) {
        if (group == null || dictId == null) {
            return 0.0;
        }
        GroupRatioStats gs = getGroupStats(group);
        synchronized (gs) {
            return gs.getFailureRate(dictId);
        }
    }

    public boolean isDrifting(String group) {
        if (group == null || registry == null) {
            return false;
        }
        DictionaryVersion active = registry.getActive(group);
        if (active == null) {
            return false;
        }
        return isDrifting(group, active.getId(), active.getMetadata().getBaseline());
    }

    public boolean isDrifting(String group, String dictId, double baseline) {
        if (group == null || dictId == null || baseline <= 0.0) {
            return false;
        }
        GroupRatioStats gs = getGroupStats(group);
        synchronized (gs) {
            int count = gs.getResponseCount(dictId);
            int minResponses = props.getDrift().getMinResponses();
            if (count < minResponses) {
                return false;
            }
            double mean = gs.getRollingMean(dictId);
            double threshold = props.getDrift().getThreshold();
            return mean < baseline * (1.0 - threshold);
        }
    }

    public void clear(String group) {
        if (group == null) return;
        String slug = ZstdDictFilter.toGroupSlug(group);
        GroupRatioStats gs = groupStats.get(slug);
        if (gs != null) {
            synchronized (gs) {
                gs.clear();
            }
        }
    }

    public long getTotalBytesOriginal(String group) {
        if (group == null) return 0;
        GroupRatioStats gs = getGroupStats(group);
        synchronized (gs) {
            return gs.getTotalBytesOriginal();
        }
    }

    public long getTotalBytesCompressed(String group) {
        if (group == null) return 0;
        GroupRatioStats gs = getGroupStats(group);
        synchronized (gs) {
            return gs.getTotalBytesCompressed();
        }
    }

    public long getTotalBytesSaved(String group) {
        if (group == null) return 0;
        GroupRatioStats gs = getGroupStats(group);
        synchronized (gs) {
            return gs.getTotalBytesSaved();
        }
    }

    public long getCompressedResponses(String group) {
        if (group == null) return 0;
        GroupRatioStats gs = getGroupStats(group);
        synchronized (gs) {
            return gs.getCompressedResponses();
        }
    }

    public Clock getClock() {
        return clock;
    }

    static class GroupRatioStats {
        private final int windowCapacity;
        private final Map<String, DictWindow> windows = new HashMap<>();
        private long totalBytesOriginal = 0;
        private long totalBytesCompressed = 0;
        private long compressedResponses = 0;

        GroupRatioStats(int windowCapacity) {
            this.windowCapacity = windowCapacity > 0 ? windowCapacity : 500;
        }

        private DictWindow getOrCreate(String dictId) {
            return windows.computeIfAbsent(dictId, k -> new DictWindow(windowCapacity));
        }

        void recordSuccess(String dictId, double ratio, long origBytes, long compBytes) {
            getOrCreate(dictId).recordSuccess(ratio);
            if (origBytes > 0 && compBytes > 0) {
                totalBytesOriginal += origBytes;
                totalBytesCompressed += compBytes;
                compressedResponses++;
            }
        }

        void recordSuccess(String dictId, double ratio) {
            recordSuccess(dictId, ratio, 0, 0);
        }

        long getTotalBytesOriginal() {
            return totalBytesOriginal;
        }

        long getTotalBytesCompressed() {
            return totalBytesCompressed;
        }

        long getTotalBytesSaved() {
            return Math.max(0, totalBytesOriginal - totalBytesCompressed);
        }

        long getCompressedResponses() {
            return compressedResponses;
        }

        void recordFailure(String dictId) {
            getOrCreate(dictId).recordFailure();
        }

        double getRollingMean(String dictId) {
            DictWindow w = windows.get(dictId);
            return w != null ? w.getRollingMean() : 0.0;
        }

        int getResponseCount(String dictId) {
            DictWindow w = windows.get(dictId);
            return w != null ? w.getCount() : 0;
        }

        long getFailureCount(String dictId) {
            DictWindow w = windows.get(dictId);
            return w != null ? w.getFailures() : 0;
        }

        double getFailureRate(String dictId) {
            DictWindow w = windows.get(dictId);
            return w != null ? w.getFailureRate() : 0.0;
        }

        void clear() {
            windows.clear();
        }
    }

    static class DictWindow {
        private final double[] values;
        private int head = 0;
        private int count = 0;
        private double sum = 0.0;
        private long failures = 0;
        private long totalResponses = 0;

        DictWindow(int capacity) {
            this.values = new double[capacity];
        }

        void recordSuccess(double ratio) {
            if (count == values.length) {
                sum -= values[head];
            } else {
                count++;
            }
            values[head] = ratio;
            sum += ratio;
            head = (head + 1) % values.length;
            if (head == 0) {
                double recomputed = 0.0;
                for (int i = 0; i < count; i++) {
                    recomputed += values[i];
                }
                sum = recomputed;
            }
            totalResponses++;
        }

        void recordFailure() {
            failures++;
        }

        double getRollingMean() {
            if (count == 0) return 0.0;
            return sum / count;
        }

        int getCount() {
            return count;
        }

        long getFailures() {
            return failures;
        }

        long getTotalResponses() {
            return totalResponses;
        }

        double getFailureRate() {
            long total = totalResponses + failures;
            if (total == 0) return 0.0;
            return (double) failures / total;
        }
    }
}
