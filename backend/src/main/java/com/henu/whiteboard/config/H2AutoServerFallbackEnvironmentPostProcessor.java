package com.henu.whiteboard.config;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;

/**
 * dev 环境 H2 兼容兜底：受限令牌/只读文件系统下 NIO 的 isWritable 为 false 时，
 * H2 会把文件库标记成只读并拒绝 AUTO_SERVER；此时去掉 AUTO_SERVER，让应用至少能只读启动。
 */
public class H2AutoServerFallbackEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Log log = LogFactory.getLog(H2AutoServerFallbackEnvironmentPostProcessor.class);
    private static final String H2_FILE_PREFIX = "jdbc:h2:file:";
    private static final String AUTO_SERVER = "AUTO_SERVER=TRUE";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty("spring.datasource.url");
        if (url == null || !url.startsWith(H2_FILE_PREFIX)
                || !url.toUpperCase(Locale.ROOT).contains(AUTO_SERVER)) {
            return;
        }

        String pathPart = url.substring(H2_FILE_PREFIX.length());
        int end = pathPart.indexOf(';');
        if (end >= 0) {
            pathPart = pathPart.substring(0, end);
        }
        Path databaseFile = Paths.get(pathPart).toAbsolutePath();
        if (!databaseFile.toString().toLowerCase(Locale.ROOT).endsWith(".mv.db")) {
            databaseFile = Paths.get(databaseFile + ".mv.db");
        }
        if (!Files.exists(databaseFile) || Files.isWritable(databaseFile)) {
            return;
        }

        String adjusted = url.replace(";" + AUTO_SERVER, "").replace(AUTO_SERVER + ";", "");
        environment.getPropertySources().addFirst(new MapPropertySource(
                "wbH2AutoServerFallback", Map.of("spring.datasource.url", adjusted)));
        String warning = "[WB] H2 文件 " + databaseFile + " 的 NIO 可写判定为 false（受限令牌/只读环境），"
                + "已回退为不使用 AUTO_SERVER；本次启动数据库只读，写操作会失败。"
                + "正常桌面环境请确保数据目录可写以恢复完整功能。";
        log.warn(warning);
        // 此时日志系统可能尚未初始化，兜底输出到 stderr，确保该降级可见
        System.err.println(warning);
    }

    @Override
    public int getOrder() {
        // 必须晚于 ConfigDataEnvironmentPostProcessor，才能读到 application-dev.yml 里的 URL
        return Ordered.LOWEST_PRECEDENCE;
    }
}
