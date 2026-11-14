package com.henu.whiteboard.ws;

import jakarta.websocket.server.ServerContainer;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册原生 WebSocket 端点 /ws，并放开跨域来源供课程演示使用。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private static final int MAX_TEXT_BUFFER = 256 * 1024;

    private final RoomSocketHandler roomSocketHandler;

    public WebSocketConfig(RoomSocketHandler roomSocketHandler) {
        this.roomSocketHandler = roomSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(roomSocketHandler, "/ws")
                .setAllowedOriginPatterns("*");
    }

    /**
     * 容器启动后把 WebSocket 帧缓冲调到协议上限 256KB。
     * 用 ServletContextInitializer 而不是 ServletServerContainerFactoryBean，
     * 避免 @SpringBootTest 的 MOCK Web 环境因缺少 ServerContainer 而启动失败。
     */
    @Bean
    public ServletContextInitializer webSocketBufferInitializer() {
        return servletContext -> {
            Object attribute = servletContext.getAttribute(ServerContainer.class.getName());
            if (attribute instanceof ServerContainer container) {
                container.setDefaultMaxTextMessageBufferSize(MAX_TEXT_BUFFER);
                container.setDefaultMaxBinaryMessageBufferSize(MAX_TEXT_BUFFER);
            }
        };
    }
}
