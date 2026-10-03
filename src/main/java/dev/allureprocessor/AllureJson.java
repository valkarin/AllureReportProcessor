package dev.allureprocessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/** Small helpers for editing Allure result JSON without losing unknown fields. */
public final class AllureJson {

    private AllureJson() {
    }

    public static String status(ObjectNode result) {
        return result.path("status").asText("");
    }

    /** Returns the first value of a label, or null. */
    public static String label(ObjectNode result, String name) {
        for (JsonNode l : result.path("labels")) {
            if (name.equals(l.path("name").asText())) {
                return l.path("value").asText(null);
            }
        }
        return null;
    }

    public static void addLabel(ObjectNode result, String name, String value) {
        result.withArray("labels").addObject().put("name", name).put("value", value);
    }

    /** All values of a label, in file order. Empty if the label isn't present. */
    public static List<String> labels(ObjectNode result, String name) {
        List<String> values = new ArrayList<>();
        for (JsonNode l : result.path("labels")) {
            if (name.equals(l.path("name").asText())) {
                values.add(l.path("value").asText(""));
            }
        }
        return values;
    }

    /** Removes every label with this name. Returns how many were removed. */
    public static int removeLabel(ObjectNode result, String name) {
        return removeLabelsIf(result, (n, v) -> name.equals(n));
    }

    /** Removes labels with this exact name and value (e.g. one specific tag). Returns how many were removed. */
    public static int removeLabel(ObjectNode result, String name, String value) {
        return removeLabelsIf(result, (n, v) -> name.equals(n) && value.equals(v));
    }

    /** Removes every label matching the predicate (label name, label value). Returns how many were removed. */
    public static int removeLabelsIf(ObjectNode result, BiPredicate<String, String> predicate) {
        JsonNode labels = result.get("labels");
        if (!(labels instanceof ArrayNode array)) {
            return 0;
        }
        int removed = 0;
        for (int i = array.size() - 1; i >= 0; i--) {
            JsonNode l = array.get(i);
            if (predicate.test(l.path("name").asText(""), l.path("value").asText(""))) {
                array.remove(i);
                removed++;
            }
        }
        return removed;
    }

    /** Replaces every label with this name by a single one with the given value. */
    public static void setLabel(ObjectNode result, String name, String value) {
        removeLabel(result, name);
        addLabel(result, name, value);
    }

    /** type is usually "issue", "tms", or "link". */
    public static void addLink(ObjectNode result, String name, String url, String type) {
        result.withArray("links").addObject().put("name", name).put("url", url).put("type", type);
    }

    public static void addParameter(ObjectNode result, String name, String value) {
        result.withArray("parameters").addObject().put("name", name).put("value", value);
    }

    /** Appends HTML to the test's description block, keeping anything already there. */
    public static void appendDescriptionHtml(ObjectNode result, String html) {
        String existing = result.path("descriptionHtml").asText("");
        result.put("descriptionHtml", existing.isEmpty() ? html : existing + "\n" + html);
    }

    /** Adds an attachment entry pointing at a file returned by {@link ModifierContext#addAttachmentFile}. */
    public static void addAttachment(ObjectNode result, String name, String mimeType, String source) {
        result.withArray("attachments").addObject().put("name", name).put("source", source).put("type", mimeType);
    }
}
