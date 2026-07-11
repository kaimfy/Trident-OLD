package com.trident.pathing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.trident.utils.BlockPosSerializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistent map of the trees the macro has verified by actually felling them.
 *
 * Hypixel trees are static: fixed base positions, fixed shapes, respawning in
 * place. So every successful fell teaches us a real tree (its base plus the
 * spot the player stood to chop it — a known-reachable pathfinding goal), and
 * every log that refused to break teaches us permanent scenery (portal frames,
 * decorative structures). Both survive restarts via config/trident_trees.json,
 * keyed by island so Park and Galatea coordinates can't collide.
 */
public final class TreeRegistry {

    public static class KnownTree {
        public String island;
        public String type;
        public BlockPos base;
        public BlockPos stand;
    }

    private static class Data {
        List<KnownTree> trees = new ArrayList<>();
        Map<String, List<BlockPos>> unbreakable = new HashMap<>();
    }

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(BlockPos.class, new BlockPosSerializer())
            .setPrettyPrinting()
            .create();

    private static Data data = new Data();
    private static final Map<String, Set<BlockPos>> unbreakableCache = new HashMap<>();
    private static boolean loaded = false;

    private TreeRegistry() {}

    private static File file() {
        return FabricLoader.getInstance().getConfigDir().resolve("trident_trees.json").toFile();
    }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        File f = file();
        if (!f.exists()) return;
        try (Reader reader = Files.newBufferedReader(f.toPath())) {
            Data read = GSON.fromJson(reader, Data.class);
            if (read != null) {
                if (read.trees == null) read.trees = new ArrayList<>();
                if (read.unbreakable == null) read.unbreakable = new HashMap<>();
                data = read;
            }
        } catch (Exception e) {
            System.err.println("[Trident] Failed to read tree registry: " + e.getMessage());
        }
        rebuildCache();
    }

    private static synchronized void save() {
        try (Writer writer = Files.newBufferedWriter(file().toPath())) {
            GSON.toJson(data, writer);
        } catch (Exception e) {
            System.err.println("[Trident] Failed to save tree registry: " + e.getMessage());
        }
    }

    private static void rebuildCache() {
        unbreakableCache.clear();
        for (Map.Entry<String, List<BlockPos>> e : data.unbreakable.entrySet()) {
            unbreakableCache.put(e.getKey(), new HashSet<>(e.getValue()));
        }
    }

    /** Records a verified tree (or refreshes its stand spot). */
    public static synchronized void recordTree(String island, String type, BlockPos base, BlockPos stand) {
        load();
        for (KnownTree t : data.trees) {
            if (!island.equals(t.island)) continue;
            int dx = Math.abs(t.base.getX() - base.getX());
            int dz = Math.abs(t.base.getZ() - base.getZ());
            int dy = Math.abs(t.base.getY() - base.getY());
            if (dx <= 2 && dz <= 2 && dy <= 2) {
                t.type = type;
                t.base = base;
                t.stand = stand;
                save();
                return;
            }
        }
        KnownTree t = new KnownTree();
        t.island = island;
        t.type = type;
        t.base = base;
        t.stand = stand;
        data.trees.add(t);
        save();
    }

    /** Marks positions as permanent scenery (never breakable, never targeted). */
    public static synchronized void recordUnbreakable(String island, Collection<BlockPos> positions) {
        load();
        List<BlockPos> list = data.unbreakable.computeIfAbsent(island, k -> new ArrayList<>());
        Set<BlockPos> cache = unbreakableCache.computeIfAbsent(island, k -> new HashSet<>());
        boolean changed = false;
        for (BlockPos pos : positions) {
            if (cache.add(pos)) {
                list.add(pos);
                changed = true;
            }
        }
        if (changed) save();
    }

    public static synchronized boolean isUnbreakable(String island, BlockPos pos) {
        load();
        Set<BlockPos> set = unbreakableCache.get(island);
        return set != null && set.contains(pos);
    }

    public static synchronized List<KnownTree> treesFor(String island, String type) {
        load();
        List<KnownTree> out = new ArrayList<>();
        for (KnownTree t : data.trees) {
            if (island.equals(t.island) && type.equals(t.type)) out.add(t);
        }
        return out;
    }

    /** The remembered chop-spot for this tree, or null if it isn't mapped. */
    public static synchronized BlockPos standFor(String island, BlockPos base) {
        load();
        for (KnownTree t : data.trees) {
            if (!island.equals(t.island)) continue;
            int dx = Math.abs(t.base.getX() - base.getX());
            int dz = Math.abs(t.base.getZ() - base.getZ());
            int dy = Math.abs(t.base.getY() - base.getY());
            if (dx <= 2 && dz <= 2 && dy <= 2) return t.stand;
        }
        return null;
    }
}
