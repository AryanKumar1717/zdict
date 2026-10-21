package dev.zdict.spring;

import dev.zdict.core.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Endpoints for manual dictionary training and querying group statuses.
 * POST /actuator/zdict/train?group=... or /zdict/train?group=...
 * GET  /actuator/zdict/groups or /zdict/groups
 * GET  /actuator/zdict/events or /zdict/events
 */
@RestController
@RequestMapping({"/actuator/zdict", "/zdict"})
public class ZdictTrainController {

    private final DictionaryTrainerService trainerService;
    private final DictionaryRegistry registry;
    private final RatioTracker ratioTracker;
    private final SampleStore sampleStore;
    private final AutoTrainerService autoTrainer;
    private final EventLog eventLog;
    private final ZdictProperties props;

    public ZdictTrainController(DictionaryTrainerService trainerService,
                                DictionaryRegistry registry,
                                RatioTracker ratioTracker,
                                SampleStore sampleStore,
                                AutoTrainerService autoTrainer,
                                EventLog eventLog,
                                ZdictProperties props) {
        this.trainerService = trainerService;
        this.registry = registry;
        this.ratioTracker = ratioTracker;
        this.sampleStore = sampleStore;
        this.autoTrainer = autoTrainer;
        this.eventLog = eventLog;
        this.props = props;
    }

    @PostMapping("/train")
    public ResponseEntity<TrainResult> train(@RequestParam("group") String group) throws Exception {
        TrainResult result = trainerService.train(group);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/groups")
    public ResponseEntity<List<GroupStatus>> getGroups() {
        Set<String> groups = new TreeSet<>();
        if (sampleStore != null) {
            groups.addAll(sampleStore.getGroups());
        }
        if (registry != null) {
            groups.addAll(registry.getGroups());
        }
        if (props != null && props.getPaths() != null) {
            groups.addAll(props.getPaths());
        }
        if (trainerService != null) {
            groups.addAll(trainerService.getNotWorthItGroups());
        }

        List<GroupStatus> list = new ArrayList<>();
        for (String group : groups) {
            DictionaryVersion active = registry != null ? registry.getActive(group) : null;
            DictionaryVersion canary = registry != null ? registry.getCanary(group) : null;

            String activeId = active != null ? active.getId() : null;
            String canaryId = canary != null ? canary.getId() : null;

            Double baselineRatio = active != null ? active.getMetadata().getBaseline() : null;
            Double rollingRatio = (active != null && ratioTracker != null) ? ratioTracker.getRollingMean(group, activeId) : null;

            int sampleCount = sampleStore != null ? sampleStore.getSampleCount(group) : 0;
            Instant lastTrain = autoTrainer != null ? autoTrainer.getLastTrainTime(group) : null;
            String lastTrainedTime = lastTrain != null ? lastTrain.toString() : (active != null ? active.getMetadata().getCreatedAt() : null);

            ZdictEvent lastEvent = eventLog != null ? eventLog.getLastEvent(group) : null;

            String status;
            DictionaryTrainerService.NotWorthItInfo notWorthIt = trainerService != null ? trainerService.getNotWorthIt(group) : null;
            if (notWorthIt != null) {
                status = "NOT_WORTH_IT";
            } else if (active == null) {
                status = "NO_DICTIONARY";
            } else if (canary != null) {
                status = "CANARY";
            } else if (ratioTracker != null && ratioTracker.isDrifting(group)) {
                status = "DRIFTING";
            } else {
                status = "HEALTHY";
            }

            GroupStatus gs = new GroupStatus(group, activeId, canaryId, baselineRatio, rollingRatio, status, sampleCount, lastTrainedTime, lastEvent);
            if (notWorthIt != null) {
                gs.setGainVsZstd(notWorthIt.getGainVsZstd());
                gs.setGainVsGzip(notWorthIt.getGainVsGzip());
                gs.setMinGain(notWorthIt.getMinGain());
            }
            if (ratioTracker != null) {
                gs.setBytesOriginal(ratioTracker.getTotalBytesOriginal(group));
                gs.setBytesCompressed(ratioTracker.getTotalBytesCompressed(group));
                gs.setBytesSaved(ratioTracker.getTotalBytesSaved(group));
                gs.setCompressedResponses(ratioTracker.getCompressedResponses(group));
            }
            list.add(gs);
        }

        return ResponseEntity.ok(list);
    }

    @GetMapping("/events")
    public ResponseEntity<List<ZdictEvent>> getEvents() {
        if (eventLog == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        return ResponseEntity.ok(eventLog.getRecentEvents());
    }
}
