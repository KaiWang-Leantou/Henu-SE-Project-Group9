package com.henu.whiteboard.board;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 文档命令应用测试：覆盖笔划增删、加页和非法命令。
 */
class BoardDocTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private BoardDoc doc;

    @BeforeEach
    void setUp() {
        doc = BoardDoc.create("b_test");
    }

    @Test
    void strokeAddIncreasesElementCount() throws Exception {
        doc.apply("stroke.add", strokeAdd("s_1"));

        assertEquals(1, doc.elementCount("p_1"));
    }

    @Test
    void strokeEraseDecreasesElementCount() throws Exception {
        doc.apply("stroke.add", strokeAdd("s_1"));
        JsonNode erase = objectMapper.readTree("""
                {"t":"stroke.erase","pageId":"p_1","ids":["s_1"]}
                """);

        doc.apply("stroke.erase", erase);

        assertEquals(0, doc.elementCount("p_1"));
    }

    @Test
    void pageAddIncreasesPageCount() throws Exception {
        JsonNode page = objectMapper.readTree("""
                {"t":"page.add","page":{"id":"p_2","name":"第 2 页","roundId":null,"elements":[]}}
                """);

        doc.apply("page.add", page);

        assertEquals(2, doc.pageCount());
    }

    @Test
    void unknownCommandIsRejected() throws Exception {
        JsonNode cmd = objectMapper.readTree("""
                {"t":"board.unknown"}
                """);

        assertThrows(IllegalArgumentException.class, () -> doc.apply("board.unknown", cmd));
    }

    @Test
    void malformedStrokeIsRejectedWithoutPartialMutation() throws Exception {
        JsonNode badStroke = objectMapper.readTree("""
                {"t":"stroke.add","pageId":"p_1","stroke":{
                  "kind":"stroke","id":"s_bad","author":"u_1","createdAt":1,
                  "tool":"pen","color":"#111111","width":4
                }}
                """);

        assertThrows(IllegalArgumentException.class, () -> doc.apply("stroke.add", badStroke));
        assertEquals(0, doc.elementCount("p_1"));
    }

    private JsonNode strokeAdd(String id) throws Exception {
        return objectMapper.readTree("""
                {"t":"stroke.add","pageId":"p_1","stroke":{
                  "kind":"stroke","id":"%s","author":"u_test","createdAt":1763000000123,
                  "tool":"pen","color":"#111111","width":4,
                  "points":[120.5,300.0,124.0,306.5,131.0,315.0]
                }}
                """.formatted(id));
    }
}
