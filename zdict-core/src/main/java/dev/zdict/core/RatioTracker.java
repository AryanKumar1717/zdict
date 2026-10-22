package dev.zdict.core;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks compression ratios (original / compressed) and failures per (group, dictId)
 * in a rolling window of size 500, and evaluates whether endpoint groups are drifting.
 * State changes per group are synchronized.
 */
public class RatioTracker {

    private final DictionaryRegistry registry;
    private final ZdictConfig config;
    private final Clock clock;
    private final ZdictMetricsListener metrics;

    // group slug -> GroupRatioStats
    private final ConcurrentHashMap<String, GroupRatioStats> groupStats = new ConcurrentHashMap<>();

    public RatioTracker(DictionaryRegistry registry,
                        ZdictConfig config,
                        Clock clock,
                        ZdictMetricsListener metrics) {
        this.registry = registry;
        this.config = config != null ? config : new ZdictConfig();
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.metrics = metrics;
    }

    public RatioTracker(DictionaryRegistry registry, ZdictConfig config, Clock clock) {
        this(registry, config, clock, null);
    }

    public RatioTracker(DictionaryRegistry registry, ZdictConfig config) {
        this(registry, config, Clock.systemUTC(), null);
    }

    public RatioTracker(ZdictConfig config) {
        this(null, config, Clock.systemUTC(), null);
    }

    private GroupRatioStats getGroupStats(String group) {
        String slug = ZdictConstants.toGroupSlug(group);
        int windowSize = config.getDrift() != null ? config.getDrift().getWindowSize() : 500;
        return groupStats.computeIfAbsent(slug, k -> new GroupRatioStats(windowSize));
    }

    public void recordSuccess(String group, String dictId, long origBytes, long compBytes) {
        if (group == null || dictId == null || compBytes <= 0) {
            return;
        }
        GroupRatioStats gs = getGroupStats(group);
        double ratio;
        synchronized (gs) {
            gs.recordSuccess(dictId, origBytes, compBytes);
            ratio = gs.getRollingRatio(dictId);
        }
        if (metrics != null) {
            metrics.updateRollingRatio(group, ratio);
        }
    }

    public void recordSuccess(String group, String dictId, double ratio) {
        if (group == null || dictId == null || ratio <= 0.0) {
            return;
        }
        long comp = 1000L;
        long orig = Math.round(ratio * 1000.0);
        recordSuccess(group, dictId, orig, comp);
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
        return getRollingRatio(group, dictId);
    }

    public double getRollingRatio(String group, String dictId) {
        if (group == null || dictId == null) {
            return 0.0;
        }
        GroupRatioStats gs = getGroupStats(group);
        synchronized (gs) {
            return gs.getRollingRatio(dictId);
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
            int minResponses = config.getDrift() != null ? config.getDrift().getMinResponses() : 200;
            if (count < minResponses) {
                return false;
            }
            double mean = gs.getRollingMean(dictId);
            double threshold = config.getDrift() != null ? config.getDrift().getThreshold() : 0.15;
            return mean < baseline * (1.0 - threshold);
        }
    }

    public void clear(String group) {
        if (group == null) return;
        String slug = ZdictConstants.toGroupSlug(group);
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

        void recordSuccess(String dictId, long origBytes, long compBytes) {
            getOrCreate(dictId).recordSuccess(origBytes, compBytes);
            if (origBytes > 0 && compBytes > 0) {
                totalBytesOriginal += origBytes;
                totalBytesCompressed += compBytes;
                compressedResponses++;
            }
        }

        void recordSuccess(String dictId, double ratio) {
            long comp = 1000L;
            long orig = Math.round(ratio * 1000.0);
            recordSuccess(dictId, orig, comp);
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
            return getRollingRatio(dictId);
        }

        double getRollingRatio(String dictId) {
            DictWindow w = windows.get(dictId);
            return w != null ? w.getRollingRatio() : 0.0;
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
        private final long[] origWindow;
        private final long[] compWindow;
        private int head = 0;
        private int count = 0;
        private long sumOrig = 0;
        private long sumComp = 0;
        private long failures = 0;
        private long totalResponses = 0;

        DictWindow(int capacity) {
            this.origWindow = new long[capacity];
            this.compWindow = new long[capacity];
        }

        void recordSuccess(long origBytes, long compBytes) {
            if (count == origWindow.length) {
                sumOrig -= origWindow[head];
                sumComp -= compWindow[head];
            } else {
                count++;
            }
            origWindow[head] = origBytes;
            compWindow[head] = compBytes;
            sumOrig += origBytes;
            sumComp += compBytes;
            head = (head + 1) % origWindow.length;
            if (head == 0) {
                long recomputedOrig = 0;
                long recomputedComp = 0;
                for (int i = 0; i < count; i++) {
                    recomputedOrig += origWindow[i];
                    recomputedComp += compWindow[i];
                }
                sumOrig = recomputedOrig;
                sumComp = recomputedComp;
            }
            totalResponses++;
        }

        void recordFailure() {
            failures++;
        }

        double getRollingRatio() {
            if (count == 0 || sumComp <= 0) return 0.0;
            return (double) sumOrig / sumComp;
        }

        double getRollingMean() {
            return getRollingRatio();
        }

        long getWindowOriginalBytes() {
            return sumOrig;
        }

        long getWindowCompressedBytes() {
            return sumComp;
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
