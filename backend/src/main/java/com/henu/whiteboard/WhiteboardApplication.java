package com.henu.whiteboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 在线协同白板后端的启动入口。
 * 开启定时任务：版本 GC 每日 03:00 执行（BoardVersionService#gc）。
 */
@SpringBootApplication
@EnableScheduling
public class WhiteboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(WhiteboardApplication.class, args);
    }
}
