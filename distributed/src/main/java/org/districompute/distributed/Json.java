package org.districompute.distributed;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON settings shared by the protocol, the HTTP API and the event stream. They match Core's contract rules:
 * {@code lowerCamelCase} fields in declaration order, ISO-8601 instants, {@code null} fields omitted, unknown
 * fields ignored, unknown enum values read as the type's declared default.
 */
public final class Json {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();
    private static final ObjectWriter PRETTY = MAPPER.writerWithDefaultPrettyPrinter();

    private Json() {
    }

    /**
     * Serializes a value to compact JSON (one line).
     *
     * @param value the value
     * @return UTF-8 JSON
     * @throws JacksonException if the value cannot be serialized
     */
    public static byte[] toBytes(Object value) {
        return MAPPER.writeValueAsBytes(Objects.requireNonNull(value, "value"));
    }

    /**
     * Serializes a value to compact JSON text.
     *
     * @param value the value
     * @return JSON text
     */
    public static String toJson(Object value) {
        return MAPPER.writeValueAsString(Objects.requireNonNull(value, "value"));
    }

    /**
     * Serializes a value to indented JSON, for documentation and the command line.
     *
     * @param value the value
     * @return JSON text
     */
    public static String toPrettyJson(Object value) {
        return PRETTY.writeValueAsString(Objects.requireNonNull(value, "value"));
    }

    /**
     * Parses JSON into a tree.
     *
     * @param json UTF-8 JSON
     * @return the tree
     * @throws JacksonException if the input is not valid JSON
     */
    public static JsonNode readTree(byte[] json) {
        return MAPPER.readTree(json);
    }

    /**
     * Converts a tree to a value.
     *
     * @param node the tree
     * @param type target type
     * @param <T> target type
     * @return the value
     * @throws JacksonException if the tree does not match the type
     */
    public static <T> T treeToValue(JsonNode node, Class<T> type) {
        return MAPPER.treeToValue(node, type);
    }

    /**
     * Parses JSON into a value.
     *
     * @param json UTF-8 JSON
     * @param type target type
     * @param <T> target type
     * @return the value
     * @throws JacksonException if the input does not match the type
     */
    public static <T> T fromJson(byte[] json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }

    /**
     * Parses JSON text into a value.
     *
     * @param json JSON text
     * @param type target type
     * @param <T> target type
     * @return the value
     */
    public static <T> T fromJson(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }
}
