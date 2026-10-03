package dev.allureprocessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

    /** Replaces every label with this name by a single one with the given value. */
    public static void setLabel(ObjectNode result, String name, String value) {
        var labels = result.withArray("labels");
        for (int i = labels.size() - 1; i >= 0; i--) {
            if (name.equals(labels.get(i).path("name").asText())) {
                labels.remove(i);
            }
        }
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
