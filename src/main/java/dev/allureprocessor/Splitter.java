package dev.allureprocessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Splits a results folder into buckets the way Allure would count them.
 *
 * <p>Retries: attempts of the same test share a {@code historyId}. The latest attempt
 * (highest {@code stop}) decides the bucket, and all attempts go into that bucket together,
 * so Allure still shows them on the test's Retries tab. A test that failed and then passed
 * on retry therefore appears only in the passed report.
 *
 * <p>Containers: a container is included in a bucket when any of its children is in that
 * bucket (resolved transitively, since containers can nest). A container shared across
 * buckets is copied into each one, with its children trimmed to what that bucket holds.
 */
public final class Splitter {

    private static final Comparator<ObjectNode> LATEST_LAST = Comparator
            .<ObjectNode>comparingLong(n -> n.path("stop").asLong(0))
            .thenComparingLong(n -> n.path("start").asLong(0))
            .thenComparing(n -> n.path("uuid").asText(""));

    private Splitter() {
    }

    public static Map<Bucket, BucketContent> split(ResultsFolder folder) {
        Map<Bucket, Map<String, ObjectNode>> resultsByBucket = new EnumMap<>(Bucket.class);
        for (Bucket b : Bucket.values()) {
            resultsByBucket.put(b, new TreeMap<>());
        }

        for (List<Map.Entry<String, ObjectNode>> attempts : groupByHistoryId(folder.results()).values()) {
            ObjectNode latest = attempts.stream().map(Map.Entry::getValue).max(LATEST_LAST).orElseThrow();
            Bucket bucket = Bucket.ofStatus(latest.path("status").asText(null));
            for (Map.Entry<String, ObjectNode> attempt : attempts) {
                resultsByBucket.get(bucket).put(attempt.getKey(), attempt.getValue().deepCopy());
            }
        }

        Map<Bucket, BucketContent> out = new EnumMap<>(Bucket.class);
        for (Bucket b : Bucket.values()) {
            Map<String, ObjectNode> results = resultsByBucket.get(b);
            Map<String, ObjectNode> containers = containersFor(results.keySet(), folder.containers());
            Set<String> attachments = new TreeSet<>();
            results.values().forEach(r -> collectAttachmentSources(r, attachments));
            containers.values().forEach(c -> collectAttachmentSources(c, attachments));
            out.put(b, new BucketContent(results, containers, attachments));
        }
        return out;
    }

    /**
     * Combines buckets back into one, for a single unsplit report. Results keep whatever
     * modifiers did to them; containers are rebuilt from the source so shared ones are whole again.
     */
    public static BucketContent merge(Collection<BucketContent> buckets, ResultsFolder folder) {
        Map<String, ObjectNode> results = new TreeMap<>();
        Set<String> attachments = new TreeSet<>();
        for (BucketContent b : buckets) {
            results.putAll(b.results());
            attachments.addAll(b.attachmentSources());
        }
        return new BucketContent(results, containersFor(results.keySet(), folder.containers()), attachments);
    }

    private static Map<String, List<Map.Entry<String, ObjectNode>>> groupByHistoryId(Map<String, ObjectNode> results) {
        Map<String, List<Map.Entry<String, ObjectNode>>> groups = new TreeMap<>();
        for (Map.Entry<String, ObjectNode> e : results.entrySet()) {
            String historyId = e.getValue().path("historyId").asText("");
            // No historyId means Allure can't link attempts, so treat the result as its own test.
            String key = historyId.isEmpty() ? "uuid:" + e.getKey() : historyId;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }
        return groups;
    }

    private static Map<String, ObjectNode> containersFor(Set<String> resultUuids, Map<String, ObjectNode> allContainers) {
        Set<String> members = new HashSet<>(resultUuids);
        Set<String> included = new TreeSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, ObjectNode> e : allContainers.entrySet()) {
                if (included.contains(e.getKey())) {
                    continue;
                }
                for (JsonNode child : e.getValue().path("children")) {
                    if (members.contains(child.asText())) {
                        included.add(e.getKey());
                        members.add(e.getKey());
                        changed = true;
                        break;
                    }
                }
            }
        }

        Map<String, ObjectNode> out = new TreeMap<>();
        for (String uuid : included) {
            ObjectNode copy = allContainers.get(uuid).deepCopy();
            JsonNode children = copy.get("children");
            if (children instanceof ArrayNode array) {
                Iterator<JsonNode> it = array.elements();
                while (it.hasNext()) {
                    if (!members.contains(it.next().asText())) {
                        it.remove();
                    }
                }
            }
            out.put(uuid, copy);
        }
        return out;
    }

    /** Finds every attachment {@code source} anywhere in the tree (test level, steps, nested steps, fixtures). */
    static void collectAttachmentSources(JsonNode node, Set<String> sink) {
        if (node.isObject()) {
            JsonNode attachments = node.get("attachments");
            if (attachments != null && attachments.isArray()) {
                for (JsonNode a : attachments) {
                    String source = a.path("source").asText("");
                    if (!source.isEmpty()) {
                        sink.add(source);
                    }
                }
            }
            node.fields().forEachRemaining(f -> collectAttachmentSources(f.getValue(), sink));
        } else if (node.isArray()) {
            node.forEach(child -> collectAttachmentSources(child, sink));
        }
    }
}
