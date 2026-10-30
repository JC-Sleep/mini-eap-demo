package com.semi.eap.controller;

import com.semi.eap.model.EqpStatusSnapshot;
import com.semi.eap.simulator.SecsGemEngine;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/eap")
public class EapDashboardController {

    private final SecsGemEngine engine;

    public EapDashboardController(SecsGemEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/status")
    public EqpStatusSnapshot getStatus() {
        return engine.getSnapshot();
    }

    @PostMapping("/load-carrier")
    public Map<String, String> loadCarrier(@RequestParam(defaultValue = "FOUP-8801") String carrierId) {
        engine.simulateCarrierLoadOnPort(carrierId);
        return Collections.singletonMap("result", "Triggered S6F11 CarrierIDRead for " + carrierId);
    }

    @PostMapping("/heartbeat")
    public Map<String, String> heartbeat() {
        engine.sendS1F1Heartbeat();
        return Collections.singletonMap("result", "Sent S1F1");
    }

    @PostMapping("/over-temp-alarm")
    public Map<String, String> triggerAlarm() {
        engine.simulateOverTempAlarm();
        return Collections.singletonMap("result", "Triggered Over-Temp FDC Interlock");
    }

    @PostMapping("/clear-logs")
    public Map<String, String> clearLogs() {
        engine.clearLogs();
        return Collections.singletonMap("result", "Cleared");
    }
}
