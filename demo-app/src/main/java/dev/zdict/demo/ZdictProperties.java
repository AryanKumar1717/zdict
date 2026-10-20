package dev.zdict.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Configuration properties for the zstd dictionary compression system.
 */
@ConfigurationProperties(prefix = "zdict")
public class ZdictProperties {

    private boolean enabled = false;
    private String dictionaryPath = "";
    private String seedDictionaryPath = "";
    private List<String> paths = List.of();
    private boolean allowAuthenticated = false;
    private boolean skipReflectedInput = true;

    private String dataDir = "zdict-data";

    private Set<String> denylistedFields = new HashSet<>(Set.of(
            "password", "token", "accesstoken", "refreshtoken", "secret",
            "email", "phone", "mobile", "address", "ssn", "aadhaar",
            "pan", "card", "cvv", "authorization"
    ));

    private Sample sample = new Sample();
    private Train train = new Train();
    private Drift drift = new Drift();
    private AutoLearning autoLearning = new AutoLearning();
    private Canary canary = new Canary();
    private Dashboard dashboard = new Dashboard();

    public static class Dashboard {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Sample {
        private int perGroupMax = 2000;
        private double rate = 0.05;

        public int getPerGroupMax() {
            return perGroupMax;
        }

        public void setPerGroupMax(int perGroupMax) {
            this.perGroupMax = perGroupMax;
        }

        public double getRate() {
            return rate;
        }

        public void setRate(double rate) {
            this.rate = rate;
        }
    }

    public static class Train {
        private int minSamples = 200;
        private int dictSize = 16 * 1024; // 16 KB
        private java.time.Duration cooldown = java.time.Duration.ofMinutes(15);

        public int getMinSamples() {
            return minSamples;
        }

        public void setMinSamples(int minSamples) {
            this.minSamples = minSamples;
        }

        public int getDictSize() {
            return dictSize;
        }

        public void setDictSize(int dictSize) {
            this.dictSize = dictSize;
        }

        public java.time.Duration getCooldown() {
            return cooldown;
        }

        public void setCooldown(java.time.Duration cooldown) {
            this.cooldown = cooldown;
        }
    }

    public static class Drift {
        private double threshold = 0.15;
        private int minResponses = 200;
        private int windowSize = 500;

        public double getThreshold() {
            return threshold;
        }

        public void setThreshold(double threshold) {
            this.threshold = threshold;
        }

        public int getMinResponses() {
            return minResponses;
        }

        public void setMinResponses(int minResponses) {
            this.minResponses = minResponses;
        }

        public int getWindowSize() {
            return windowSize;
        }

        public void setWindowSize(int windowSize) {
            this.windowSize = windowSize;
        }
    }

    public static class AutoLearning {
        private boolean enabled = false;
        private java.time.Duration interval = java.time.Duration.ofMinutes(1);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public java.time.Duration getInterval() {
            return interval;
        }

        public void setInterval(java.time.Duration interval) {
            this.interval = interval;
        }
    }

    public static class Canary {
        private int percent = 5;
        private int minResponses = 300;
        private java.time.Duration maxDuration = java.time.Duration.ofMinutes(30);

        public int getPercent() {
            return percent;
        }

        public void setPercent(int percent) {
            this.percent = percent;
        }

        public int getMinResponses() {
            return minResponses;
        }

        public void setMinResponses(int minResponses) {
            this.minResponses = minResponses;
        }

        public java.time.Duration getMaxDuration() {
            return maxDuration;
        }

        public void setMaxDuration(java.time.Duration maxDuration) {
            this.maxDuration = maxDuration;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSeedDictionaryPath() {
        if (seedDictionaryPath != null && !seedDictionaryPath.isBlank()) {
            return seedDictionaryPath;
        }
        return dictionaryPath;
    }

    public void setSeedDictionaryPath(String seedDictionaryPath) {
        this.seedDictionaryPath = seedDictionaryPath;
    }

    public String getDictionaryPath() {
        return getSeedDictionaryPath();
    }

    public void setDictionaryPath(String dictionaryPath) {
        this.dictionaryPath = dictionaryPath;
    }

    public List<String> getPaths() {
        return paths;
    }

    public void setPaths(List<String> paths) {
        this.paths = paths;
    }

    public boolean isAllowAuthenticated() {
        return allowAuthenticated;
    }

    public void setAllowAuthenticated(boolean allowAuthenticated) {
        this.allowAuthenticated = allowAuthenticated;
    }

    public boolean isSkipReflectedInput() {
        return skipReflectedInput;
    }

    public void setSkipReflectedInput(boolean skipReflectedInput) {
        this.skipReflectedInput = skipReflectedInput;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public Set<String> getDenylistedFields() {
        return denylistedFields;
    }

    public void setDenylistedFields(Set<String> denylistedFields) {
        this.denylistedFields = denylistedFields;
    }

    public Sample getSample() {
        return sample;
    }

    public void setSample(Sample sample) {
        this.sample = sample;
    }

    public Train getTrain() {
        return train;
    }

    public void setTrain(Train train) {
        this.train = train;
    }

    public Drift getDrift() {
        return drift;
    }

    public void setDrift(Drift drift) {
        this.drift = drift;
    }

    public AutoLearning getAutoLearning() {
        return autoLearning;
    }

    public void setAutoLearning(AutoLearning autoLearning) {
        this.autoLearning = autoLearning;
    }

    public Canary getCanary() {
        return canary;
    }

    public void setCanary(Canary canary) {
        this.canary = canary;
    }

    public Dashboard getDashboard() {
        return dashboard;
    }

    public void setDashboard(Dashboard dashboard) {
        this.dashboard = dashboard;
    }

    public boolean isDashboardEnabled() {
        return dashboard != null && dashboard.isEnabled();
    }
}
