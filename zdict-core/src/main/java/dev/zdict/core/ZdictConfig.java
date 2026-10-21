package dev.zdict.core;

import java.time.Duration;

/**
 * Core configuration holder for zdict training, drift, canary, and sampling policies.
 */
public class ZdictConfig {

    private String dataDir = "zdict-data";
    private TrainConfig train = new TrainConfig();
    private DriftConfig drift = new DriftConfig();
    private CanaryConfig canary = new CanaryConfig();
    private AutoLearningConfig autoLearning = new AutoLearningConfig();
    private SampleConfig sample = new SampleConfig();

    public static class TrainConfig {
        private int minSamples = 200;
        private int dictSize = 16 * 1024; // 16 KB
        private Duration cooldown = Duration.ofMinutes(15);
        private double minGain = 0.10; // 10% min-gain over gzip and plain zstd

        public int getMinSamples() { return minSamples; }
        public void setMinSamples(int minSamples) { this.minSamples = minSamples; }
        public int getDictSize() { return dictSize; }
        public void setDictSize(int dictSize) { this.dictSize = dictSize; }
        public Duration getCooldown() { return cooldown; }
        public void setCooldown(Duration cooldown) { this.cooldown = cooldown; }
        public double getMinGain() { return minGain; }
        public void setMinGain(double minGain) { this.minGain = minGain; }
    }

    public static class DriftConfig {
        private double threshold = 0.15;
        private int minResponses = 200;
        private int windowSize = 500;

        public double getThreshold() { return threshold; }
        public void setThreshold(double threshold) { this.threshold = threshold; }
        public int getMinResponses() { return minResponses; }
        public void setMinResponses(int minResponses) { this.minResponses = minResponses; }
        public int getWindowSize() { return windowSize; }
        public void setWindowSize(int windowSize) { this.windowSize = windowSize; }
    }

    public static class CanaryConfig {
        private int percent = 5;
        private int minResponses = 300;
        private Duration maxDuration = Duration.ofMinutes(30);

        public int getPercent() { return percent; }
        public void setPercent(int percent) { this.percent = percent; }
        public int getMinResponses() { return minResponses; }
        public void setMinResponses(int minResponses) { this.minResponses = minResponses; }
        public Duration getMaxDuration() { return maxDuration; }
        public void setMaxDuration(Duration maxDuration) { this.maxDuration = maxDuration; }
    }

    public static class AutoLearningConfig {
        private boolean enabled = false;
        private Duration interval = Duration.ofMinutes(1);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getInterval() { return interval; }
        public void setInterval(Duration interval) { this.interval = interval; }
    }

    public static class SampleConfig {
        private int perGroupMax = 2000;
        private double rate = 0.05;

        public int getPerGroupMax() { return perGroupMax; }
        public void setPerGroupMax(int perGroupMax) { this.perGroupMax = perGroupMax; }
        public double getRate() { return rate; }
        public void setRate(double rate) { this.rate = rate; }
    }

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public TrainConfig getTrain() { return train; }
    public void setTrain(TrainConfig train) { this.train = train; }
    public DriftConfig getDrift() { return drift; }
    public void setDrift(DriftConfig drift) { this.drift = drift; }
    public CanaryConfig getCanary() { return canary; }
    public void setCanary(CanaryConfig canary) { this.canary = canary; }
    public AutoLearningConfig getAutoLearning() { return autoLearning; }
    public void setAutoLearning(AutoLearningConfig autoLearning) { this.autoLearning = autoLearning; }
    public SampleConfig getSample() { return sample; }
    public void setSample(SampleConfig sample) { this.sample = sample; }
}
