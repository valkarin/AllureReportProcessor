package dev.allureprocessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Writes one bucket into a fresh results folder that the allure CLI can generate from. */
public final class BucketWriter {

    /** Folder-level files copied into every bucket when present. */
    static final List<String> SHARED_FILES = List.of(
            "categories.json", "environment.properties", "environment.xml", "executor.json");

    private final ObjectMapper mapper;

    public BucketWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param historyFrom previous report folder for this bucket; its history/ subfolder is
     *                    copied in so trends continue across runs. May be null.
     * @return warnings, such as attachments that were referenced but missing on disk
     */
    public List<String> write(BucketContent content, Map<String, byte[]> newFiles,
                              ResultsFolder source, Path target, Path historyFrom) throws IOException {
        List<String> warnings = new ArrayList<>();
        deleteRecursively(target);
        Files.createDirectories(target);

        for (Map.Entry<String, ObjectNode> e : content.results().entrySet()) {
            mapper.writeValue(target.resolve(e.getKey() + ResultsFolder.RESULT_SUFFIX).toFile(), e.getValue());
        }
        for (Map.Entry<String, ObjectNode> e : content.containers().entrySet()) {
            mapper.writeValue(target.resolve(e.getKey() + ResultsFolder.CONTAINER_SUFFIX).toFile(), e.getValue());
        }
        for (String name : content.attachmentSources()) {
            Path from = source.dir().resolve(name);
            if (newFiles.containsKey(name)) {
                continue;
            }
            if (Files.isRegularFile(from)) {
                Files.copy(from, target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            } else {
                warnings.add("Attachment referenced but not found: " + name);
            }
        }
        for (Map.Entry<String, byte[]> e : newFiles.entrySet()) {
            Files.write(target.resolve(e.getKey()), e.getValue());
        }
        for (String name : SHARED_FILES) {
            Path from = source.dir().resolve(name);
            if (Files.isRegularFile(from)) {
                Files.copy(from, target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (historyFrom != null && Files.isDirectory(historyFrom.resolve("history"))) {
            copyRecursively(historyFrom.resolve("history"), target.resolve("history"));
        }
        return warnings;
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static void copyRecursively(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path dest = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
