package org.districompute.app.config;

import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** JSON conversion for JSON columns, with the application's configured mapper. */
@Component
public class JsonSupport {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JsonMapper mapper;

    JsonSupport(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Serializes a value.
     *
     * @param value the value
     * @return JSON text, or {@code null} for {@code null}
     */
    public String write(Object value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    /**
     * Parses a JSON object.
     *
     * @param json JSON text, or {@code null}
     * @return the map (empty for {@code null})
     */
    public Map<String, Object> map(String json) {
        return json == null || json.isBlank() ? Map.of() : mapper.readValue(json, MAP);
    }

    /**
     * Parses JSON into a tree.
     *
     * @param json JSON text, or {@code null}
     * @return the tree, or {@code null}
     */
    public JsonNode tree(String json) {
        return json == null ? null : mapper.readTree(json);
    }

    /**
     * Parses JSON into a type.
     *
     * @param json JSON text
     * @param type target type
     * @param <T> type
     * @return the value
     */
    public <T> T read(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }

    /**
     * Converts a value to another type through JSON (for example a record to a map).
     *
     * @param value the value
     * @return the map
     */
    public Map<String, Object> toMap(Object value) {
        return mapper.convertValue(value, MAP);
    }

    public JsonMapper mapper() {
        return mapper;
    }
}
