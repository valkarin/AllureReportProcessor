package dev.allureprocessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * An allure-results folder loaded into memory as raw JSON trees.
 * Files are read as {@link ObjectNode} so every field is preserved when written back,
 * including fields added by newer adapters that this code does not know about.
 * The source folder itself is never modified.
 */
public final class ResultsFolder {

    public static final String RESULT_SUFFIX = "-result.json";
    public static final String CONTAINER_SUFFIX = "-container.json";

    private final Path dir;
    private final Map<String, ObjectNode> results;
    private final Map<String, ObjectNode> containers;
    private final List<String> warnings;

    private ResultsFolder(Path dir, Map<String, ObjectNode> results,
                          Map<String, ObjectNode> containers, List<String> warnings) {
        this.dir = dir;
        this.results = results;
        this.containers = containers;
        this.warnings = warnings;
    }

    public static ResultsFolder load(Path dir, ObjectMapper mapper) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IOException("Results folder does not exist: " + dir.toAbsolutePath());
        }
        // TreeMaps keyed by uuid keep iteration order stable, so output is deterministic.
        Map<String, ObjectNode> results = new TreeMap<>();
        Map<String, ObjectNode> containers = new TreeMap<>();
        List<String> warnings = new ArrayList<>();

        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (name.endsWith(RESULT_SUFFIX)) {
                read(file, mapper, warnings, RESULT_SUFFIX).ifPresent(n -> results.put(uuidOf(n, name, RESULT_SUFFIX), n));
            } else if (name.endsWith(CONTAINER_SUFFIX)) {
                read(file, mapper, warnings, CONTAINER_SUFFIX).ifPresent(n -> containers.put(uuidOf(n, name, CONTAINER_SUFFIX), n));
            }
        }
        return new ResultsFolder(dir, results, containers, warnings);
    }

    private static java.util.Optional<ObjectNode> read(Path file, ObjectMapper mapper, List<String> warnings, String suffix) {
        try {
            JsonNode node = mapper.readTree(file.toFile());
            if (node instanceof ObjectNode obj) {
                return java.util.Optional.of(obj);
            }
            warnings.add("Skipped " + file.getFileName() + ": not a JSON object");
        } catch (IOException e) {
            warnings.add("Skipped " + file.getFileName() + ": " + e.getMessage());
        }
        return java.util.Optional.empty();
    }

    private static String uuidOf(ObjectNode node, String fileName, String suffix) {
        String uuid = node.path("uuid").asText("");
        return uuid.isEmpty() ? fileName.substring(0, fileName.length() - suffix.length()) : uuid;
    }

    public Path dir() {
        return dir;
    }

    /** uuid to result JSON. Unmodifiable. */
    public Map<String, ObjectNode> results() {
        return Collections.unmodifiableMap(results);
    }

    /** uuid to container JSON. Unmodifiable. */
    public Map<String, ObjectNode> containers() {
        return Collections.unmodifiableMap(containers);
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }
}
