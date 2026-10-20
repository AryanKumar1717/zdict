package dev.zdict.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.*;

/**
 * Actuator endpoints for manual dictionary training and querying group statuses.
 * POST /actuator/zdict/train?group=...
 * GET  /actuator/zdict/groups
 */
@RestController
@RequestMapping("/actuator/zdict")
public class ZdictTrainController {

    private final DictionaryTrainerService trainerService;
    private final DictionaryRegistry registry;
    private final RatioTracker ratioTracker;
    private final SampleStore sampleStore;
    private final AutoTrainerService autoTrainer;
    private final EventLog eventLog;
    private final ZdictProperties props;

    @Autowired
    public ZdictTrainController(DictionaryTrainerService trainerService,
                                @Autowired(required = false) DictionaryRegistry registry,
                                @Autowired(required = false) RatioTracker ratioTracker,
                                @Autowired(required = false) SampleStore sampleStore,
                                @Autowired(required = false) AutoTrainerService autoTrainer,
                                @Autowired(required = false) EventLog eventLog,
                                @Autowired(required = false) ZdictProperties props) {
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
            if (active == null) {
                status = "NO_DICTIONARY";
            } else if (canary != null) {
                status = "CANARY";
            } else if (ratioTracker != null && ratioTracker.isDrifting(group)) {
                status = "DRIFTING";
            } else {
                status = "HEALTHY";
            }

            GroupStatus gs = new GroupStatus(group, activeId, canaryId, baselineRatio, rollingRatio, status, sampleCount, lastTrainedTime, lastEvent);
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
