package com.metallumextra.shader.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The block ids of a pack's {@code block.properties}: {@code block.10005=grass short_grass fern} gives those blocks the id 10005, which a program reads as
 * {@code mc_Entity.x}. A block is written {@code name}, {@code namespace:name} or with a filter on its state, {@code farmland:moisture=7}
 * (several filters follow each other: {@code name:a=1:b=2}). A block state that several lines name has the id of the last of them in the file.
 * <p>
 * This holds the rules only; {@code BlockIds} (in the game's package) applies them to the game's block states.
 */
public final class BlockMappings {
    /** One block (and optionally some of its properties) and the id it has. */
    public record Rule(int id, String namespace, String name, Map<String, String> properties) {
    }

    private final List<Rule> rules;

    private BlockMappings(final List<Rule> rules) {
        this.rules = rules;
    }

    public static BlockMappings none() {
        return new BlockMappings(List.of());
    }

    /** @param properties the preprocessed {@code block.properties}, in file order; keys other than {@code block.<id>} are ignored here */
    public static BlockMappings parse(final Map<String, String> properties) {
        List<Rule> rules = new ArrayList<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (!entry.getKey().startsWith("block.")) continue;
            int id;
            try {
                id = Integer.parseInt(entry.getKey().substring("block.".length()));
            } catch (NumberFormatException e) {
                continue;
            }
            for (String spec : entry.getValue().trim().split("\\s+")) {
                if (spec.isEmpty()) continue;
                String[] parts = spec.split(":");
                int at = 0;
                String namespace = "minecraft";
                // "namespace:name", unless the second part is a property filter ("name:prop=value").
                if (parts.length > 1 && !parts[1].contains("=")) {
                    namespace = parts[0];
                    at = 1;
                }
                String name = parts[at++];
                Map<String, String> filter = new LinkedHashMap<>();
                for (; at < parts.length; at++) {
                    int equals = parts[at].indexOf('=');
                    if (equals > 0) filter.put(parts[at].substring(0, equals), parts[at].substring(equals + 1));
                }
                rules.add(new Rule(id, namespace, name, Map.copyOf(filter)));
            }
        }
        return new BlockMappings(List.copyOf(rules));
    }

    /** The rules, in file order. */
    public List<Rule> rules() {
        return rules;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** The distinct ids, in ascending order. */
    public int[] ids() {
        return rules.stream().mapToInt(Rule::id).distinct().sorted().toArray();
    }
}
