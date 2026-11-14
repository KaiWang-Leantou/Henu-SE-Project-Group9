package com.henu.whiteboard.auth;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证链路测试：注册→登录→me、重复用户名、错误密码、游客升级保持 id。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class AuthFlowTest {

    private static final String PASSWORD = "Abc12345";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void registerThenLoginThenMe() throws Exception {
        String username = uniqueUsername("reg");
        String token = register(username, "张三");
        assertTrue(token.length() > 20, "注册应返回 JWT");

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.user.username").value(username))
                .andExpect(jsonPath("$.data.user.guest").value(false))
                .andReturn();
        String loginToken = dataOf(login).path("token").asText();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + loginToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.nickname").value("张三"))
                .andExpect(jsonPath("$.data.guest").value(false));
    }

    @Test
    void duplicateUsernameReturns409() throws Exception {
        String username = uniqueUsername("dup");
        register(username, "甲");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","nickname":"甲2"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void wrongPasswordReturns401() throws Exception {
        String username = uniqueUsername("bad");
        register(username, "乙");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"Wrong12345"}
                                """.formatted(username)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void guestUpgradeKeepsUserId() throws Exception {
        MvcResult guest = mockMvc.perform(post("/api/auth/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"路人甲"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.guest").value(true))
                .andReturn();
        JsonNode guestData = dataOf(guest);
        String guestId = guestData.path("user").path("id").asText();
        String guestToken = guestData.path("token").asText();

        String newUsername = uniqueUsername("up");
        MvcResult upgrade = mockMvc.perform(post("/api/auth/upgrade")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","nickname":"李四"}
                                """.formatted(newUsername, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.id").value(guestId))
                .andExpect(jsonPath("$.data.user.guest").value(false))
                .andExpect(jsonPath("$.data.user.username").value(newUsername))
                .andReturn();

        String upgradedToken = dataOf(upgrade).path("token").asText();
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + upgradedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(guestId))
                .andExpect(jsonPath("$.data.username").value(newUsername))
                .andExpect(jsonPath("$.data.nickname").value("李四"))
                .andExpect(jsonPath("$.data.guest").value(false));
    }

    private String register(String username, String nickname) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","nickname":"%s"}
                                """.formatted(username, PASSWORD, nickname)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn();
        JsonNode data = dataOf(result);
        assertEquals(username, data.path("user").path("username").asText());
        assertFalse(data.path("user").path("guest").asBoolean(true));
        return data.path("token").asText();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("data");
    }

    private static String uniqueUsername(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
