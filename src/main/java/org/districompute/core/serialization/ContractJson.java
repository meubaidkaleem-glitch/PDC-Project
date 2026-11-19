package org.districompute.core.serialization;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;
import java.util.Optional;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.districompute.core.validation.ValidationException;
import org.districompute.core.validation.Violation;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON codec for the shared contract objects (task specifications, results, catalog entries and so on). Jackson
 * is an implementation detail: callers deal only in strings and bytes, so consumers on either Jackson major
 * version, or another language, can interoperate.
 *
 * <p>Format rules (integration contract, section 1): {@code lowerCamelCase} fields in declaration order, ISO-8601
 * instants, {@code null} fields omitted, unknown fields ignored. Readers still enforce
 * {@link SchemaVersion#requireCompatible schema compatibility} through the contract types' constructors.
 */
public final class ContractJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();
    private static final ObjectWriter PRETTY = MAPPER.writerWithDefaultPrettyPrinter();

    private ContractJson() {
    }

    /**
     * Serializes a contract object to compact JSON.
     *
     * @param value contract object
     * @return JSON text
     */
    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(Objects.requireNonNull(value, "value"));
        } catch (JacksonException e) {
            throw new DistriComputeException(ErrorCode.INTERNAL_ERROR,
                    "Cannot serialize " + value.getClass().getName() + ": " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Serializes a contract object to indented JSON, for documentation and examples.
     *
     * @param value contract object
     * @return JSON text
     */
    public static String toPrettyJson(Object value) {
        try {
            return PRETTY.writeValueAsString(Objects.requireNonNull(value, "value"));
        } catch (JacksonException e) {
            throw new DistriComputeException(ErrorCode.INTERNAL_ERROR,
                    "Cannot serialize " + value.getClass().getName() + ": " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Parses a contract object.
     *
     * @param json JSON text
     * @param type contract type
     * @param <T> contract type
     * @return the parsed object
     * @throws ValidationException if the document is malformed or violates the type's invariants
     * @throws DistriComputeException with {@link ErrorCode#UNSUPPORTED_SCHEMA_VERSION} for incompatible versions
     */
    public static <T> T fromJson(String json, Class<T> type) {
        Objects.requireNonNull(json, "json");
        try {
            return MAPPER.readValue(json, type);
        } catch (JacksonException e) {
            throw translate(e, type);
        }
    }

    /**
     * Parses a contract object from UTF-8 bytes.
     *
     * @param json UTF-8 JSON
     * @param type contract type
     * @param <T> contract type
     * @return the parsed object
     */
    public static <T> T fromJson(byte[] json, Class<T> type) {
        Objects.requireNonNull(json, "json");
        try {
            return MAPPER.readValue(json, type);
        } catch (JacksonException e) {
            throw translate(e, type);
        }
    }

    private static DistriComputeException translate(JacksonException e, Class<?> type) {
        Optional<DistriComputeException> domain = DistriComputeException.findIn(e);
        if (domain.isPresent()) {
            return domain.get();
        }
        String detail = rootMessage(e);
        return new ValidationException(new Violation("document", Violation.Codes.MALFORMED_DOCUMENT,
                "Cannot read " + type.getSimpleName() + ": " + detail));
    }

    private static String rootMessage(JacksonException e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof IllegalArgumentException || cause instanceof NullPointerException) {
            return cause.getMessage();
        }
        return e.getOriginalMessage();
    }
}
