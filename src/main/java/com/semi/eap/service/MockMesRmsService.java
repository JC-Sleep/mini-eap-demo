package com.semi.eap.service;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 模拟晶圆厂上层系统：MES (制造执行系统) + RMS (配方管理系统) + FDC (故障检测系统)
 * 在真实晶圆厂中，EAP 通过 RabbitMQ / Kafka / TIBCO RV 与这些系统交互
 */
@Service
public class MockMesRmsService {

    // 模拟 MES 中的在制品批次表 (CarrierID -> LotInfo)
    private final Map<String, LotInfo> mesLotDatabase = new HashMap<>();
    // 模拟 RMS 中的标准工艺配方参数表 (RecipeName -> 设定温度)
    private final Map<String, Double> rmsRecipeDatabase = new HashMap<>();

    public static class LotInfo {
        public final String carrierId;
        public final String lotId;
        public final String recipeName;
        public final int waferCount;

        public LotInfo(String carrierId, String lotId, String recipeName, int waferCount) {
            this.carrierId = carrierId;
            this.lotId = lotId;
            this.recipeName = recipeName;
            this.waferCount = waferCount;
        }
    }

    public MockMesRmsService() {
        // 初始化两盒标准25片12寸晶圆数据
        mesLotDatabase.put("FOUP-8801", new LotInfo("FOUP-8801", "LOT-2026-N7-001", "RCP-ETCH-7NM-POLY", 25));
        mesLotDatabase.put("FOUP-8802", new LotInfo("FOUP-8802", "LOT-2026-N5-099", "RCP-ETCH-5NM-OXIDE", 25));

        rmsRecipeDatabase.put("RCP-ETCH-7NM-POLY", 380.0);
        rmsRecipeDatabase.put("RCP-ETCH-5NM-OXIDE", 420.0);
    }

    /**
     * 模拟 MES TrackIn 前校验：检查该晶圆盒是否合法、返回对应的 Lot 与 Recipe
     */
    public LotInfo verifyCarrierAndQueryLot(String carrierId) {
        return mesLotDatabase.getOrDefault(carrierId,
                new LotInfo(carrierId, "LOT-AUTO-9527", "RCP-ETCH-7NM-POLY", 25));
    }

    /**
     * 模拟 RMS 配方校验 (Recipe Body Check)
     */
    public boolean checkRecipeValid(String recipeName) {
        return rmsRecipeDatabase.containsKey(recipeName);
    }

    /**
     * 模拟 FDC 实时传感器监控（当温度超过 490℃ 触发超标拦截）
     */
    public boolean checkFdcParameterInSpec(double temp, double pressure) {
        return temp <= 490.0 && pressure >= 5.0;
    }
}
