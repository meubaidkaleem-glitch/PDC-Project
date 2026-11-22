package org.districompute.core.partition;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * The part of the input a partition covers. In JSON the variant is identified by {@code "type"}: {@code RANGE},
 * {@code CHUNK} or {@code BLOCK} (integration contract, section 6).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RangeBounds.class, name = "RANGE"),
    @JsonSubTypes.Type(value = ChunkBounds.class, name = "CHUNK"),
    @JsonSubTypes.Type(value = BlockBounds.class, name = "BLOCK")
})
public sealed interface PartitionBounds permits RangeBounds, ChunkBounds, BlockBounds {

    /**
     * Number of input elements covered (integers, array elements or matrix elements).
     *
     * @return the element count
     */
    long elementCount();
}
