package com.semi.eap.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 半导体机台 (EQP) 实时状态与 SECS 报文日志模型
 */
public class EqpStatusSnapshot {
    private String eqpId = "ETCH-EQP-01 (刻蚀机01号)";
    private boolean hsmsConnected = false;
    private String controlState = "OFFLINE"; // OFFLINE / ONLINE_REMOTE
    private String processState = "IDLE";    // IDLE / READY_TO_LOAD / CARRIER_LOADED / RECIPE_READY / PROCESSING / COMPLETED / ALARM
    private String currentCarrierId = "-";   // FOUP 晶圆盒号
    private String currentLotId = "-";       // 批次号 (Lot)
    private String currentRecipe = "-";      // 工艺配方 (Recipe)
    private int waferCount = 0;              // 晶圆片数 (标准1盒25片)
    private int processedWafer = 0;          // 已加工片数
    private double chamberTemp = 25.0;       // 腔体温度 (FDC 传感器数据)
    private double chamberPressure = 101.3;  // 腔体气压 (mTorr)
    private double rfPower = 0.0;            // 射频功率 (W)
    private List<SecsLogEntry> secsLogs = new ArrayList<>();

    public static class SecsLogEntry {
        private String time;
        private String direction; // "EQP -> EAP" or "EAP -> EQP" or "EAP -> MES"
        private String sxfy;      // e.g. S6F11, S2F41
        private String title;
        private String smlDetail; // SECS Message Language 内容解释

        public SecsLogEntry() {}

        public SecsLogEntry(String time, String direction, String sxfy, String title, String smlDetail) {
            this.time = time;
            this.direction = direction;
            this.sxfy = sxfy;
            this.title = title;
            this.smlDetail = smlDetail;
        }

        public String getTime() { return time; }
        public void setTime(String time) { this.time = time; }
        public String getDirection() { return direction; }
        public void setDirection(String direction) { this.direction = direction; }
        public String getSxfy() { return sxfy; }
        public void setSxfy(String sxfy) { this.sxfy = sxfy; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getSmlDetail() { return smlDetail; }
        public void setSmlDetail(String smlDetail) { this.smlDetail = smlDetail; }
    }

    public String getEqpId() { return eqpId; }
    public void setEqpId(String eqpId) { this.eqpId = eqpId; }
    public boolean isHsmsConnected() { return hsmsConnected; }
    public void setHsmsConnected(boolean hsmsConnected) { this.hsmsConnected = hsmsConnected; }
    public String getControlState() { return controlState; }
    public void setControlState(String controlState) { this.controlState = controlState; }
    public String getProcessState() { return processState; }
    public void setProcessState(String processState) { this.processState = processState; }
    public String getCurrentCarrierId() { return currentCarrierId; }
    public void setCurrentCarrierId(String currentCarrierId) { this.currentCarrierId = currentCarrierId; }
    public String getCurrentLotId() { return currentLotId; }
    public void setCurrentLotId(String currentLotId) { this.currentLotId = currentLotId; }
    public String getCurrentRecipe() { return currentRecipe; }
    public void setCurrentRecipe(String currentRecipe) { this.currentRecipe = currentRecipe; }
    public int getWaferCount() { return waferCount; }
    public void setWaferCount(int waferCount) { this.waferCount = waferCount; }
    public int getProcessedWafer() { return processedWafer; }
    public void setProcessedWafer(int processedWafer) { this.processedWafer = processedWafer; }
    public double getChamberTemp() { return chamberTemp; }
    public void setChamberTemp(double chamberTemp) { this.chamberTemp = chamberTemp; }
    public double getChamberPressure() { return chamberPressure; }
    public void setChamberPressure(double chamberPressure) { this.chamberPressure = chamberPressure; }
    public double getRfPower() { return rfPower; }
    public void setRfPower(double rfPower) { this.rfPower = rfPower; }
    public List<SecsLogEntry> getSecsLogs() { return secsLogs; }
    public void setSecsLogs(List<SecsLogEntry> secsLogs) { this.secsLogs = secsLogs; }
}
