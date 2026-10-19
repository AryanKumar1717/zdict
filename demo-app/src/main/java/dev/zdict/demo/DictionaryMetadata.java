package dev.zdict.demo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Metadata stored in <id>.json alongside each dictionary file.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DictionaryMetadata {

    private String id;
    private String group;
    private String createdAt;
    private int sampleCount;
    private int dictSize;
    private double holdoutRatio;
    private double baseline;
    private String state; // ACTIVE, RETIRED

    public DictionaryMetadata() {
    }

    public DictionaryMetadata(String id, String group, String createdAt, int sampleCount,
                              int dictSize, double holdoutRatio, String state) {
        this(id, group, createdAt, sampleCount, dictSize, holdoutRatio,
                holdoutRatio > 0 && holdoutRatio < 1.0 ? (1.0 / holdoutRatio) : holdoutRatio, state);
    }

    public DictionaryMetadata(String id, String group, String createdAt, int sampleCount,
                              int dictSize, double holdoutRatio, double baseline, String state) {
        this.id = id;
        this.group = group;
        this.createdAt = createdAt;
        this.sampleCount = sampleCount;
        this.dictSize = dictSize;
        this.holdoutRatio = holdoutRatio;
        this.baseline = baseline;
        this.state = state;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(int sampleCount) {
        this.sampleCount = sampleCount;
    }

    public int getDictSize() {
        return dictSize;
    }

    public void setDictSize(int dictSize) {
        this.dictSize = dictSize;
    }

    private String rejectionReason;

    public double getHoldoutRatio() {
        return holdoutRatio;
    }

    public void setHoldoutRatio(double holdoutRatio) {
        this.holdoutRatio = holdoutRatio;
    }

    public double getBaseline() {
        return baseline;
    }

    public void setBaseline(double baseline) {
        this.baseline = baseline;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(state);
    }

    public boolean isCanary() {
        return "CANARY".equalsIgnoreCase(state);
    }

    public boolean isRetired() {
        return "RETIRED".equalsIgnoreCase(state);
    }
}
