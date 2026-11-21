package com.bencodez.gravestonesplus.storage;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * Merge owner-prepared records into an interrupted startup's persisted document.
 * Composition and serialization operate on YAML nodes only: legacy Bukkit items
 * and grave records are never instantiated, cloned or encoded on the disk worker.
 */
final class StartupGraveDocument {
    private StartupGraveDocument() { }

    static String merge(String document, List<Map<String, Object>> active,
            List<Map<String, Object>> broken) {
        Yaml yaml = new Yaml();
        Node node = yaml.compose(new StringReader(document));
        MappingNode root;
        if (node == null) root = (MappingNode) yaml.represent(new HashMap<String, Object>());
        else if (node instanceof MappingNode mapping) root = mapping;
        else throw new IllegalArgumentException("Grave document must be a mapping");
        SequenceNode activeSection = section(yaml, root, "graves");
        SequenceNode brokenSection = section(yaml, root, "brokengraves");
        Set<String> replaced = new HashSet<>();
        List<Node> activeNodes = prepared(yaml, active, replaced);
        List<Node> brokenNodes = prepared(yaml, broken, replaced);
        replace(activeSection, activeNodes, replaced);
        replace(brokenSection, brokenNodes, replaced);
        StringWriter result = new StringWriter();
        yaml.serialize(root, result);
        return result.toString();
    }

    private static SequenceNode section(Yaml yaml, MappingNode root, String name) {
        Node found = null;
        for (NodeTuple tuple : root.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode key && name.equals(key.getValue())) {
                if (found != null) throw new IllegalArgumentException("Duplicate grave section");
                found = tuple.getValueNode();
            }
        }
        if (found instanceof SequenceNode sequence) return sequence;
        if (found != null) throw new IllegalArgumentException("Grave section must be a list");
        SequenceNode created = (SequenceNode) yaml.represent(new ArrayList<>());
        root.getValue().add(new NodeTuple(yaml.represent(name), created));
        return created;
    }

    private static List<Node> prepared(Yaml yaml, List<Map<String, Object>> records, Set<String> identities) {
        List<Node> nodes = new ArrayList<>();
        for (Map<String, Object> record : records) {
            Node node = yaml.represent(record);
            String identity = identity(node);
            if (identity == null || !identities.add(identity))
                throw new IllegalArgumentException("Invalid or conflicting prepared grave identity");
            nodes.add(node);
        }
        return nodes;
    }

    private static void replace(SequenceNode section, List<Node> additions, Set<String> identities) {
        section.getValue().removeIf(node -> identities.contains(identity(node)));
        section.getValue().addAll(additions);
    }

    private static String identity(Node node) {
        if (!(node instanceof MappingNode mapping)) return null;
        Map<String, String> fields = new HashMap<>();
        Set<String> wanted = Set.of("UUID", "Time", "World", "X", "Y", "Z");
        for (NodeTuple tuple : mapping.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode key && wanted.contains(key.getValue())) {
                if (!(tuple.getValueNode() instanceof ScalarNode value)
                        || fields.putIfAbsent(key.getValue(), value.getValue()) != null) return null;
            }
        }
        if (!fields.keySet().containsAll(wanted)) return null;
        try {
            return java.util.UUID.fromString(fields.get("UUID")) + "/"
                    + Long.parseLong(fields.get("Time")) + "/"
                    + java.util.UUID.fromString(fields.get("World")) + "/"
                    + Integer.parseInt(fields.get("X")) + "/"
                    + Integer.parseInt(fields.get("Y")) + "/"
                    + Integer.parseInt(fields.get("Z"));
        } catch (IllegalArgumentException invalid) { return null; }
    }
}
