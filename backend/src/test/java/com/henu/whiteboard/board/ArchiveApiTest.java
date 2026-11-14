package com.henu.whiteboard.board;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.henu.whiteboard.persistence.BoardEntity;
import com.henu.whiteboard.persistence.BoardRepository;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 生命周期 REST 测试：归档 / 只读 403 / 回收站 / 恢复 / 复制为新板。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class ArchiveApiTest {

    private static final String PASSWORD = "Abc12345";
    private static final String PNG_DATA_URL =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BoardRepository boardRepository;

    @Test
    void archiveTrashRestoreAndDuplicate() throws Exception {
        String token = register("arch");
        String userId = dataOf(mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()).path("id").asText();
        String boardId = createBoard(token, "归档测试");
        writeOneStrokeDoc(boardId);

        // 归档：列表按 status=archived 可见，详情状态 archived
        mockMvc.perform(patch("/api/boards/" + boardId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"archived"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        mockMvc.perform(get("/api/boards/" + boardId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("archived"));

        MvcResult archivedList = mockMvc.perform(get("/api/boards")
                        .param("scope", "mine")
                        .param("status", "archived")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode archivedItems = dataOf(archivedList).path("items");
        assertEquals(boardId, findItem(archivedItems, boardId).path("id").asText());
        assertEquals("archived",
                findItem(archivedItems, boardId).path("status").asText());

        // 归档板只读：改名 / 缩略图 / 手动存档一律 403 FORBIDDEN
        mockMvc.perform(patch("/api/boards/" + boardId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"归档后改名"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(put("/api/boards/" + boardId + "/thumbnail")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("dataUrl", PNG_DATA_URL))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/boards/" + boardId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"归档后存档"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // 归档板可复制：新 id、owner=当前用户、ACTIVE，doc_json 完整复制且 boardId 重写
        MvcResult duplicate = mockMvc.perform(post("/api/boards/" + boardId + "/duplicate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("active"))
                .andExpect(jsonPath("$.data.role").value("owner"))
                .andReturn();
        String copyId = dataOf(duplicate).path("id").asText();
        assertNotEquals(boardId, copyId, "复制必须生成新 id");
        assertTrue(copyId.startsWith("b_"));

        mockMvc.perform(get("/api/boards/" + copyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.owner.id").value(userId))
                .andExpect(jsonPath("$.data.status").value("active"))
                .andExpect(jsonPath("$.data.role").value("owner"));

        BoardEntity copy = boardRepository.findById(copyId).orElseThrow();
        BoardDoc copyDoc = BoardDoc.fromJson(copy.getDocJson(), copyId);
        assertEquals(1, copyDoc.elementCount("p_1"), "复制应带上源文档内容");
        assertEquals(copyId, copyDoc.boardId(), "复制后文档 boardId 必须重写");

        // 软删除进回收站：默认列表看不到，status=trashed 能看到
        mockMvc.perform(delete("/api/boards/" + boardId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        MvcResult defaultList = mockMvc.perform(get("/api/boards")
                        .param("scope", "mine")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(findItemOrNull(dataOf(defaultList).path("items"), boardId) == null,
                "默认列表不应返回回收站中的板");
        assertEquals("trashed", dataOf(mockMvc.perform(get("/api/boards/" + boardId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()).path("status").asText());

        MvcResult trashedList = mockMvc.perform(get("/api/boards")
                        .param("scope", "mine")
                        .param("status", "trashed")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals("trashed",
                findItem(dataOf(trashedList).path("items"), boardId).path("status").asText());

        // 回收站恢复 → ACTIVE
        mockMvc.perform(post("/api/boards/" + boardId + "/trash/restore")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
        mockMvc.perform(get("/api/boards/" + boardId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("active"));

        // 非 owner 不能删除/恢复
        String otherToken = register("other");
        mockMvc.perform(delete("/api/boards/" + boardId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private void writeOneStrokeDoc(String boardId) throws Exception {
        BoardEntity board = boardRepository.findById(boardId).orElseThrow();
        BoardDoc doc = BoardDoc.create(boardId);
        JsonNode stroke = objectMapper.readTree("""
                {"t":"stroke.add","pageId":"p_1","stroke":{
                  "kind":"stroke","id":"s_1","author":"u_test","createdAt":1763000000123,
                  "tool":"pen","color":"#111111","width":4,
                  "points":[10,10,20,20]
                }}
                """);
        doc.apply("stroke.add", stroke);
        doc.setSeq(1L);
        board.setDocJson(doc.toJson());
        board.setDocSeq(1L);
        boardRepository.save(board);
    }

    private JsonNode findItem(JsonNode items, String boardId) {
        JsonNode item = findItemOrNull(items, boardId);
        assertTrue(item != null, "列表应包含白板 " + boardId);
        return item;
    }

    private JsonNode findItemOrNull(JsonNode items, String boardId) {
        for (JsonNode item : items) {
            if (boardId.equals(item.path("id").asText())) {
                return item;
            }
        }
        return null;
    }

    private String createBoard(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/boards")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(name)))
                .andExpect(status().isOk())
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
}
