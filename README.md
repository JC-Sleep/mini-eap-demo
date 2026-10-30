# 半导体 CIM Mini-EAP & 虚拟晶圆机台实战项目 (Spring Boot + secs4j)

本项目专为 **Java 开发者转型半导体 CIM (MES / EAP / FDC)** 打造，无需安装任何外部商业模拟器，单个 Spring Boot 进程内同时启动：
1. **虚拟晶圆刻蚀机台 (`EQP - Passive` 模式)**：监听 TCP `127.0.0.1:5000`，模拟真实半导体机台的 LoadPort 扫码、配方接收、晶圆加工、温度传感器上报。
2. **Java EAP 设备自动化服务端 (`Host - Active` 模式)**：主动连接机台，基于工业级开源协议栈 `secs4j` 实现完整的 **HSMS + SECS-II** 报文收发与 **MES/RMS/FDC 6步过站业务编排**。

---

## 一、 如何运行
1. 使用 **IntelliJ IDEA** 打开 `C:\WorkSoftware\a_program\mini-eap-demo` 目录，等待 Maven 下载依赖（兼容本机 **Java 8+**）。
2. 直接运行主类：`com.semi.eap.MiniEapApplication`。
3. 浏览器打开监控大屏：**[http://localhost:8088](http://localhost:8088)**

---

## 二、 面试核心流程与代码对照表（背熟即可应对 90% EAP 面试）

| 晶圆过站阶段 | SECS-II 报文 | 方向 | 对应源码位置 (`SecsGemEngine.java`) | 面试话术要点 |
| :--- | :--- | :--- | :--- | :--- |
| **1. 链路与通信握手** | `S1F13` / `S1F14` | EAP $\leftrightarrow$ EQP | `sendS1F13EstablishComm()` | TCP HSMS Selected 后，必须先发 S1F13 建立应用层通信，拿到机台型号(MDLN)与软件版本(SOFTREV)。 |
| **2. 心跳保活** | `S1F1` / `S1F2` | EAP $\leftrightarrow$ EQP | `sendS1F1Heartbeat()` | Are You There 检测，配合 HSMS 的 T3（回复超时）、T5（重连间隔）保证长连接高可用。 |
| **3. 上料扫码上报** | `S6F11` / `S6F12` (`CEID=2001`) | EQP $\rightarrow$ EAP | `handleIncomingEquipmentMessage()` | 机台扫到晶圆盒 FOUP 后上报 S6F11，**EAP 必须第一时间先回 S6F12 (ACKC6=0)**，然后再异步调 MES 校验。 |
| **4. 配方自动下发** | `S7F3` / `S7F4` | EAP $\rightarrow$ EQP | `dispatchCeidBusiness()` | MES 校验 Carrier 通过后，EAP 从 RMS 获取 Recipe，通过 S7F3 下发给机台并校验 `ACKC7=0`。 |
| **5. 进站并远程启动** | `S2F41` / `S2F42` (`RCMD=START`) | EAP $\rightarrow$ EQP | `dispatchCeidBusiness()` | EAP 调 MES 完成 `TrackIn` 后，发 S2F41 命令机台自动开始加工（无需工人手动按机台按钮）。 |
| **6. FDC 超标拦截与出站** | `S6F11` + `S2F41(STOP)` | 双向 | `simulateOverTempAlarm()` | 加工中实时监控温度/气压，一旦 FDC 判定超标（如 >490℃），EAP 立即发 `S2F41(STOP)` 停机并通知 MES `HoldLot`。 |
