package dev.allureprocessor.modifiers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.allureprocessor.AllureJson;
import dev.allureprocessor.ModifierContext;
import dev.allureprocessor.ResultModifier;

import java.util.List;

/**
 * Turns a label into a link: if the result has label {@code labelName}, each of its values
 * becomes a link built from {@code urlTemplate} (where "{value}" is replaced), and the label is removed.
 *
 * <p>Example: label {@code jira=PAY-123} with template {@code https://jira.example.com/browse/{value}}
 * becomes an issue link named PAY-123, and the jira label disappears from the report.
 */
public final class LabelToLinkModifier implements ResultModifier {

    private final String labelName;
    private final String urlTemplate;
    private final String linkType;

    /** @param linkType "issue", "tms", or "link"; Allure shows issue and tms links with their own icons. */
    public LabelToLinkModifier(String labelName, String urlTemplate, String linkType) {
        this.labelName = labelName;
        this.urlTemplate = urlTemplate;
        this.linkType = linkType;
    }

    @Override
    public void modify(ObjectNode result, ModifierContext context) {
        List<String> values = AllureJson.labels(result, labelName);
        if (values.isEmpty()) {
            return;
        }
        AllureJson.removeLabel(result, labelName);
        for (String value : values) {
            AllureJson.addLink(result, value, urlTemplate.replace("{value}", value), linkType);
        }
    }
}
