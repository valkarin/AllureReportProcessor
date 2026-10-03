package dev.allureprocessor;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Changes a test result before it is written to its bucket folder.
 * This is the extension point for adding data Allure will render:
 * labels, links, parameters, descriptionHtml, attachments, statusDetails.
 * See {@link AllureJson} for helpers.
 */
@FunctionalInterface
public interface ResultModifier {

    void modify(ObjectNode result, ModifierContext context);
}
