package com.henu.whiteboard.board;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 作品库 REST 测试：建板、列表隔离、私有板 403、改名、缩略图与游客建板 403。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class BoardApiTest {

    private static final String PASSWORD = "Abc12345";
    private static final String PNG_DATA_URL =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void ownerLifecycleAndAnotherUserIsForbidden() throws Exception {
        String tokenA = register("owner");
        String tokenB = register("other");

        // A 建板：默认 draft + owner，并写入 owner 成员
        MvcResult create = mockMvc.perform(post("/api/boards")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"动物速写"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.name").value("动物速写"))
                .andExpect(jsonPath("$.data.role").value("owner"))
                .andExpect(jsonPath("$.data.status").value("active"))
                .andReturn();
        String boardId = dataOf(create).path("id").asText();
        assertTrue(boardId.startsWith("b_"));

        // A 的列表能看到
        mockMvc.perform(get("/api/boards").param("scope", "mine")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(boardId));

        // B 的列表看不到 A 的板
        mockMvc.perform(get("/api/boards").param("scope", "mine")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        // B 通过链接访问 A 的板：可读可写（链接即分享），但不是 owner
        mockMvc.perform(get("/api/boards/" + boardId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("editor"));

        // B 改名 -> 403（owner 专属）
        mockMvc.perform(patch("/api/boards/" + boardId)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"改名试试"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // A 改名成功
        mockMvc.perform(patch("/api/boards/" + boardId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"动物速写 2"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        // A 上传缩略图成功
        mockMvc.perform(put("/api/boards/" + boardId + "/thumbnail")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("dataUrl", PNG_DATA_URL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        // 详情返回改名后的名称、owner 角色与缩略图
        mockMvc.perform(get("/api/boards/" + boardId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("动物速写 2"))
                .andExpect(jsonPath("$.data.role").value("owner"))
                .andExpect(jsonPath("$.data.public").value(true))
                .andExpect(jsonPath("$.data.thumbnail").value(PNG_DATA_URL));
    }

    @Test
    void guestCannotCreateBoard() throws Exception {
        MvcResult guest = mockMvc.perform(post("/api/auth/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"路人乙"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String guestToken = dataOf(guest).path("token").asText();

        mockMvc.perform(post("/api/boards")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"游客的板"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/boards"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
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
}
