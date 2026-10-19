package dev.zdict.demo;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result returned by the dictionary trainer service and the /actuator/zdict/train endpoint.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TrainResult {

    private boolean promoted;
    private String decision;
    private String group;
    private String dictionaryId;
    private int sampleCount;
    private int trainCount;
    private int holdoutCount;
    private double plainHoldoutRatio;
    private Double activeHoldoutRatio;
    private double newHoldoutRatio;
    private String reason;

    public TrainResult() {
    }

    public static TrainResult refused(String group, int sampleCount, int minSamples) {
        TrainResult r = new TrainResult();
        r.promoted = false;
        r.decision = "REFUSED";
        r.group = group;
        r.sampleCount = sampleCount;
        r.reason = "Sample count " + sampleCount + " is below minimum required " + minSamples;
        return r;
    }

    public boolean isPromoted() {
        return promoted;
    }

    public void setPromoted(boolean promoted) {
        this.promoted = promoted;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getDictionaryId() {
        return dictionaryId;
    }

    public void setDictionaryId(String dictionaryId) {
        this.dictionaryId = dictionaryId;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(int sampleCount) {
        this.sampleCount = sampleCount;
    }

    public int getTrainCount() {
        return trainCount;
    }

    public void setTrainCount(int trainCount) {
        this.trainCount = trainCount;
    }

    public int getHoldoutCount() {
        return holdoutCount;
    }

    public void setHoldoutCount(int holdoutCount) {
        this.holdoutCount = holdoutCount;
    }

    public double getPlainHoldoutRatio() {
        return plainHoldoutRatio;
    }

    public void setPlainHoldoutRatio(double plainHoldoutRatio) {
        this.plainHoldoutRatio = plainHoldoutRatio;
    }

    public Double getActiveHoldoutRatio() {
        return activeHoldoutRatio;
    }

    public void setActiveHoldoutRatio(Double activeHoldoutRatio) {
        this.activeHoldoutRatio = activeHoldoutRatio;
    }

    public double getNewHoldoutRatio() {
        return newHoldoutRatio;
    }

    public void setNewHoldoutRatio(double newHoldoutRatio) {
        this.newHoldoutRatio = newHoldoutRatio;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
