package dev.allureprocessor.modifiers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.allureprocessor.AllureJson;
import dev.allureprocessor.ModifierContext;
import dev.allureprocessor.ResultModifier;

/**
 * Removes links with the same name and url as an earlier link, keeping the first.
 * Runs in the {@link Phase#LATE} phase so it also catches duplicates created by other modifiers.
 */
public final class DedupeLinksModifier implements ResultModifier {

    @Override
    public Phase phase() {
        return Phase.LATE;
    }

    @Override
    public void modify(ObjectNode result, ModifierContext context) {
        AllureJson.dedupeLinks(result);
    }
}
