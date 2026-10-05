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

    /**
     * When this modifier runs relative to the others. Phases run in the order
     * {@link Phase#EARLY}, {@link Phase#NORMAL}, {@link Phase#LATE}; within a phase,
     * modifiers run in the order they were added.
     */
    default Phase phase() {
        return Phase.NORMAL;
    }

    enum Phase {
        /** Prepares data that other modifiers rely on. */
        EARLY,
        NORMAL,
        /** Cleans up after the others, so it needs to see what they changed. */
        LATE
    }
}
