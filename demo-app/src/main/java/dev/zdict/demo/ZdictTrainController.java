package dev.zdict.demo;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Actuator trigger endpoint for manual dictionary training.
 * POST /actuator/zdict/train?group=...
 */
@RestController
@RequestMapping("/actuator/zdict")
public class ZdictTrainController {

    private final DictionaryTrainerService trainerService;

    public ZdictTrainController(DictionaryTrainerService trainerService) {
        this.trainerService = trainerService;
    }

    @PostMapping("/train")
    public ResponseEntity<TrainResult> train(@RequestParam("group") String group) throws Exception {
        TrainResult result = trainerService.train(group);
        return ResponseEntity.ok(result);
    }
}
