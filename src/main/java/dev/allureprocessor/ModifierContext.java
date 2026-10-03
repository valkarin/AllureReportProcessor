package dev.allureprocessor;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** What a {@link ResultModifier} can see and do besides editing the result itself. */
public final class ModifierContext {

    private final Bucket bucket;
    private final ResultsFolder source;
    private final Map<String, byte[]> newFiles = new TreeMap<>();

    public ModifierContext(Bucket bucket, ResultsFolder source) {
        this.bucket = bucket;
        this.source = source;
    }

    /** The bucket the result is being written to. */
    public Bucket bucket() {
        return bucket;
    }

    /** The original, unmodified results folder (read only). */
    public ResultsFolder source() {
        return source;
    }

    /**
     * Registers a new attachment file to be written into the bucket folder and returns
     * its file name, to use as the {@code source} of an attachment entry.
     * The name is derived from the content, so the same content always gets the same name.
     */
    public String addAttachmentFile(byte[] content, String extension) {
        String name = UUID.nameUUIDFromBytes(content) + "-attachment" + (extension.startsWith(".") ? extension : "." + extension);
        newFiles.put(name, content);
        return name;
    }

    public String addAttachmentFile(String content, String extension) {
        return addAttachmentFile(content.getBytes(StandardCharsets.UTF_8), extension);
    }

    Map<String, byte[]> newFiles() {
        return Collections.unmodifiableMap(newFiles);
    }
}
