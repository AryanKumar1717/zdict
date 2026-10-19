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
    private String state; // ACTIVE, RETIRED

    public DictionaryMetadata() {
    }

    public DictionaryMetadata(String id, String group, String createdAt, int sampleCount,
                              int dictSize, double holdoutRatio, String state) {
        this.id = id;
        this.group = group;
        this.createdAt = createdAt;
        this.sampleCount = sampleCount;
        this.dictSize = dictSize;
        this.holdoutRatio = holdoutRatio;
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

    public double getHoldoutRatio() {
        return holdoutRatio;
    }

    public void setHoldoutRatio(double holdoutRatio) {
        this.holdoutRatio = holdoutRatio;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(state);
    }
}
