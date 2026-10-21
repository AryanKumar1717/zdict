package dev.zdict.core;

/**
 * Event recorded in the zdict event log.
 */
public class ZdictEvent {

    private String timestamp;
    private String type; // TRAINED, DRIFT_DETECTED, CANARY_STARTED, PROMOTED, ROLLED_BACK, NOT_WORTH_IT
    private String group;
    private String dictionaryId;
    private String reason;

    public ZdictEvent() {
    }

    public ZdictEvent(String timestamp, String type, String group, String dictionaryId, String reason) {
        this.timestamp = timestamp;
        this.type = type;
        this.group = group;
        this.dictionaryId = dictionaryId;
        this.reason = reason;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
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

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
