package dev.zdict.demo;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictTrainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * Trains endpoint-group dictionaries from accumulated samples, measures compression
 * against plain zstd and the active dictionary on holdout, and promotes or discards.
 */
@Service
public class DictionaryTrainerService {

    private static final Logger log = LoggerFactory.getLogger(DictionaryTrainerService.class);
    private static final int COMPRESSION_LEVEL = 3;

    private final SampleStore sampleStore;
    private final DictionaryRegistry registry;
    private final ZdictProperties props;
    private final EventLog eventLog;

    public DictionaryTrainerService(SampleStore sampleStore, DictionaryRegistry registry, ZdictProperties props) {
        this(sampleStore, registry, props, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DictionaryTrainerService(SampleStore sampleStore, DictionaryRegistry registry, ZdictProperties props,
                                  @org.springframework.beans.factory.annotation.Autowired(required = false) EventLog eventLog) {
        this.sampleStore = sampleStore;
        this.registry = registry;
        this.props = props;
        this.eventLog = eventLog;
    }

    public TrainResult train(String group) throws IOException {
        int minSamples = props.getTrain().getMinSamples();
        int dictSize = props.getTrain().getDictSize();
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
        String candidateId = ZstdDictFilter.computeDictId(candidateBytes);

        // Evaluate on 20% holdout
        DictionaryVersion activeVersion = registry.getActive(group);
        ZstdDictCompress candidateCompress = new ZstdDictCompress(candidateBytes, COMPRESSION_LEVEL);
        ZstdDictCompress activeCompress = (activeVersion != null) ? activeVersion.getCdict() : null;

        long origHoldoutBytes = 0;
        long candidateCompBytes = 0;
        long plainCompBytes = 0;
        long activeCompBytes = 0;

        try {
            for (byte[] sample : holdoutSamples) {
                origHoldoutBytes += sample.length;
                byte[] plainComp = Zstd.compress(sample, COMPRESSION_LEVEL);
                plainCompBytes += plainComp.length;

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

        double plainRatio = (double) plainCompBytes / origHoldoutBytes;
        double candidateRatio = (double) candidateCompBytes / origHoldoutBytes;
        Double activeRatio = (activeVersion != null) ? (double) activeCompBytes / origHoldoutBytes : null;
        double baselineRatio = candidateCompBytes > 0 ? (double) origHoldoutBytes / candidateCompBytes : 1.0;

        TrainResult result = new TrainResult();
        result.setGroup(group);
        result.setDictionaryId(candidateId);
        result.setSampleCount(sampleCount);
        result.setTrainCount(trainCount);
        result.setHoldoutCount(holdoutCount);
        result.setPlainHoldoutRatio(plainRatio);
        result.setActiveHoldoutRatio(activeRatio);
        result.setNewHoldoutRatio(candidateRatio);

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
}
