package dev.allureprocessor.modifiers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.allureprocessor.AllureJson;
import dev.allureprocessor.Bucket;
import dev.allureprocessor.ModifierContext;
import dev.allureprocessor.ResultModifier;

/** Example modifier: adds a label to every result in one bucket, e.g. tag=needs-triage on failures. */
public final class AddLabelModifier implements ResultModifier {

    private final Bucket bucket;
    private final String name;
    private final String value;

    public AddLabelModifier(Bucket bucket, String name, String value) {
        this.bucket = bucket;
        this.name = name;
        this.value = value;
    }

    @Override
    public void modify(ObjectNode result, ModifierContext context) {
        if (context.bucket() == bucket) {
            AllureJson.addLabel(result, name, value);
        }
    }
}
