package com.semi.eap.simulator;

import com.semi.eap.model.EqpStatusSnapshot;
import com.semi.eap.service.MockMesRmsService;
import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.hsms.HsmsConnectionMode;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.secs2.Secs2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 核心：在同一个进程内同时启动：
 * 1. 【虚拟半导体刻蚀机台 (EQP - Passive模式)】监听 TCP 5000 端口
 * 2. 【Java EAP 服务端 (Host - Active模式)】主动连接机台，处理 S1F13/S6F11/S7F3/S2F41 全流程
 *
 * 采用真实工业级 secs4j 协议栈进行 TCP/IP HSMS 二进制报文收发！
 */
@Component
public class SecsGemEngine {

    // 行业标准 CEID (Collection Event ID 事件编号)
    public static final int CEID_CONTROL_STATE_REMOTE = 1001; // 机台切到远程模式
    public static final int CEID_CARRIER_ID_READ      = 2001; // LoadPort 扫到晶圆盒 FOUP RFID
    public static final int CEID_PROCESS_STARTED      = 3001; // 机台开始加工晶圆
    public static final int CEID_WAFER_FDC_TRACE      = 3002; // 加工中上报片数与温度气压传感器数据
    public static final int CEID_PROCESS_COMPLETED    = 3003; // 25片晶圆全部加工完成
    public static final int CEID_ALARM_OVER_TEMP      = 9001; // 腔体超温报警

    @Value("${eap.hsms.ip:127.0.0.1}")
    private String hsmsIp;

    @Value("${eap.hsms.port:5000}")
    private int hsmsPort;

    @Value("${eap.hsms.device-id:10}")
    private int deviceId;

    private final MockMesRmsService mesRmsService;

    private HsmsSsCommunicator eqpComm;  // 模拟机台端 (Passive)
    private HsmsSsCommunicator eapHost;  // EAP 服务端 (Active)

    private final EqpStatusSnapshot state = new EqpStatusSnapshot();
    private final LinkedList<EqpStatusSnapshot.SecsLogEntry> logQueue = new LinkedList<>();
    private final ExecutorService simExecutor = Executors.newSingleThreadExecutor();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public SecsGemEngine(MockMesRmsService mesRmsService) {
        this.mesRmsService = mesRmsService;
    }

    @PostConstruct
    public void initAndStart() {
        try {
            startVirtualEquipment();
            startEapHost();
        } catch (Exception e) {
            addLog("SYSTEM", "ERROR", "启动 HSMS 协议栈异常", e.getMessage());
        }
    }

    /**
     * 1. 启动虚拟晶圆刻蚀机台 (EQP) - 被动监听 127.0.0.1:5000
     */
    private void startVirtualEquipment() throws Exception {
        HsmsSsCommunicatorConfig eqpConfig = new HsmsSsCommunicatorConfig();
        eqpConfig.connectionMode(HsmsConnectionMode.PASSIVE);
        eqpConfig.socketAddress(new InetSocketAddress(hsmsIp, hsmsPort));
        eqpConfig.sessionId(deviceId);
        eqpConfig.isEquip(true);
        eqpConfig.timeout().t3(10.0F);

        eqpComm = HsmsSsCommunicator.newInstance(eqpConfig);

        // 机台收到 EAP 发来的指令时的硬件响应逻辑
        eqpComm.addSecsMessageReceiveListener(msg -> {
            int s = msg.getStream();
            int f = msg.getFunction();
            try {
                if (s == 1 && f == 13) {
                    // S1F13 建立通信请求 -> 回复 S1F14 COMMACK=0 (接受)
                    Secs2 reply = Secs2.list(
                            Secs2.binary((byte) 0x00),
                            Secs2.list(Secs2.ascii("ETCH-EQP-01"), Secs2.ascii("V2.6"))
                    );
                    eqpComm.send(msg, 1, 14, false, reply);
                } else if (s == 1 && f == 1) {
                    // S1F1 Are You There -> 回复 S1F2 On Line Data
                    eqpComm.send(msg, 1, 2, false, Secs2.list(Secs2.ascii("ETCH-EQP-01"), Secs2.ascii("V2.6")));
                } else if (s == 7 && f == 3) {
                    // S7F3 EAP 下发配方 (Process Program Send) -> 回复 S7F4 ACKC7=0 (成功接收配方)
                    String rcpName = msg.secs2().getAscii(0);
                    synchronized (state) {
                        state.setCurrentRecipe(rcpName);
                        state.setProcessState("RECIPE_READY");
                    }
                    eqpComm.send(msg, 7, 4, false, Secs2.binary((byte) 0x00));
                } else if (s == 2 && f == 41) {
                    // S2F41 Host Command Send (远程控制指令: START / STOP / PP-SELECT)
                    String rcmd = msg.secs2().getAscii(0);
                    // 回复 S2F42 HCACK = 0 (指令已接受并执行)
                    eqpComm.send(msg, 2, 42, false, Secs2.list(Secs2.binary((byte) 0x00), Secs2.list()));

                    if ("START".equalsIgnoreCase(rcpOrCmd(rcmd))) {
                        triggerEquipmentWaferProcessing();
                    } else if ("STOP".equalsIgnoreCase(rcpOrCmd(rcmd))) {
                        synchronized (state) {
                            state.setProcessState("ABORTED_BY_EAP");
                            state.setRfPower(0.0);
                        }
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        eqpComm.open();
    }

    private String rcpOrCmd(String cmd) {
        return cmd == null ? "" : cmd.trim();
    }

    /**
     * 2. 启动我们的核心 Java EAP 服务端 (Host) - 主动连接机台并控制全流程
     */
    private void startEapHost() throws Exception {
        HsmsSsCommunicatorConfig hostConfig = new HsmsSsCommunicatorConfig();
        hostConfig.connectionMode(HsmsConnectionMode.ACTIVE);
        hostConfig.socketAddress(new InetSocketAddress(hsmsIp, hsmsPort));
        hostConfig.sessionId(deviceId);
        hostConfig.isEquip(false);
        hostConfig.timeout().t3(10.0F);
        hostConfig.timeout().t5(2.0F);

        eapHost = HsmsSsCommunicator.newInstance(hostConfig);

        // 监听 TCP HSMS 连接状态变化
        eapHost.addSecsCommunicatableStateChangeListener(connected -> {
            synchronized (state) {
                state.setHsmsConnected(connected);
                state.setControlState(connected ? "ONLINE_REMOTE" : "OFFLINE");
            }
            if (connected) {
                addLog("EAP <-> EQP", "HSMS", "TCP/IP HSMS Selected 握手成功",
                        "SessionID=" + deviceId + ", 链路已激活 (127.0.0.1:" + hsmsPort + ")");
                // 自动发起 S1F13 建立 SECS 通信
                simExecutor.submit(this::sendS1F13EstablishComm);
            }
        });

        // 核心考点：EAP 监听机台主动上报的 S6F11 (Event Report) 与 S5F1 (Alarm Report)
        eapHost.addSecsMessageReceiveListener(this::handleIncomingEquipmentMessage);

        eapHost.open();
    }

    /**
     * EAP 处理机台主动发来的报文（主要是 S6F11 事件上报）
     */
    private void handleIncomingEquipmentMessage(SecsMessage msg) {
        int s = msg.getStream();
        int f = msg.getFunction();
        try {
            if (s == 6 && f == 11) {
                // 解析 S6F11: <L [3] <U4 DATAID> <U4 CEID> <L [n] Reports...>>
                Secs2 body = msg.secs2();
                int ceid = body.getInt(1, 0);
                String payloadInfo = body.getAscii(2, 0);

                addLog("EQP -> EAP", "S6F11", "收到机台事件上报 (CEID=" + ceid + ")",
                        "S6F11 W <L [3] <U4 1> <U4 " + ceid + "> <A \"" + payloadInfo + "\">>");

                // 第一时间回复 S6F12 ACKC6 = 0x00（SECS协议规定必须先回复ACK，再异步走MES业务）
                eapHost.send(msg, 6, 12, false, Secs2.binary((byte) 0x00));
                addLog("EAP -> EQP", "S6F12", "EAP 确认收到事件 (ACKC6=0)", "S6F12 <B 0x00>");

                // 根据不同的 CEID 触发 EAP 自动化大脑逻辑
                dispatchCeidBusiness(ceid, payloadInfo);
            }
        } catch (Exception e) {
            addLog("EAP", "ERROR", "解析 S6F11 失败", e.getMessage());
        }
    }

    /**
     * EAP 状态机与业务编排核心（面试最核心的 6 步过站逻辑！）
     */
    private void dispatchCeidBusiness(int ceid, String data) {
        simExecutor.submit(() -> {
            try {
                if (ceid == CEID_CARRIER_ID_READ) {
                    // 【第1步】收到上料扫码事件 -> 询问 MES 校验晶圆盒
                    String carrierId = data;
                    synchronized (state) {
                        state.setProcessState("CARRIER_LOADED");
                        state.setCurrentCarrierId(carrierId);
                    }
                    addLog("EAP -> MES", "MQ-RPC", "向 MES 发起 Carrier 校验请求",
                            "VerifyCarrierRequest: CarrierID=" + carrierId + ", EqpID=ETCH-EQP-01");
                    Thread.sleep(500);

                    MockMesRmsService.LotInfo lot = mesRmsService.verifyCarrierAndQueryLot(carrierId);
                    synchronized (state) {
                        state.setCurrentLotId(lot.lotId);
                        state.setWaferCount(lot.waferCount);
                        state.setProcessedWafer(0);
                    }
                    addLog("MES -> EAP", "MQ-ACK", "MES 校验通过，返回 Lot 与配方信息",
                            "LotID=" + lot.lotId + ", WaferQty=" + lot.waferCount + ", Recipe=" + lot.recipeName);

                    // 【第2步】EAP 通过 S7F3 向机台下发配方 (Recipe Download)
                    Thread.sleep(500);
                    addLog("EAP -> EQP", "S7F3", "EAP 向机台下发工艺配方 (PP-Send)",
                            "S7F3 W <L [2] <A \"" + lot.recipeName + "\"> <B 0x20 0x26 ...>>");
                    Optional<SecsMessage> s7f4 = eapHost.send(7, 3, true,
                            Secs2.list(Secs2.ascii(lot.recipeName), Secs2.binary((byte) 0x20, (byte) 0x26)));
                    if (s7f4.isPresent()) {
                        addLog("EQP -> EAP", "S7F4", "机台确认配方接收成功 (ACKC7=0)", "S7F4 <B 0x00>");
                    }

                    // 【第3步】EAP 通知 MES 完成 TrackIn（进站登记），并向机台发送 S2F41 (START)
                    Thread.sleep(500);
                    addLog("EAP -> MES", "TrackIn", "EAP 通知 MES 批次进站成功 (TrackIn)",
                            "LotID=" + lot.lotId + " 状态变更为 RUN");

                    addLog("EAP -> EQP", "S2F41", "EAP 下发远程启动指令 (RCMD=START)",
                            "S2F41 W <L [2] <A \"START\"> <L [1] <A \"" + lot.lotId + "\">>>");
                    Optional<SecsMessage> s2f42 = eapHost.send(2, 41, true,
                            Secs2.list(Secs2.ascii("START"), Secs2.list(Secs2.ascii(lot.lotId))));
                    if (s2f42.isPresent()) {
                        addLog("EQP -> EAP", "S2F42", "机台接受 START 指令，开始刻蚀晶圆 (HCACK=0)",
                                "S2F42 <L [2] <B 0x00> <L [0]>>");
                    }

                } else if (ceid == CEID_WAFER_FDC_TRACE) {
                    // 【第4步】加工中实时收到机台上报的 FDC 传感器参数 -> 检查是否超标
                    // data 格式: "WAFER=5,TEMP=382.5,PRESS=15.2,RF=1200"
                    boolean ok = mesRmsService.checkFdcParameterInSpec(state.getChamberTemp(), state.getChamberPressure());
                    if (!ok) {
                        addLog("EAP -> FDC", "ALARM", "FDC 检测到腔体温度超标 (>490℃)！触发自动停机 Hold Lot！",
                                "CurrentTemp=" + state.getChamberTemp() + "℃");
                        eapHost.send(2, 41, true, Secs2.list(Secs2.ascii("STOP"), Secs2.list()));
                        addLog("EAP -> EQP", "S2F41", "EAP 紧急下发远程停机指令 (RCMD=STOP)", "S2F41 W <A \"STOP\">");
                        addLog("EAP -> MES", "HoldLot", "EAP 自动锁定异常批次 (Hold Lot)", "HoldReason=FDC_OVER_TEMP");
                    }
                } else if (ceid == CEID_PROCESS_COMPLETED) {
                    // 【第5步】25片晶圆全部加工完成 -> EAP 调用 MES TrackOut（出站）
                    Thread.sleep(400);
                    addLog("EAP -> MES", "TrackOut", "25片晶圆刻蚀完成，EAP 通知 MES 自动出站 (TrackOut)",
                            "LotID=" + state.getCurrentLotId() + " 加工完毕，呼叫天车(AMHS)搬走 FOUP 晶圆盒");
                }
            } catch (Exception e) {
                addLog("EAP", "ERROR", "EAP 业务编排异常", e.getMessage());
            }
        });
    }

    /**
     * 发送 S1F13 握手报文
     */
    public void sendS1F13EstablishComm() {
        try {
            addLog("EAP -> EQP", "S1F13", "发送建立通信握手请求 (Establish Communications)",
                    "S1F13 W <L [0]>");
            Optional<SecsMessage> reply = eapHost.send(1, 13, true, Secs2.list());
            if (reply.isPresent()) {
                String mdln = reply.get().secs2().getAscii(1, 0);
                String softrev = reply.get().secs2().getAscii(1, 1);
                addLog("EQP -> EAP", "S1F14", "握手成功！机台型号: " + mdln + " (" + softrev + ")",
                        "S1F14 <L [2] <B 0x00> <L [2] <A \"" + mdln + "\"> <A \"" + softrev + "\">>>");
            }
        } catch (Exception e) {
            addLog("EAP", "ERROR", "S1F13 发送失败", e.getMessage());
        }
    }

    /**
     * 发送 S1F1 心跳检测 (Are You There)
     */
    public void sendS1F1Heartbeat() {
        simExecutor.submit(() -> {
            try {
                addLog("EAP -> EQP", "S1F1", "发送心跳检测 (Are You There Request)", "S1F1 W");
                Optional<SecsMessage> reply = eapHost.send(1, 1, true, Secs2.empty());
                if (reply.isPresent()) {
                    addLog("EQP -> EAP", "S1F2", "收到机台在线心跳回复 (On Line Data)",
                            "S1F2 <L [2] <A \"ETCH-EQP-01\"> <A \"V2.6\">>");
                }
            } catch (Exception e) {
                addLog("EAP", "ERROR", "S1F1 心跳超时", e.getMessage());
            }
        });
    }

    /**
     * 模拟天车把晶圆盒 (FOUP) 放到机台 LoadPort 上，触发全自动过站流程！
     */
    public void simulateCarrierLoadOnPort(String carrierId) {
        new Thread(() -> {
            try {
                Secs2 s6f11 = Secs2.list(
                        Secs2.uint4(1),
                        Secs2.uint4(CEID_CARRIER_ID_READ),
                        Secs2.list(Secs2.ascii(carrierId))
                );
                eqpComm.send(6, 11, true, s6f11);
            } catch (Exception e) {
                addLog("EQP", "ERROR", "机台上报 S6F11 失败", e.getMessage());
            }
        }).start();
    }

    /**
     * 模拟机台收到 START 后，逐片加工 25 片晶圆并实时通过 S6F11 上报 FDC 传感器数据
     */
    private void triggerEquipmentWaferProcessing() {
        new Thread(() -> {
            try {
                synchronized (state) {
                    state.setProcessState("PROCESSING");
                    state.setChamberTemp(380.0);
                    state.setChamberPressure(15.0);
                    state.setRfPower(1200.0);
                }

                // 模拟分 5 批快速跑完 25 片晶圆（每批5片，方便演示）
                for (int w = 5; w <= 25; w += 5) {
                    Thread.sleep(900);
                    synchronized (state) {
                        if (!"PROCESSING".equals(state.getProcessState())) {
                            return; // 若被 EAP 紧急叫停则退出
                        }
                        state.setProcessedWafer(w);
                        double jitterTemp = 380.0 + Math.round((Math.random() * 6 - 3) * 10.0) / 10.0;
                        state.setChamberTemp(jitterTemp);
                    }
                    String traceMsg = String.format("WAFER=%d/25,TEMP=%.1fC,PRESS=15.0mTorr,RF=1200W",
                            state.getProcessedWafer(), state.getChamberTemp());
                    Secs2 fdcEvent = Secs2.list(
                            Secs2.uint4(1),
                            Secs2.uint4(CEID_WAFER_FDC_TRACE),
                            Secs2.list(Secs2.ascii(traceMsg))
                    );
                    eqpComm.send(6, 11, true, fdcEvent);
                }

                Thread.sleep(600);
                synchronized (state) {
                    if ("PROCESSING".equals(state.getProcessState())) {
                        state.setProcessState("COMPLETED");
                        state.setChamberTemp(45.0);
                        state.setRfPower(0.0);
                    }
                }
                // 上报加工完成事件 CEID=3003
                Secs2 compEvent = Secs2.list(
                        Secs2.uint4(1),
                        Secs2.uint4(CEID_PROCESS_COMPLETED),
                        Secs2.list(Secs2.ascii("LOT_COMPLETED_25_WAFERS"))
                );
                eqpComm.send(6, 11, true, compEvent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * 模拟机台腔体温度异常飙升（测试 FDC 拦截 + EAP 下发 STOP + MES Hold Lot）
     */
    public void simulateOverTempAlarm() {
        new Thread(() -> {
            try {
                synchronized (state) {
                    state.setChamberTemp(525.0); // 超过 490℃ 阈值
                    state.setProcessState("ALARM_OVER_TEMP");
                }
                Secs2 alarmEvent = Secs2.list(
                        Secs2.uint4(1),
                        Secs2.uint4(CEID_WAFER_FDC_TRACE),
                        Secs2.list(Secs2.ascii("FDC_ALERT:TEMP=525.0C_EXCEED_SPEC_490C"))
                );
                eqpComm.send(6, 11, true, alarmEvent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    public synchronized void addLog(String direction, String sxfy, String title, String smlDetail) {
        String now = LocalTime.now().format(TIME_FMT);
        logQueue.addFirst(new EqpStatusSnapshot.SecsLogEntry(now, direction, sxfy, title, smlDetail));
        while (logQueue.size() > 60) {
            logQueue.removeLast();
        }
    }

    public synchronized EqpStatusSnapshot getSnapshot() {
        state.setSecsLogs(new LinkedList<>(logQueue));
        return state;
    }

    public synchronized void clearLogs() {
        logQueue.clear();
    }

    @PreDestroy
    public void shutdown() {
        try {
            if (eapHost != null) eapHost.close();
            if (eqpComm != null) eqpComm.close();
            simExecutor.shutdownNow();
        } catch (Exception ignored) {}
    }
}
