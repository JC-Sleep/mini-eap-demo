package com.semi.eap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MiniEapApplication {
    public static void main(String[] args) {
        SpringApplication.run(MiniEapApplication.class, args);
        System.out.println("==============================================================");
        System.out.println("  半导体 Mini-EAP 与虚拟晶圆机台已启动！");
        System.out.println("  请用浏览器打开监控大屏: http://localhost:8088");
        System.out.println("==============================================================");
    }
}
