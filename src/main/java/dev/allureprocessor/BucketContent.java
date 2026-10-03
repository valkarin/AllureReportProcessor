package dev.allureprocessor;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Set;

/**
 * Everything that goes into one output results folder.
 * The JSON nodes are copies, so modifying them never affects the loaded source folder.
 *
 * @param results           uuid to result JSON (includes every retry attempt of each test)
 * @param containers        uuid to container JSON, with children trimmed to this bucket
 * @param attachmentSources file names in the source folder referenced by the results and containers
 */
public record BucketContent(Map<String, ObjectNode> results,
                            Map<String, ObjectNode> containers,
                            Set<String> attachmentSources) {

    public boolean isEmpty() {
        return results.isEmpty();
    }
}
