package dev.zdict.core;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictTrainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

/**
 * Trains endpoint-group dictionaries from accumulated samples, measures compression
 * against plain zstd and the active dictionary on holdout, and promotes or discards.
 */
public class DictionaryTrainerService {

    private static final Logger log = LoggerFactory.getLogger(DictionaryTrainerService.class);
    private static final int COMPRESSION_LEVEL = 3;

    public static class NotWorthItInfo {
        private final String group;
        private final String candidateId;
        private final double gainVsZstd;
        private final double gainVsGzip;
        private final double minGain;

        public NotWorthItInfo(String group, String candidateId, double gainVsZstd, double gainVsGzip, double minGain) {
            this.group = group;
            this.candidateId = candidateId;
            this.gainVsZstd = gainVsZstd;
            this.gainVsGzip = gainVsGzip;
            this.minGain = minGain;
        }

        public String getGroup() {
            return group;
        }

        public String getCandidateId() {
            return candidateId;
        }

        public double getGainVsZstd() {
            return gainVsZstd;
        }

        public double getGainVsGzip() {
            return gainVsGzip;
        }

        public double getMinGain() {
            return minGain;
        }
    }

    private final SampleStore sampleStore;
    private final DictionaryRegistry registry;
    private final ZdictConfig config;
    private final EventLog eventLog;
    private final ConcurrentHashMap<String, NotWorthItInfo> notWorthItGroups = new ConcurrentHashMap<>();

    public DictionaryTrainerService(SampleStore sampleStore, DictionaryRegistry registry, ZdictConfig config) {
        this(sampleStore, registry, config, null);
    }

    public DictionaryTrainerService(SampleStore sampleStore, DictionaryRegistry registry, ZdictConfig config, EventLog eventLog) {
        this.sampleStore = sampleStore;
        this.registry = registry;
        this.config = config != null ? config : new ZdictConfig();
        this.eventLog = eventLog;
    }

    public NotWorthItInfo getNotWorthIt(String group) {
        return notWorthItGroups.get(group);
    }

    public boolean isNotWorthIt(String group) {
        return notWorthItGroups.containsKey(group);
    }

    public Set<String> getNotWorthItGroups() {
        return notWorthItGroups.keySet();
    }

    public TrainResult train(String group) throws IOException {
        int minSamples = config.getTrain() != null ? config.getTrain().getMinSamples() : 200;
        int dictSize = config.getTrain() != null ? config.getTrain().getDictSize() : 16 * 1024;
        return train(group, minSamples, dictSize);
    }

    public TrainResult train(String group, int minSamples, int dictSize) throws IOException {
        List<byte[]> samples = sampleStore.getSamples(group);
        int sampleCount = samples.size();

        if (sampleCount < minSamples) {
            log.warn("Refusing training for group '{}': sample count {} < required minimum {}",
                    group, sampleCount, minSamples);
            return TrainResult.refused(group, sampleCount, minSamples);
        }

        // 80/20 split
        int trainCount = (int) (sampleCount * 0.8);
        int holdoutCount = sampleCount - trainCount;
        List<byte[]> trainSamples = samples.subList(0, trainCount);
        List<byte[]> holdoutSamples = samples.subList(trainCount, sampleCount);

        // Train dictionary on 80%
        long totalTrainBytes = trainSamples.stream().mapToLong(s -> s.length).sum();
        ZstdDictTrainer trainer = new ZstdDictTrainer((int) (totalTrainBytes + 1024), dictSize);
        for (byte[] s : trainSamples) {
            trainer.addSample(s);
        }
        byte[] candidateBytes = trainer.trainSamples();
        if (candidateBytes == null || candidateBytes.length == 0) {
            throw new IllegalStateException("ZstdDictTrainer produced empty dictionary for group: " + group);
        }
        String candidateId = ZdictConstants.computeDictId(candidateBytes);

        // Evaluate on 20% holdout
        DictionaryVersion activeVersion = registry.getActive(group);
        ZstdDictCompress candidateCompress = new ZstdDictCompress(candidateBytes, COMPRESSION_LEVEL);
        ZstdDictCompress activeCompress = (activeVersion != null) ? activeVersion.getCdict() : null;

        long origHoldoutBytes = 0;
        long candidateCompBytes = 0;
        long plainCompBytes = 0;
        long gzipCompBytes = 0;
        long activeCompBytes = 0;

        try {
            for (byte[] sample : holdoutSamples) {
                origHoldoutBytes += sample.length;
                byte[] plainComp = Zstd.compress(sample, COMPRESSION_LEVEL);
                plainCompBytes += plainComp.length;

                byte[] gzComp = compressGzip(sample, 6);
                gzipCompBytes += gzComp.length;

                byte[] candidateComp = Zstd.compress(sample, candidateCompress);
                candidateCompBytes += candidateComp.length;

                if (activeCompress != null) {
                    byte[] activeComp = Zstd.compress(sample, activeCompress);
                    activeCompBytes += activeComp.length;
                }
            }
        } finally {
            candidateCompress.close();
        }

        double plainRatio = origHoldoutBytes > 0 ? (double) plainCompBytes / origHoldoutBytes : 1.0;
        double gzipRatio = origHoldoutBytes > 0 ? (double) gzipCompBytes / origHoldoutBytes : 1.0;
        double candidateRatio = origHoldoutBytes > 0 ? (double) candidateCompBytes / origHoldoutBytes : 1.0;
        Double activeRatio = (activeVersion != null && origHoldoutBytes > 0) ? (double) activeCompBytes / origHoldoutBytes : null;
        double baselineRatio = candidateCompBytes > 0 ? (double) origHoldoutBytes / candidateCompBytes : 1.0;

        double gainVsZstd = plainCompBytes > 0 ? (double) (plainCompBytes - candidateCompBytes) / plainCompBytes : 0.0;
        double gainVsGzip = gzipCompBytes > 0 ? (double) (gzipCompBytes - candidateCompBytes) / gzipCompBytes : 0.0;
        double minGain = (config.getTrain() != null) ? config.getTrain().getMinGain() : 0.10;

        TrainResult result = new TrainResult();
        result.setGroup(group);
        result.setDictionaryId(candidateId);
        result.setDictSize(candidateBytes.length);
        result.setSampleCount(sampleCount);
        result.setTrainCount(trainCount);
        result.setHoldoutCount(holdoutCount);
        result.setPlainHoldoutRatio(plainRatio);
        result.setGzipHoldoutRatio(gzipRatio);
        result.setActiveHoldoutRatio(activeRatio);
        result.setNewHoldoutRatio(candidateRatio);
        result.setGainVsZstd(gainVsZstd);
        result.setGainVsGzip(gainVsGzip);
        result.setMinGain(minGain);

        // Worth-It Gate: new dictionary must beat both gzip and plain zstd by at least zdict.train.min-gain
        if (gainVsZstd < minGain || gainVsGzip < minGain) {
            notWorthItGroups.put(group, new NotWorthItInfo(group, candidateId, gainVsZstd, gainVsGzip, minGain));
            result.setPromoted(false);
            result.setDecision("NOT_WORTH_IT");
            result.setReason(String.format("Candidate dictionary failed worth-it gate: gain vs plain zstd = %.2f%%, gain vs gzip = %.2f%% (>= %.2f%% required)",
                    gainVsZstd * 100, gainVsGzip * 100, minGain * 100));
            if (eventLog != null) {
                eventLog.record("NOT_WORTH_IT", group, candidateId, result.getReason());
            }
            log.info("Candidate dictionary {} for group '{}' failed worth-it gate: gain vs zstd = {:.2f}%, gain vs gzip = {:.2f}%, min-gain = {:.2f}%. Group status set to NOT_WORTH_IT.",
                    candidateId, group, gainVsZstd * 100, gainVsGzip * 100, minGain * 100);
            return result;
        }

        // Passed worth-it gate; clear any previous NOT_WORTH_IT status for this group
        notWorthItGroups.remove(group);

        if (activeVersion == null) {
            // First dictionary: automatically promote
            registry.register(group, candidateBytes, sampleCount, candidateRatio, baselineRatio);
            result.setPromoted(true);
            result.setDecision("PROMOTED_FIRST_ACTIVE");
            result.setReason("No active dictionary existed for group; promoted as initial ACTIVE version");
            if (eventLog != null) {
                eventLog.record("TRAINED", group, candidateId, result.getReason());
            }
            log.info("Promoted initial dictionary {} for group '{}' (holdout ratio: {:.4f}, plain: {:.4f})",
                    candidateId, group, candidateRatio, plainRatio);
        } else {
            // Promotion requires beating active dictionary by >= 5%
            double improvement = (activeRatio - candidateRatio) / activeRatio;
            if (improvement >= 0.05) {
                registry.registerCanary(group, candidateBytes, sampleCount, candidateRatio, baselineRatio);
                result.setPromoted(false);
                result.setDecision("CANARY_STARTED");
                result.setReason(String.format("Candidate beat active dictionary (%s) by %.2f%% (>= 5%% required); registered as CANARY",
                        activeVersion.getId(), improvement * 100));
                if (eventLog != null) {
                    eventLog.record("CANARY_STARTED", group, candidateId, result.getReason());
                }
                log.info("Registered dictionary {} as CANARY for group '{}' beating active {} by {:.2f}% (active: {:.4f}, new: {:.4f})",
                        candidateId, group, activeVersion.getId(), improvement * 100, activeRatio, candidateRatio);
            } else {
                result.setPromoted(false);
                result.setDecision("DISCARDED_INSUFFICIENT_IMPROVEMENT");
                result.setReason(String.format("Candidate improvement was %.2f%%, which did not meet the 5%% threshold against active %s",
                        improvement * 100, activeVersion.getId()));
                log.info("Discarded candidate dictionary {} for group '{}': {}", candidateId, group, result.getReason());
            }
        }

        return result;
    }

    private static byte[] compressGzip(byte[] input, int level) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos) {
            {
                def.setLevel(level);
            }
        }) {
            gzip.write(input);
        }
        return baos.toByteArray();
    }
}
