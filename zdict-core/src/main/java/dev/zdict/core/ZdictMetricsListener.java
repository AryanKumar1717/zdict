package dev.zdict.core;

/**
 * Listener interface implemented by metric adapters (e.g. Micrometer in starter)
 * to receive telemetry from zdict-core components.
 */
public interface ZdictMetricsListener {

    default void updateRollingRatio(String group, double ratio) {}

    default void recordEvent(String type, String group) {}

    default void recordResponse(String result, String reason) {}

    default void recordBytesOriginal(String group, long bytes) {}

    default void recordBytesCompressed(String group, long bytes) {}

    default void recordCompressTime(long durationNanos) {}
}
