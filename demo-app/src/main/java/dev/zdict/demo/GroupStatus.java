package dev.zdict.demo;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Status of an endpoint group returned by GET /actuator/zdict/groups.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GroupStatus {

    private String group;
    private String activeId;
    private String canaryId;
    private Double baselineRatio;
    private Double rollingRatio;
    private String status; // HEALTHY | DRIFTING | CANARY | NO_DICTIONARY
    private int sampleCount;
    private String lastTrainedTime;
    private ZdictEvent lastEvent;

    public GroupStatus() {
    }

    public GroupStatus(String group, String activeId, String canaryId, Double baselineRatio,
                       Double rollingRatio, String status, int sampleCount,
                       String lastTrainedTime, ZdictEvent lastEvent) {
        this.group = group;
        this.activeId = activeId;
        this.canaryId = canaryId;
        this.baselineRatio = baselineRatio;
        this.rollingRatio = rollingRatio;
        this.status = status;
        this.sampleCount = sampleCount;
        this.lastTrainedTime = lastTrainedTime;
        this.lastEvent = lastEvent;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getActiveId() {
        return activeId;
    }

    public void setActiveId(String activeId) {
        this.activeId = activeId;
    }

    public String getCanaryId() {
        return canaryId;
    }

    public void setCanaryId(String canaryId) {
        this.canaryId = canaryId;
    }

    public Double getBaselineRatio() {
        return baselineRatio;
    }

    public void setBaselineRatio(Double baselineRatio) {
        this.baselineRatio = baselineRatio;
    }

    public Double getRollingRatio() {
        return rollingRatio;
    }

    public void setRollingRatio(Double rollingRatio) {
        this.rollingRatio = rollingRatio;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(int sampleCount) {
        this.sampleCount = sampleCount;
    }

    public String getLastTrainedTime() {
        return lastTrainedTime;
    }

    public void setLastTrainedTime(String lastTrainedTime) {
        this.lastTrainedTime = lastTrainedTime;
    }

    public ZdictEvent getLastEvent() {
        return lastEvent;
    }

    public void setLastEvent(ZdictEvent lastEvent) {
        this.lastEvent = lastEvent;
    }
}
