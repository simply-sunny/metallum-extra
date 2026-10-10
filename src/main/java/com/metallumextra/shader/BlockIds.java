package com.metallumextra.shader;

import com.metallumextra.MetallumExtra;
import com.metallumextra.shader.pack.BlockMappings;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ids a shader pack gives blocks (see {@link BlockMappings}) as the chunk mesh carries them.
 * <p>
 * A pack has hundreds of ids, up to tens of thousands in value, and a vertex of the mesh has nine spare bits for it. So each distinct id is given a small
 * number, its <em>palette index</em> (1 up to {@value #CAPACITY}; 0 is "no id"), which is what the mesh keeps; and the table that turns the index back into the
 * id goes to the terrain shader with the other uniforms ({@code MxBlockIds}), which writes it out as {@code mc_Entity.x}. Chunks are made again when the
 * mapping changes, since their vertices carry the old numbers.
 * <p>
 * Meshing runs on several threads, so everything here is safe to ask from any of them.
 */
public final class BlockIds {
    /** Palette indices that fit the mesh: the color's alpha byte and one more bit. */
    public static final int CAPACITY = 511;

    private record State(BlockMappings mappings, Map<String, List<BlockMappings.Rule>> byBlock, int[] table, Map<Integer, Integer> indexOfId,
                         Map<BlockState, Integer> cache) {
    }

    private static volatile @Nullable State state;

    private BlockIds() {
    }

    /**
     * Makes these mappings the ones in use.
     *
     * @return whether that changed anything, so that chunks have to be made again
     */
    public static synchronized boolean use(final BlockMappings mappings) {
        State old = state;
        State made = mappings.isEmpty() ? null : build(mappings);
        boolean same = old == null ? made == null : made != null && java.util.Arrays.equals(old.table, made.table) && old.mappings.rules().equals(made.mappings.rules());
        if (same) return false;
        state = made;
        return true;
    }

    private static State build(final BlockMappings mappings) {
        int[] ids = mappings.ids();
        if (ids.length > CAPACITY) {
            MetallumExtra.LOGGER.warn("[Metallum Extra] The pack gives blocks {} different ids; only the first {} can be told apart", ids.length, CAPACITY);
        }
        int count = Math.min(ids.length, CAPACITY);
        int[] table = new int[CAPACITY + 1];
        Map<Integer, Integer> indexOfId = new HashMap<>();
        for (int i = 0; i < count; i++) {
            table[i + 1] = ids[i];
            indexOfId.put(ids[i], i + 1);
        }
        Map<String, List<BlockMappings.Rule>> byBlock = new HashMap<>();
        for (BlockMappings.Rule rule : mappings.rules()) {
            byBlock.computeIfAbsent(rule.namespace() + ":" + rule.name(), k -> new ArrayList<>()).add(rule);
        }
        return new State(mappings, byBlock, table, indexOfId, new ConcurrentHashMap<>());
    }

    /** Whether the pack in use gives blocks ids. */
    public static boolean active() {
        return state != null;
    }

    /** The palette index of the block state (0 when it has no id), for the mesh. */
    public static int indexOf(final BlockState block) {
        State current = state;
        if (current == null) return 0;
        return current.cache.computeIfAbsent(block, s -> resolve(current, s));
    }

    private static int resolve(final State current, final BlockState block) {
        List<BlockMappings.Rule> rules = current.byBlock.get(BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString());
        if (rules == null) return 0;
        int found = 0;
        for (BlockMappings.Rule rule : rules) {
            if (matches(rule, block)) found = current.indexOfId.getOrDefault(rule.id(), 0);
        }
        return found;
    }

    private static boolean matches(final BlockMappings.Rule rule, final BlockState block) {
        for (Map.Entry<String, String> filter : rule.properties().entrySet()) {
            Property<?> property = block.getBlock().getStateDefinition().getProperty(filter.getKey());
            // A rule for a property the block does not have (a name from an older game) names nothing.
            if (property == null || !valueName(block, property).equals(filter.getValue())) return false;
        }
        return true;
    }

    private static <T extends Comparable<T>> String valueName(final BlockState block, final Property<T> property) {
        return property.getName(block.getValue(property));
    }

    /** The table from palette index to id, as the terrain shader reads it: {@value #CAPACITY} plus the empty entry, 0 for an index nothing uses. */
    public static double[] table() {
        State current = state;
        double[] values = new double[CAPACITY + 1];
        if (current != null) for (int i = 0; i < values.length; i++) values[i] = current.table[i];
        return values;
    }
}
