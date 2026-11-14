package com.henu.whiteboard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/**
 * 启动上下文冒烟测试，确认 Spring / JPA / WebSocket 配置能完整装配。
 *
 * <p>REPLACE_DEFAULTS 只保留依赖注入监听器：本机沙箱禁止 Mockito/ByteBuddy
 * 自附加进程，而 Spring Boot 默认的 ResetMocksTestExecutionListener 会在
 * 没有 mock 的场景下也触发 Mockito 初始化。这里不使用 Mockito，故显式收窄监听器。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class WhiteboardApplicationTests {

    @Test
    void contextLoads() {
    }
}
