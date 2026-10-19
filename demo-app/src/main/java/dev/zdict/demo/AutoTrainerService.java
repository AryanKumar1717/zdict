package dev.zdict.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scheduled job and orchestration for automated dictionary retraining upon drift
 * or for un-dictionaried endpoint groups. Synchronizes state mutations per group.
 */
@Service
public class AutoTrainerService {

    private static final Logger log = LoggerFactory.getLogger(AutoTrainerService.class);

    private final ZdictProperties props;
    private final SampleStore sampleStore;
    private final DictionaryRegistry registry;
    private final DictionaryTrainerService trainerService;
    private final RatioTracker ratioTracker;
    private final Clock clock;

    private final ConcurrentHashMap<String, Object> groupLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastTrainTimes = new ConcurrentHashMap<>();
    private final Set<String> driftClearedGroups = ConcurrentHashMap.newKeySet();

    @Autowired
    public AutoTrainerService(ZdictProperties props,
                              SampleStore sampleStore,
                              DictionaryRegistry registry,
                              DictionaryTrainerService trainerService,
                              RatioTracker ratioTracker,
                              @Autowired(required = false) Clock clock) {
        this.props = props;
        this.sampleStore = sampleStore;
        this.registry = registry;
        this.trainerService = trainerService;
        this.ratioTracker = ratioTracker;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Scheduled(fixedDelayString = "${zdict.auto-learning.interval:60000}")
    public void runScheduled() {
        if (!props.getAutoLearning().isEnabled()) {
            return;
        }
        checkAndTrain();
    }

    /**
     * Checks all known endpoint groups and trains or retrains dictionaries where appropriate.
     */
    public void checkAndTrain() {
        if (!props.getAutoLearning().isEnabled()) {
            return;
        }

        Set<String> groups = new HashSet<>();
        if (sampleStore != null) {
            groups.addAll(sampleStore.getGroups());
        }
        if (registry != null) {
            groups.addAll(registry.getGroups());
        }
        if (props.getPaths() != null) {
            groups.addAll(props.getPaths());
        }

        for (String group : groups) {
            processGroup(group);
        }
    }

    public void processGroup(String group) {
        if (group == null || group.isBlank()) {
            return;
        }
        String slug = ZstdDictFilter.toGroupSlug(group);
        Object lock = groupLocks.computeIfAbsent(slug, k -> new Object());

        synchronized (lock) {
            Instant now = Instant.now(clock);
            DictionaryVersion active = registry != null ? registry.getActive(group) : null;
            boolean drifting = (active != null) && (ratioTracker != null) && ratioTracker.isDrifting(group);

            if (active == null) {
                // No ACTIVE dictionary and enough samples -> train, promote directly to ACTIVE
                int sampleCount = sampleStore != null ? sampleStore.getSampleCount(group) : 0;
                int minSamples = props.getTrain().getMinSamples();
                if (sampleCount >= minSamples) {
                    if (!isInCooldown(slug, now)) {
                        trainGroup(group, slug, now);
                    } else {
                        log.debug("Group '{}' has enough samples but is in cooldown", group);
                    }
                }
            } else if (drifting) {
                // DRIFTING -> clear reservoir once, keep serving old dict, retrain once min-samples exist
                if (!driftClearedGroups.contains(slug)) {
                    if (sampleStore != null) {
                        sampleStore.clear(group);
                    }
                    driftClearedGroups.add(slug);
                    log.info("Drift detected for group '{}'; cleared sample reservoir once to collect fresh samples", group);
                }

                int sampleCount = sampleStore != null ? sampleStore.getSampleCount(group) : 0;
                int minSamples = props.getTrain().getMinSamples();
                if (sampleCount >= minSamples) {
                    if (!isInCooldown(slug, now)) {
                        TrainResult res = trainGroup(group, slug, now);
                        if (res != null && res.isPromoted()) {
                            driftClearedGroups.remove(slug);
                        }
                    } else {
                        log.debug("Group '{}' is drifting with enough samples but is in cooldown", group);
                    }
                }
            } else {
                // Not drifting and has active dict
                driftClearedGroups.remove(slug);
            }
        }
    }

    private TrainResult trainGroup(String group, String slug, Instant now) {
        lastTrainTimes.put(slug, now);
        try {
            log.info("Triggering auto-training for group '{}' at {}", group, now);
            TrainResult result = trainerService.train(group);
            log.info("Auto-training result for group '{}': promoted={}, decision={}, dictId={}",
                    group, result.isPromoted(), result.getDecision(), result.getDictionaryId());
            return result;
        } catch (IOException e) {
            log.error("Failed to train dictionary for group '{}': {}", group, e.getMessage(), e);
            return null;
        }
    }

    private boolean isInCooldown(String slug, Instant now) {
        Instant last = lastTrainTimes.get(slug);
        if (last == null) {
            return false;
        }
        Duration cd = props.getTrain().getCooldown();
        return Duration.between(last, now).compareTo(cd) < 0;
    }

    public Instant getLastTrainTime(String group) {
        String slug = ZstdDictFilter.toGroupSlug(group);
        return lastTrainTimes.get(slug);
    }

    public void setLastTrainTime(String group, Instant time) {
        String slug = ZstdDictFilter.toGroupSlug(group);
        if (time != null) {
            lastTrainTimes.put(slug, time);
        } else {
            lastTrainTimes.remove(slug);
        }
    }

    public boolean isDriftCleared(String group) {
        String slug = ZstdDictFilter.toGroupSlug(group);
        return driftClearedGroups.contains(slug);
    }

    public Clock getClock() {
        return clock;
    }
}
