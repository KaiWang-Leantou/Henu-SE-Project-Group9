package com.henu.whiteboard.board;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.henu.whiteboard.persistence.BoardEntity;
import com.henu.whiteboard.persistence.BoardOpEntity;
import com.henu.whiteboard.persistence.BoardOpRepository;
import com.henu.whiteboard.persistence.BoardRepository;
import com.henu.whiteboard.persistence.BoardVersionEntity;
import com.henu.whiteboard.persistence.BoardVersionKind;
import com.henu.whiteboard.persistence.BoardVersionRepository;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 版本历史 REST 测试：手动存档 → 再改 → 回滚回到存档点、PRE_RESTORE 生成、AUTO 自动版本。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class VersionApiTest {

    private static final String PASSWORD = "Abc12345";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BoardRepository boardRepository;

    @Autowired
    private BoardOpRepository boardOpRepository;

    @Autowired
    private BoardVersionRepository boardVersionRepository;

    @Autowired
    private BoardService boardService;

    @Test
    void manualVersionAndRestoreRollBackDocument() throws Exception {
        String token = register("ver");
        String boardId = createBoard(token, "版本回滚");

        // 存档点：两笔
        writeDoc(boardId, 2, 2L);
        MvcResult created = mockMvc.perform(post("/api/boards/" + boardId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"两笔"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.kind").value("MANUAL"))
                .andExpect(jsonPath("$.data.label").value("两笔"))
                .andReturn();
        JsonNode manual = dataOf(created);
        String versionId = manual.path("id").asText();
        assertEquals(1, manual.path("versionNo").asInt(), "首个版本号应为 1");
        assertEquals(2L, manual.path("docSeq").asLong());
        assertFalse(versionId.isBlank());

        // 再改：三笔
        writeDoc(boardId, 3, 3L);

        mockMvc.perform(get("/api/boards/" + boardId + "/versions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(versionId))
                .andExpect(jsonPath("$.data.items[0].kind").value("MANUAL"));

        // 回滚
        MvcResult restored = mockMvc.perform(post("/api/boards/" + boardId + "/versions/"
                        + versionId + "/restore")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.versionId").value(versionId))
                .andReturn();
        JsonNode restoreData = dataOf(restored);
        assertEquals(1, restoreData.path("versionNo").asInt());
        assertEquals(4L, restoreData.path("seq").asLong(),
                "回滚 seq 应为 max(当前 3, 目标 2) + 1");

        // 文档回到存档点：两笔；board.doc_json / doc_seq 同步更新
        BoardEntity after = boardRepository.findById(boardId).orElseThrow();
        assertEquals(4L, after.getDocSeq());
        BoardDoc doc = BoardDoc.fromJson(after.getDocJson(), boardId);
        assertEquals(2, doc.elementCount("p_1"), "回滚后应回到存档时的两笔");

        // 历史只追加：MANUAL + PRE_RESTORE
        MvcResult list = mockMvc.perform(get("/api/boards/" + boardId + "/versions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andReturn();
        JsonNode items = dataOf(list).path("items");
        assertEquals(2, items.size());
        assertEquals("PRE_RESTORE", items.get(0).path("kind").asText(), "最新一条应是回滚前备份");
        assertEquals("MANUAL", items.get(1).path("kind").asText());
        assertEquals(2, items.get(0).path("versionNo").asInt());

        // 回滚必须落一条 board.restore op（可审计、可重放）
        List<BoardOpEntity> ops = boardOpRepository
                .findByBoardIdAndSeqGreaterThanOrderBySeqAsc(boardId, 0L);
        assertEquals(1, ops.size());
        assertEquals(4L, ops.get(0).getSeq());
        JsonNode restoreCmd = objectMapper.readTree(ops.get(0).getCmdJson());
        assertEquals("board.restore", restoreCmd.path("t").asText());
        assertEquals(versionId, restoreCmd.path("versionId").asText());
    }

    @Test
    void firstSnapshotCreatesAutoVersion() throws Exception {
        String token = register("auto");
        String boardId = createBoard(token, "自动版本");

        BoardDoc doc = BoardDoc.create(boardId);
        doc.apply("stroke.add", strokeCmd("s_auto"));
        doc.setSeq(1L);
        boardService.saveSnapshot(boardId, doc, 1L);

        BoardVersionEntity auto = null;
        long deadline = System.currentTimeMillis() + 8_000L;
        while (System.currentTimeMillis() < deadline) {
            auto = boardVersionRepository
                    .findFirstByBoardIdAndKindOrderByVersionNoDesc(boardId, BoardVersionKind.AUTO)
                    .orElse(null);
            if (auto != null) {
                break;
            }
            Thread.sleep(100L);
        }
        assertNotNull(auto, "快照落库后应自动生成 AUTO 版本");
        assertEquals(BoardVersionKind.AUTO, auto.getKind());
        assertEquals(1L, auto.getDocSeq());
        assertTrue(auto.getVersionNo() >= 1);
    }

    private void writeDoc(String boardId, int strokeCount, long seq) throws Exception {
        BoardEntity board = boardRepository.findById(boardId).orElseThrow();
        BoardDoc doc = BoardDoc.create(boardId);
        for (int i = 1; i <= strokeCount; i++) {
            doc.apply("stroke.add", strokeCmd("s_" + i));
        }
        doc.setSeq(seq);
        board.setDocJson(doc.toJson());
        board.setDocSeq(seq);
        boardRepository.save(board);
    }

    private JsonNode strokeCmd(String id) throws Exception {
        return objectMapper.readTree("""
                {"t":"stroke.add","pageId":"p_1","stroke":{
                  "kind":"stroke","id":"%s","author":"u_test","createdAt":1763000000123,
                  "tool":"pen","color":"#111111","width":4,
                  "points":[10,10,20,20,30,30]
                }}
                """.formatted(id));
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
