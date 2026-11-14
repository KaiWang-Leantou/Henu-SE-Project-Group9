package com.henu.whiteboard.board;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 白板文档快照，持有 Jackson ObjectNode，并负责按 docs/01 的结构校验和原子应用命令。
 */
public class BoardDoc {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_FLAT_POINTS = 8000; // 4000 个坐标点，扁平数组长度为 2 倍

    private ObjectNode root;

    private BoardDoc(ObjectNode root) {
        this.root = root;
    }

    /** 新建空文档；默认页固定为 p_1，便于前后端联调与演示。 */
    public static BoardDoc create(String boardId) {
        requireBoardId(boardId);
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("boardId", boardId);
        root.put("seq", 0L);
        ArrayNode pages = root.putArray("pages");
        ObjectNode page = pages.addObject();
        page.put("id", "p_1");
        page.put("name", "第 1 页");
        page.putNull("roundId");
        page.putArray("elements");
        return new BoardDoc(root);
    }

    /** 从数据库快照恢复；boardId 以请求参数为准，避免脏数据串板。 */
    public static BoardDoc fromJson(String json, String boardId) {
        requireBoardId(boardId);
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("文档 JSON 为空");
        }
        try {
            JsonNode parsed = JSON.readTree(json);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException("文档 JSON 必须是对象");
            }
            ObjectNode root = (ObjectNode) parsed;
            JsonNode pages = root.get("pages");
            if (pages == null || !pages.isArray() || pages.isEmpty()) {
                throw new IllegalArgumentException("文档必须至少包含一页");
            }
            if (root.has("schemaVersion") && root.get("schemaVersion").asInt() != SCHEMA_VERSION) {
                throw new IllegalArgumentException("不支持的 schemaVersion");
            }
            root.put("schemaVersion", SCHEMA_VERSION);
            root.put("boardId", boardId);
            if (!root.has("seq") || !root.get("seq").isIntegralNumber()) {
                root.put("seq", 0L);
            }
            return new BoardDoc(root);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("文档 JSON 解析失败", e);
        }
    }

    /**
     * 原子应用一条 board 命令。先在副本上校验/修改，全部成功后才替换根节点，
     * 因此校验失败不会留下半更新状态。
     */
    public synchronized void apply(String cmdType, JsonNode cmd) {
        applyChecked(cmdType, cmd, null);
    }

    /** 应用命令并同步写入 seq，保证文档状态与 seq 在同一个原子步骤内可见。 */
    public synchronized void apply(String cmdType, JsonNode cmd, long newSeq) {
        applyChecked(cmdType, cmd, newSeq);
    }

    private void applyChecked(String cmdType, JsonNode cmd, Long newSeq) {
        if (cmdType == null || cmdType.isBlank()) {
            throw new IllegalArgumentException("命令类型不能为空");
        }
        if (cmd == null || !cmd.isObject()) {
            throw new IllegalArgumentException("cmd 必须是对象");
        }
        ObjectNode working = root.deepCopy();
        applyTo(working, cmdType, cmd);
        if (newSeq != null) {
            working.put("seq", newSeq);
        }
        this.root = working;
    }

    /** 只校验不提交，供网络层在进入串行执行器前做字段预检。 */
    public synchronized void validate(String cmdType, JsonNode cmd) {
        if (cmdType == null || cmdType.isBlank()) {
            throw new IllegalArgumentException("命令类型不能为空");
        }
        if (cmd == null || !cmd.isObject()) {
            throw new IllegalArgumentException("cmd 必须是对象");
        }
        applyTo(root.deepCopy(), cmdType, cmd);
    }

    public synchronized long seq() {
        return root.path("seq").asLong(0L);
    }

    public synchronized void setSeq(long seq) {
        root.put("seq", seq);
    }

    /**
     * 用另一份快照整体覆盖当前文档（版本回滚用）；boardId 以本实例为准，seq 写成 newSeq。
     * 与 apply 共用同一把锁，房间内不会看到半覆盖状态。
     */
    public synchronized void replaceWith(String json, long newSeq) {
        BoardDoc replacement = fromJson(json, boardId());
        replacement.setSeq(newSeq);
        this.root = replacement.root;
    }

    public synchronized String boardId() {
        return root.path("boardId").asText();
    }

    public synchronized int pageCount() {
        return root.withArray("pages").size();
    }

    public synchronized int elementCount(String pageId) {
        ObjectNode page = findPage(root, pageId, false);
        return page == null ? 0 : page.withArray("elements").size();
    }

    public synchronized boolean hasPage(String pageId) {
        return findPage(root, pageId, false) != null;
    }

    public synchronized String firstPageId() {
        ArrayNode pages = root.withArray("pages");
        return pages.isEmpty() ? null : pages.get(0).path("id").asText(null);
    }

    /** 查询元素作者；元素或页面不存在时返回 null。 */
    public synchronized String authorOfElement(String pageId, String elementId) {
        JsonNode element = findElement(root, pageId, elementId);
        return element == null ? null : element.path("author").asText(null);
    }

    public synchronized String kindOfElement(String pageId, String elementId) {
        JsonNode element = findElement(root, pageId, elementId);
        return element == null ? null : element.path("kind").asText(null);
    }

    /** 下发给客户端的完整文档副本，防止外部意外修改内部状态。 */
    public synchronized ObjectNode toNodeCopy() {
        return root.deepCopy();
    }

    /** 同一把锁内取出文档副本与 seq，供 join 的 init 使用。 */
    public synchronized NodeSnapshot nodeSnapshot() {
        return new NodeSnapshot(root.deepCopy(), seq());
    }

    public synchronized String toJson() {
        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("文档序列化失败", e);
        }
    }

    /** 同一把锁内同时取出 JSON 与 seq，保证快照字段一致。 */
    public synchronized Snapshot snapshot() {
        return new Snapshot(toJson(), seq());
    }

    private void applyTo(ObjectNode work, String cmdType, JsonNode cmd) {
        switch (cmdType) {
            case "stroke.add" -> addStroke(work, cmd);
            case "stroke.erase" -> eraseStrokes(work, cmd);
            case "object.add" -> addObject(work, cmd);
            case "object.patch" -> patchObject(work, cmd);
            case "object.remove" -> removeObjects(work, cmd);
            case "page.add" -> addPage(work, cmd);
            case "page.rename" -> renamePage(work, cmd);
            case "page.clear" -> clearPage(work, cmd);
            case "board.reset" -> resetBoard(work);
            default -> throw new IllegalArgumentException("未知命令: " + cmdType);
        }
    }

    private void addStroke(ObjectNode work, JsonNode cmd) {
        String pageId = requireText(cmd, "pageId", 32);
        ObjectNode page = requirePage(work, pageId);
        ObjectNode stroke = validateElement(requireObject(cmd.get("stroke"), "stroke"));
        if (!"stroke".equals(stroke.path("kind").asText())) {
            throw new IllegalArgumentException("stroke.kind 必须为 stroke");
        }
        if (findElementAnywhere(work, stroke.path("id").asText()) != null) {
            throw new IllegalArgumentException("元素 id 已存在");
        }
        ((ArrayNode) page.get("elements")).add(stroke);
    }

    private void eraseStrokes(ObjectNode work, JsonNode cmd) {
        String pageId = requireText(cmd, "pageId", 32);
        ObjectNode page = requirePage(work, pageId);
        Set<String> ids = requireIds(cmd, "ids");
        ArrayNode elements = (ArrayNode) page.get("elements");
        for (int i = elements.size() - 1; i >= 0; i--) {
            JsonNode element = elements.get(i);
            if ("stroke".equals(element.path("kind").asText())
                    && ids.contains(element.path("id").asText())) {
                elements.remove(i);
            }
        }
    }

    private void addObject(ObjectNode work, JsonNode cmd) {
        String pageId = requireText(cmd, "pageId", 32);
        ObjectNode page = requirePage(work, pageId);
        ObjectNode object = validateElement(requireObject(cmd.get("object"), "object"));
        if ("stroke".equals(object.path("kind").asText())) {
            throw new IllegalArgumentException("object.add 不接受 stroke");
        }
        if (findElementAnywhere(work, object.path("id").asText()) != null) {
            throw new IllegalArgumentException("元素 id 已存在");
        }
        ((ArrayNode) page.get("elements")).add(object);
    }

    private void patchObject(ObjectNode work, JsonNode cmd) {
        String pageId = requireText(cmd, "pageId", 32);
        String elementId = requireText(cmd, "id", 32);
        ObjectNode patch = requireObject(cmd.get("patch"), "patch");
        ObjectNode page = requirePage(work, pageId);
        ArrayNode elements = (ArrayNode) page.get("elements");
        int index = findElementIndex(elements, elementId);
        if (index < 0) {
            throw new IllegalArgumentException("元素不存在: " + elementId);
        }
        ObjectNode current = (ObjectNode) elements.get(index);
        if ("stroke".equals(current.path("kind").asText())) {
            throw new IllegalArgumentException("笔划不可修改");
        }
        rejectImmutableChange(current, patch, "id");
        rejectImmutableChange(current, patch, "kind");
        rejectImmutableChange(current, patch, "author");
        rejectImmutableChange(current, patch, "createdAt");

        ObjectNode merged = current.deepCopy();
        Iterator<String> fields = patch.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            merged.set(field, patch.get(field));
        }
        validateElement(merged);
        elements.set(index, merged);
    }

    private void removeObjects(ObjectNode work, JsonNode cmd) {
        String pageId = requireText(cmd, "pageId", 32);
        ObjectNode page = requirePage(work, pageId);
        Set<String> ids = requireIds(cmd, "ids");
        ArrayNode elements = (ArrayNode) page.get("elements");
        for (int i = elements.size() - 1; i >= 0; i--) {
            JsonNode element = elements.get(i);
            if (!"stroke".equals(element.path("kind").asText())
                    && ids.contains(element.path("id").asText())) {
                elements.remove(i);
            }
        }
    }

    private void addPage(ObjectNode work, JsonNode cmd) {
        ObjectNode page = validatePage(requireObject(cmd.get("page"), "page"));
        ArrayNode pages = (ArrayNode) work.get("pages");
        if (findPage(work, page.path("id").asText(), false) != null) {
            throw new IllegalArgumentException("页面 id 已存在");
        }
        pages.add(page);
    }

    private void renamePage(ObjectNode work, JsonNode cmd) {
        String pageId = requireText(cmd, "pageId", 32);
        String name = requireText(cmd, "name", 50);
        requirePage(work, pageId).put("name", name);
    }

    private void clearPage(ObjectNode work, JsonNode cmd) {
        String pageId = requireText(cmd, "pageId", 32);
        requirePage(work, pageId).putArray("elements");
    }

    private void resetBoard(ObjectNode work) {
        ArrayNode pages = (ArrayNode) work.get("pages");
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("文档没有页面");
        }
        JsonNode first = pages.get(0);
        ObjectNode kept = JSON.createObjectNode();
        kept.put("id", first.path("id").asText("p_1"));
        kept.put("name", first.path("name").asText("第 1 页"));
        kept.putNull("roundId");
        kept.putArray("elements");
        pages.removeAll();
        pages.add(kept);
    }

    private ObjectNode validatePage(ObjectNode page) {
        requireText(page, "id", 32);
        requireText(page, "name", 50);
        JsonNode roundId = page.get("roundId");
        if (roundId != null && !roundId.isNull()) {
            if (!roundId.isTextual() || roundId.asText().length() > 32) {
                throw new IllegalArgumentException("roundId 非法");
            }
        } else {
            page.putNull("roundId");
        }
        JsonNode elements = page.get("elements");
        if (elements == null || !elements.isArray()) {
            throw new IllegalArgumentException("page.elements 必须是数组");
        }
        for (JsonNode element : elements) {
            validateElement(element);
        }
        return page.deepCopy();
    }

    /**
     * 校验元素公共字段和 kind 专有字段，返回深拷贝以避免调用方后续修改。
     */
    private ObjectNode validateElement(JsonNode node) {
        ObjectNode element = requireObject(node, "element").deepCopy();
        String kind = requireText(element, "kind", 16);
        requireText(element, "id", 32);
        requireText(element, "author", 32);
        requireLong(element, "createdAt");
        switch (kind) {
            case "stroke" -> validateStroke(element);
            case "rect", "ellipse", "arrow", "line" -> validateShape(element);
            case "text" -> validateText(element);
            default -> throw new IllegalArgumentException("未知元素类型: " + kind);
        }
        return element;
    }

    private void validateStroke(ObjectNode element) {
        String tool = requireText(element, "tool", 8);
        if (!"pen".equals(tool)) {
            throw new IllegalArgumentException("stroke.tool 目前只能是 pen");
        }
        requireColor(element, "color");
        requireNumber(element, "width", 1, 64);
        JsonNode points = element.get("points");
        if (points == null || !points.isArray()) {
            throw new IllegalArgumentException("stroke.points 必须是扁平数组");
        }
        int size = points.size();
        if (size < 2 || size % 2 != 0 || size > MAX_FLAT_POINTS) {
            throw new IllegalArgumentException("stroke.points 长度非法");
        }
        for (JsonNode point : points) {
            if (!point.isNumber() || !Double.isFinite(point.asDouble())) {
                throw new IllegalArgumentException("stroke.points 含非法坐标");
            }
        }
    }

    private void validateShape(ObjectNode element) {
        requireNumber(element, "x", -Double.MAX_VALUE, Double.MAX_VALUE);
        requireNumber(element, "y", -Double.MAX_VALUE, Double.MAX_VALUE);
        requireNumber(element, "w", -Double.MAX_VALUE, Double.MAX_VALUE);
        requireNumber(element, "h", -Double.MAX_VALUE, Double.MAX_VALUE);
        requireColor(element, "stroke");
        requireNumber(element, "strokeWidth", 0.1, 64);
        JsonNode fill = element.get("fill");
        if (fill != null && !fill.isNull()) {
            requireColor(element, "fill");
        }
        if (element.has("rotation")) {
            requireFiniteNumber(element, "rotation");
        }
    }

    private void validateText(ObjectNode element) {
        requireNumber(element, "x", -Double.MAX_VALUE, Double.MAX_VALUE);
        requireNumber(element, "y", -Double.MAX_VALUE, Double.MAX_VALUE);
        JsonNode text = element.get("text");
        if (text == null || !text.isTextual() || text.asText().length() > 500) {
            throw new IllegalArgumentException("text.text 非法");
        }
        requireNumber(element, "fontSize", 16, 96);
        requireColor(element, "color");
    }

    private ObjectNode requirePage(ObjectNode work, String pageId) {
        ObjectNode page = findPage(work, pageId, false);
        if (page == null) {
            throw new IllegalArgumentException("页面不存在: " + pageId);
        }
        return page;
    }

    private ObjectNode findPage(ObjectNode work, String pageId, boolean required) {
        if (pageId == null) {
            return null;
        }
        for (JsonNode page : work.withArray("pages")) {
            if (page.isObject() && pageId.equals(page.path("id").asText())) {
                return (ObjectNode) page;
            }
        }
        if (required) {
            throw new IllegalArgumentException("页面不存在: " + pageId);
        }
        return null;
    }

    private JsonNode findElement(ObjectNode work, String pageId, String elementId) {
        ObjectNode page = findPage(work, pageId, false);
        if (page == null || !page.get("elements").isArray()) {
            return null;
        }
        for (JsonNode element : page.get("elements")) {
            if (elementId != null && elementId.equals(element.path("id").asText())) {
                return element;
            }
        }
        return null;
    }

    private JsonNode findElementAnywhere(ObjectNode work, String elementId) {
        if (elementId == null) {
            return null;
        }
        for (JsonNode page : work.withArray("pages")) {
            JsonNode elements = page.get("elements");
            if (elements == null || !elements.isArray()) {
                continue;
            }
            for (JsonNode element : elements) {
                if (elementId.equals(element.path("id").asText())) {
                    return element;
                }
            }
        }
        return null;
    }

    private int findElementIndex(ArrayNode elements, String elementId) {
        for (int i = 0; i < elements.size(); i++) {
            if (elementId.equals(elements.get(i).path("id").asText())) {
                return i;
            }
        }
        return -1;
    }

    private void rejectImmutableChange(ObjectNode current, ObjectNode patch, String field) {
        if (patch.has(field) && !Objects.equals(current.get(field), patch.get(field))) {
            throw new IllegalArgumentException("不允许修改元素字段: " + field);
        }
    }

    private Set<String> requireIds(JsonNode cmd, String field) {
        JsonNode ids = cmd.get(field);
        if (ids == null || !ids.isArray() || ids.isEmpty()) {
            throw new IllegalArgumentException(field + " 必须是非空数组");
        }
        Set<String> result = new HashSet<>();
        for (JsonNode id : ids) {
            if (!id.isTextual() || id.asText().isBlank() || id.asText().length() > 32) {
                throw new IllegalArgumentException(field + " 含非法 id");
            }
            result.add(id.asText());
        }
        return result;
    }

    private static ObjectNode requireObject(JsonNode node, String what) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(what + " 必须是对象");
        }
        return (ObjectNode) node;
    }

    private static String requireText(JsonNode obj, String field, int maxLength) {
        JsonNode node = obj.get(field);
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException(field + " 必须是字符串");
        }
        String value = node.asText();
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " 长度非法");
        }
        return value;
    }

    private static void requireLong(JsonNode obj, String field) {
        JsonNode node = obj.get(field);
        if (node == null || !node.isIntegralNumber() || node.asLong() < 0) {
            throw new IllegalArgumentException(field + " 必须是非负整数");
        }
    }

    private static void requireFiniteNumber(JsonNode obj, String field) {
        JsonNode node = obj.get(field);
        if (node == null || !node.isNumber() || !Double.isFinite(node.asDouble())) {
            throw new IllegalArgumentException(field + " 必须是有限数值");
        }
    }

    private static double requireNumber(JsonNode obj, String field, double min, double max) {
        JsonNode node = obj.get(field);
        if (node == null || !node.isNumber() || !Double.isFinite(node.asDouble())) {
            throw new IllegalArgumentException(field + " 必须是有限数值");
        }
        double value = node.asDouble();
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " 超出范围");
        }
        return value;
    }

    private static void requireColor(JsonNode obj, String field) {
        JsonNode node = obj.get(field);
        if (node == null || !node.isTextual() || !COLOR.matcher(node.asText()).matches()) {
            throw new IllegalArgumentException(field + " 必须是 #RRGGBB");
        }
    }

    private static void requireBoardId(String boardId) {
        if (boardId == null || boardId.isBlank() || boardId.length() > 32) {
            throw new IllegalArgumentException("boardId 非法");
        }
    }

    /** 文档快照，JSON 与 seq 在同一把锁内取到。 */
    public record Snapshot(String json, long seq) {
    }

    /** 文档节点与 seq 的原子快照，供 init 下发。 */
    public record NodeSnapshot(ObjectNode doc, long seq) {
    }
}
