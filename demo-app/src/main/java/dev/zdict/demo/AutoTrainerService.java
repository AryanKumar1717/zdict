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
    private final EventLog eventLog;

    private final ConcurrentHashMap<String, Object> groupLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastTrainTimes = new ConcurrentHashMap<>();
    private final Set<String> driftClearedGroups = ConcurrentHashMap.newKeySet();

    public AutoTrainerService(ZdictProperties props,
                              SampleStore sampleStore,
                              DictionaryRegistry registry,
                              DictionaryTrainerService trainerService,
                              RatioTracker ratioTracker,
                              Clock clock) {
        this(props, sampleStore, registry, trainerService, ratioTracker, clock, null);
    }

    @Autowired
    public AutoTrainerService(ZdictProperties props,
                              SampleStore sampleStore,
                              DictionaryRegistry registry,
                              DictionaryTrainerService trainerService,
                              RatioTracker ratioTracker,
                              @Autowired(required = false) Clock clock,
                              @Autowired(required = false) EventLog eventLog) {
        this.props = props;
        this.sampleStore = sampleStore;
        this.registry = registry;
        this.trainerService = trainerService;
        this.ratioTracker = ratioTracker;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.eventLog = eventLog;
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
            DictionaryVersion canary = registry != null ? registry.getCanary(group) : null;
            DictionaryVersion active = registry != null ? registry.getActive(group) : null;

            if (canary != null) {
                evaluateCanary(group, slug, canary, active, now);
                canary = registry != null ? registry.getCanary(group) : null;
                active = registry != null ? registry.getActive(group) : null;
            }

            if (canary != null) {
                // Ongoing canary evaluation in progress; at most one CANARY per group
                return;
            }

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
                    if (eventLog != null) {
                        eventLog.record("DRIFT_DETECTED", group, active.getId(), "Rolling ratio dropped below drift threshold");
                    }
                    log.info("Drift detected for group '{}'; cleared sample reservoir once to collect fresh samples", group);
                }

                int sampleCount = sampleStore != null ? sampleStore.getSampleCount(group) : 0;
                int minSamples = props.getTrain().getMinSamples();
                if (sampleCount >= minSamples) {
                    if (!isInCooldown(slug, now)) {
                        TrainResult res = trainGroup(group, slug, now);
                        if (res != null && (res.isPromoted() || "CANARY_STARTED".equals(res.getDecision()))) {
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

    public void evaluateCanary(String group) {
        if (group == null || registry == null) return;
        String slug = ZstdDictFilter.toGroupSlug(group);
        Object lock = groupLocks.computeIfAbsent(slug, k -> new Object());
        synchronized (lock) {
            Instant now = Instant.now(clock);
            DictionaryVersion canary = registry.getCanary(group);
            DictionaryVersion active = registry.getActive(group);
            if (canary != null) {
                evaluateCanary(group, slug, canary, active, now);
            }
        }
    }

    private void evaluateCanary(String group, String slug, DictionaryVersion canary, DictionaryVersion active, Instant now) {
        int canaryResponses = ratioTracker != null ? ratioTracker.getResponseCount(group, canary.getId()) : 0;
        int minResponses = props.getCanary().getMinResponses();

        Instant createdAt;
        try {
            createdAt = Instant.parse(canary.getMetadata().getCreatedAt());
        } catch (Exception e) {
            createdAt = now;
        }

        Duration elapsed = Duration.between(createdAt, now);
        Duration maxDuration = props.getCanary().getMaxDuration();

        if (canaryResponses >= minResponses) {
            double canaryMean = ratioTracker != null ? ratioTracker.getRollingMean(group, canary.getId()) : 0.0;
            double activeMean = (active != null && ratioTracker != null) ? ratioTracker.getRollingMean(group, active.getId()) : 0.0;
            double canaryFailRate = ratioTracker != null ? ratioTracker.getFailureRate(group, canary.getId()) : 0.0;
            double activeFailRate = (active != null && ratioTracker != null) ? ratioTracker.getFailureRate(group, active.getId()) : 0.0;

            if (canaryMean >= activeMean * 1.03 && canaryFailRate <= activeFailRate) {
                registry.promoteCanary(group);
                String reason = String.format("Canary mean (%.4f) >= active (%.4f) * 1.03 and failure rate (%.4f) <= active (%.4f)",
                        canaryMean, activeMean, canaryFailRate, activeFailRate);
                if (eventLog != null) {
                    eventLog.record("PROMOTED", group, canary.getId(), reason);
                }
                log.info("Canary {} PROMOTED to ACTIVE for group '{}': canaryMean={:.4f}, activeMean={:.4f}, canaryFailRate={:.4f}, activeFailRate={:.4f}",
                        canary.getId(), group, canaryMean, activeMean, canaryFailRate, activeFailRate);
            } else {
                String reason;
                if (canaryMean < activeMean * 1.03) {
                    reason = String.format("canary ratio (%.4f) did not beat active (%.4f) by 3%%", canaryMean, activeMean);
                } else {
                    reason = String.format("canary failure rate (%.4f) exceeded active (%.4f)", canaryFailRate, activeFailRate);
                }
                registry.rollbackCanary(group, reason);
                if (eventLog != null) {
                    eventLog.record("ROLLED_BACK", group, canary.getId(), reason);
                }
                log.info("Canary {} ROLLED_BACK for group '{}': {}", canary.getId(), group, reason);
            }
        } else if (elapsed.compareTo(maxDuration) >= 0) {
            String reason = "insufficient traffic";
            registry.rollbackCanary(group, reason);
            if (eventLog != null) {
                eventLog.record("ROLLED_BACK", group, canary.getId(), reason);
            }
            log.info("Canary {} ROLLED_BACK for group '{}' due to timeout: insufficient traffic ({}/{} responses after {})",
                    canary.getId(), group, canaryResponses, minResponses, elapsed);
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
