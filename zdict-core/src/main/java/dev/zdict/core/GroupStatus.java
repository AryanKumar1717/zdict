package dev.zdict.core;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Status of an endpoint group returned by GET /actuator/zdict/groups or dashboard.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GroupStatus {

    private String group;
    private String activeId;
    private String canaryId;
    private Double baselineRatio;
    private Double rollingRatio;
    private String status; // HEALTHY | DRIFTING | CANARY | NO_DICTIONARY | NOT_WORTH_IT
    private Double gainVsZstd;
    private Double gainVsGzip;
    private Double minGain;
    private int sampleCount;
    private String lastTrainedTime;
    private ZdictEvent lastEvent;
    private long bytesOriginal;
    private long bytesCompressed;
    private long bytesSaved;
    private long compressedResponses;

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

    public long getBytesOriginal() {
        return bytesOriginal;
    }

    public void setBytesOriginal(long bytesOriginal) {
        this.bytesOriginal = bytesOriginal;
    }

    public long getBytesCompressed() {
        return bytesCompressed;
    }

    public void setBytesCompressed(long bytesCompressed) {
        this.bytesCompressed = bytesCompressed;
    }

    public long getBytesSaved() {
        return bytesSaved;
    }

    public void setBytesSaved(long bytesSaved) {
        this.bytesSaved = bytesSaved;
    }

    public long getCompressedResponses() {
        return compressedResponses;
    }

    public void setCompressedResponses(long compressedResponses) {
        this.compressedResponses = compressedResponses;
    }

    public Double getGainVsZstd() {
        return gainVsZstd;
    }

    public void setGainVsZstd(Double gainVsZstd) {
        this.gainVsZstd = gainVsZstd;
    }

    public Double getGainVsGzip() {
        return gainVsGzip;
    }

    public void setGainVsGzip(Double gainVsGzip) {
        this.gainVsGzip = gainVsGzip;
    }

    public Double getMinGain() {
        return minGain;
    }

    public void setMinGain(Double minGain) {
        this.minGain = minGain;
    }
}
