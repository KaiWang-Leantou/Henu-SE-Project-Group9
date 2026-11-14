package com.henu.whiteboard.board;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 只读强制集成测试：真实 WS 连接 + REST 归档，验证写命令统一返回 READ_ONLY，
 * 同时核对 init / presence 的成员颜色与归档时的 board.reload 广播。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class ReadOnlyWsTest {

    private static final String PASSWORD = "Abc12345";

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void archivedBoardRejectsWsWritesWithReadOnly() throws Exception {
        String token = register("wsowner");
        String boardId = createBoard(token, "WS 只读");

        CollectingHandler handler = new CollectingHandler(objectMapper);
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(handler,
                        "ws://127.0.0.1:" + port + "/ws?token="
                                + URLEncoder.encode(token, StandardCharsets.UTF_8))
                .get(10, TimeUnit.SECONDS);
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                    "t", "join", "boardId", boardId, "lastSeq", 0))));
            JsonNode init = handler.await(message -> "init".equals(message.path("t").asText()), 5_000);
            assertNotNull(init, "join 后应收到 init");

            // 成员颜色：init.me 与 members[] 都带 #RRGGBB，且同一用户颜色一致
            String color = init.path("me").path("color").asText();
            assertTrue(color.matches("^#[0-9A-Fa-f]{6}$"), "init.me.color 应为 #RRGGBB");
            assertTrue(init.path("members").size() >= 1, "members 至少包含自己");
            assertEquals(color, init.path("members").get(0).path("color").asText());
            JsonNode presence = handler.await(message ->
                    "presence".equals(message.path("t").asText()), 5_000);
            assertNotNull(presence, "join 后应收到 presence");
            assertEquals(color, presence.path("members").get(0).path("color").asText());

            // 归档：WS 应收到 board.reload
            mockMvc.perform(patch("/api/boards/" + boardId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status":"archived"}
                                    """))
                    .andExpect(status().isOk());
            JsonNode reload = handler.await(message ->
                    "board.reload".equals(message.path("t").asText()), 5_000);
            assertNotNull(reload, "归档后应广播 board.reload");
            assertEquals("archived", reload.path("reason").asText());

            // stroke.append 被拒：READ_ONLY
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                    "t", "stroke.append", "cid", "c-append", "pageId", "p_1",
                    "strokeId", "s_ro", "tool", "pen", "color", "#111111", "width", 4,
                    "pts", List.of(10, 10, 20, 20)))));
            JsonNode appendError = handler.await(message ->
                    "error".equals(message.path("t").asText())
                            && "c-append".equals(message.path("cid").asText()), 5_000);
            assertNotNull(appendError, "stroke.append 应收到 error");
            assertEquals("READ_ONLY", appendError.path("code").asText());

            // stroke.end 被拒：READ_ONLY
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                    "t", "stroke.end", "cid", "c-end", "strokeId", "s_ro"))));
            JsonNode endError = handler.await(message ->
                    "error".equals(message.path("t").asText())
                            && "c-end".equals(message.path("cid").asText()), 5_000);
            assertNotNull(endError, "stroke.end 应收到 error");
            assertEquals("READ_ONLY", endError.path("code").asText());

            // board 写命令被拒：READ_ONLY
            Map<String, Object> cmd = Map.of(
                    "t", "page.rename", "pageId", "p_1", "name", "只读页");
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                    "t", "op", "cid", "c-op", "ch", "board", "cmd", cmd))));
            JsonNode opError = handler.await(message ->
                    "error".equals(message.path("t").asText())
                            && "c-op".equals(message.path("cid").asText()), 5_000);
            assertNotNull(opError, "op 写命令应收到 error");
            assertEquals("READ_ONLY", opError.path("code").asText());
        } finally {
            try {
                session.close(CloseStatus.NORMAL);
            } catch (Exception ignored) {
                // 关闭失败不影响断言结果
            }
        }
    }

    private String createBoard(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/boards")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(name)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn();
        return dataOf(result).path("id").asText();
    }

    private String register(String prefix) throws Exception {
        String username = prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","nickname":"%s"}
                                """.formatted(username, PASSWORD, prefix)))
                .andExpect(status().isOk())
                .andReturn();
        String token = dataOf(result).path("token").asText();
        assertFalse(token.isBlank(), "注册应返回 token");
        return token;
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("data");
    }

    /** 收集所有 S→C 消息，按谓词轮询等待，避免时序抖动。 */
    private static final class CollectingHandler extends TextWebSocketHandler {

        private final ObjectMapper objectMapper;
        private final List<JsonNode> messages = new CopyOnWriteArrayList<>();

        private CollectingHandler(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            try {
                messages.add(objectMapper.readTree(message.getPayload()));
            } catch (Exception ignored) {
                // 非 JSON 帧直接忽略
            }
        }

        private JsonNode await(Predicate<JsonNode> predicate, long timeoutMillis)
                throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                for (JsonNode message : messages) {
                    if (predicate.test(message)) {
                        return message;
                    }
                }
                Thread.sleep(25L);
            }
            return null;
        }
    }
}
