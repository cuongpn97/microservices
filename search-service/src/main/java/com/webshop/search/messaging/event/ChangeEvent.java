package com.webshop.search.messaging.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webshop.search.exception.MalformedChangeEventException;

/**
 * Một bản ghi thay đổi của Debezium (JsonConverter, schemas.enable=false):
 * <pre>
 * { "before": {...} | null, "after": {...} | null, "op": "c|u|d|r", "ts_ms": ..., "source": { "lsn": ..., ... } }
 * </pre>
 * <ul>
 *   <li>{@code r} = read: dòng đọc từ snapshot ban đầu (chưa có thay đổi nào, chỉ là trạng thái hiện tại).</li>
 *   <li>{@code c}/{@code u}: {@code after} là trạng thái MỚI đầy đủ của dòng.</li>
 *   <li>{@code d}: chỉ có {@code before}; với REPLICA IDENTITY mặc định, {@code before} chỉ chứa khóa chính.</li>
 * </ul>
 * Sau một {@code d}, Debezium gửi thêm <b>tombstone</b> (value = null) để log compaction xóa hẳn key đó.
 */
public record ChangeEvent(Operation op, JsonNode before, JsonNode after) {

    public enum Operation {
        CREATE, UPDATE, DELETE, SNAPSHOT_READ;

        static Operation of(String code) {
            return switch (code) {
                case "c" -> CREATE;
                case "u" -> UPDATE;
                case "d" -> DELETE;
                case "r" -> SNAPSHOT_READ;
                default -> throw new MalformedChangeEventException("Unknown Debezium op: " + code);
            };
        }
    }

    /** @return null nếu là tombstone (không có gì để làm). */
    public static ChangeEvent parse(ObjectMapper mapper, String value) {
        if (value == null) {
            return null;
        }
        JsonNode root;
        try {
            root = mapper.readTree(value);
        } catch (Exception e) {
            throw new MalformedChangeEventException("Invalid JSON", e);
        }
        JsonNode op = root.get("op");
        if (op == null || !op.isTextual()) {
            throw new MalformedChangeEventException("Missing op");
        }
        ChangeEvent event = new ChangeEvent(Operation.of(op.asText()), nullIfMissing(root.get("before")),
                nullIfMissing(root.get("after")));
        if (event.op() == Operation.DELETE ? event.before() == null : event.after() == null) {
            throw new MalformedChangeEventException("Missing row image for op " + event.op());
        }
        return event;
    }

    /** Ảnh dòng cần đọc: {@code before} cho delete, {@code after} cho phần còn lại. */
    public JsonNode row() {
        return op == Operation.DELETE ? before : after;
    }

    public String requiredText(String field) {
        JsonNode node = row().get(field);
        if (node == null || node.isNull()) {
            throw new MalformedChangeEventException("Missing field " + field);
        }
        return node.asText();
    }

    public long requiredLong(String field) {
        JsonNode node = row().get(field);
        if (node == null || !node.canConvertToLong()) {
            throw new MalformedChangeEventException("Missing/invalid numeric field " + field);
        }
        return node.asLong();
    }

    /**
     * Cột JSONB. Debezium (PostgreSQL connector) gửi jsonb dưới dạng CHUỖI JSON (kiểu io.debezium.data.Json), không
     * phải object lồng → parse lại. Chấp nhận cả object để không phụ thuộc cấu hình converter.
     */
    public JsonNode requiredJson(ObjectMapper mapper, String field) {
        JsonNode node = row().get(field);
        if (node == null || node.isNull()) {
            throw new MalformedChangeEventException("Missing field " + field);
        }
        if (node.isObject()) {
            return node;
        }
        try {
            return mapper.readTree(node.asText());
        } catch (Exception e) {
            throw new MalformedChangeEventException("Invalid JSON in field " + field, e);
        }
    }

    public boolean requiredBoolean(String field) {
        JsonNode node = row().get(field);
        if (node == null || !node.isBoolean()) {
            throw new MalformedChangeEventException("Missing/invalid boolean field " + field);
        }
        return node.asBoolean();
    }

    private static JsonNode nullIfMissing(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }
}
