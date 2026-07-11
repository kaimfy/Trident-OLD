package com.trident.pathing;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Lightweight A* walker for the foraging macro.
 *
 * Finds a natural walking route over the terrain, weighted the way a player
 * reads it: flat open ground is cheap, hopping up costs extra, drops cost a
 * little, pushing through plants costs a little, leaves-as-floor are dubious,
 * water and slime blocks (portals) are avoided outright. The cheapest route is
 * therefore usually the "open" one — around the bush, not over it — rather
 * than the geometric beeline.
 *
 * Paths are lists of feet positions. When the goal can't be reached within the
 * expansion budget, the best partial path (closest approach) is returned so the
 * caller still walks in the right direction instead of freezing.
 */
public final class TreePathfinder {

    private TreePathfinder() {}

    private static final int MAX_EXPANSIONS = 10000;

    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private record Node(BlockPos pos, double g, double f) {}

    /**
     * @param acceptRadius horizontal distance from {@code goal} at which a node
     *                     counts as "arrived" (chop reach, not exact block)
     */
    public static List<BlockPos> findPath(World world, BlockPos start, BlockPos goal, double acceptRadius) {
        BlockPos s = snapToGround(world, start);
        if (s == null) return List.of();

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        Map<BlockPos, Double> gScore = new HashMap<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();

        gScore.put(s, 0.0);
        open.add(new Node(s, 0.0, heuristic(s, goal)));

        BlockPos best = s;
        double bestH = heuristic(s, goal);
        double acceptSq = acceptRadius * acceptRadius;
        int expansions = 0;

        while (!open.isEmpty() && expansions < MAX_EXPANSIONS) {
            Node current = open.poll();
            if (current.g() > gScore.getOrDefault(current.pos(), Double.MAX_VALUE)) continue;
            expansions++;

            double h = heuristic(current.pos(), goal);
            if (h < bestH) {
                bestH = h;
                best = current.pos();
            }

            if (horizontalSq(current.pos(), goal) <= acceptSq
                    && Math.abs(current.pos().getY() - goal.getY()) <= 3) {
                return reconstruct(cameFrom, current.pos());
            }

            expand(world, current, goal, open, gScore, cameFrom);
        }

        // Budget exhausted or unreachable — walk toward the closest approach.
        return reconstruct(cameFrom, best);
    }

    private static void expand(World world, Node current, BlockPos goal,
            PriorityQueue<Node> open, Map<BlockPos, Double> gScore, Map<BlockPos, BlockPos> cameFrom) {
        BlockPos cur = current.pos();
        double curStand = standHeight(world, cur);
        if (Double.isNaN(curStand)) return;

        for (int[] d : DIRS) {
            boolean diagonal = d[0] != 0 && d[1] != 0;
            // No corner cutting: both orthogonal columns must be open
            if (diagonal
                    && (!passableColumn(world, cur.add(d[0], 0, 0))
                            || !passableColumn(world, cur.add(0, 0, d[1])))) {
                continue;
            }

            // Level first, then hop up, then drops — first standable wins.
            // Costs come from the REAL surface heights (collision shape tops),
            // so stairs/slabs are walked like a player walks them: a rise of
            // up to 0.6 is auto-stepped, only true ledges cost a jump.
            for (int dy : new int[]{0, 1, -1, -2, -3}) {
                BlockPos np = cur.add(d[0], dy, d[1]);
                double npStand = standHeight(world, np);
                if (Double.isNaN(npStand)) continue;

                double rise = npStand - curStand;
                if (rise > 1.25) continue;                       // too high even with a jump
                if (rise < -4.0) continue;                       // don't leap off cliffs
                boolean needsJump = rise > 0.6;
                if (needsJump && !isPassable(world, cur.up(2))) break; // no headroom

                double cost = diagonal ? 1.4142 : 1.0;
                // Jumps are expensive on purpose: a player walks around a log,
                // they don't parkour over it unless the detour is really long.
                if (needsJump) cost += 4.0;
                else if (rise > 0.05) cost += 0.15;              // stair/slab step
                if (rise < -0.05) cost += 0.3 * -rise;           // drops
                cost += surfacePenalty(world, np);

                double ng = current.g() + cost;
                if (ng < gScore.getOrDefault(np, Double.MAX_VALUE)) {
                    gScore.put(np, ng);
                    cameFrom.put(np, cur);
                    open.add(new Node(np, ng, ng + heuristic(np, goal)));
                }
                break;
            }
        }
    }

    // ===== terrain reading =====

    /**
     * The exact Y a player's feet rest at when standing in this node's column,
     * or NaN when it isn't standable. Handles partial blocks: standing INSIDE a
     * stair/slab/carpet block (collision top ≤ 0.6) is valid — that's how the
     * game itself works — while full blocks require standing on top of them.
     */
    public static double standHeight(World world, BlockPos feet) {
        BlockState feetState = world.getBlockState(feet);
        if (!feetState.getFluidState().isEmpty()) {
            // Shallow water is wadeable (the Galatea swamp): solid ground under
            // it and a dry head. Deeper water stays impassable.
            BlockPos ground = feet.down();
            VoxelShape groundShape = world.getBlockState(ground).getCollisionShape(world, ground);
            if (groundShape.isEmpty()) return Double.NaN;
            if (world.getBlockState(ground).getBlock() == Blocks.SLIME_BLOCK) return Double.NaN;
            if (!isPassable(world, feet.up())) return Double.NaN;
            if (!feetState.getCollisionShape(world, feet).isEmpty()) return Double.NaN;
            return ground.getY() + groundShape.getMax(Direction.Axis.Y);
        }
        VoxelShape feetShape = feetState.getCollisionShape(world, feet);

        if (!feetShape.isEmpty()) {
            // Partial block underfoot (stair, slab, carpet, path...)
            double top = feetShape.getMax(Direction.Axis.Y);
            if (top > 0.6) return Double.NaN;
            if (feetState.getBlock() == Blocks.SLIME_BLOCK) return Double.NaN;
            if (!isPassable(world, feet.up()) || !isPassable(world, feet.up(2))) return Double.NaN;
            return feet.getY() + top;
        }

        BlockPos ground = feet.down();
        BlockState groundState = world.getBlockState(ground);
        VoxelShape groundShape = groundState.getCollisionShape(world, ground);
        if (groundShape.isEmpty()) return Double.NaN;
        if (groundState.getBlock() == Blocks.SLIME_BLOCK) return Double.NaN;
        if (!isPassable(world, feet.up())) return Double.NaN;
        return ground.getY() + groundShape.getMax(Direction.Axis.Y);
    }

    private static boolean isStandable(World world, BlockPos feet) {
        return !Double.isNaN(standHeight(world, feet));
    }

    private static boolean isPassable(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!state.getCollisionShape(world, pos).isEmpty()) return false;
        // Deep enough to swim in counts as blocked — routes go around ponds
        return state.getFluidState().isEmpty();
    }

    private static boolean passableColumn(World world, BlockPos feet) {
        return isPassable(world, feet) && isPassable(world, feet.up());
    }

    private static double surfacePenalty(World world, BlockPos feet) {
        double penalty = 0;
        BlockState support = world.getBlockState(feet.down());
        BlockState body = world.getBlockState(feet);
        // Trees are obstacles, not floor: never route across logs or canopies
        // when there's any way around them.
        if (support.isIn(BlockTags.LOGS) || body.isIn(BlockTags.LOGS)) {
            penalty += 4.0;
        }
        if (support.getBlock() instanceof LeavesBlock) {
            penalty += 3.0;
        }
        if (!body.getFluidState().isEmpty()) {
            penalty += 2.5; // wading is slow and conspicuous — prefer dry land
        } else if (!body.isAir() && body.getCollisionShape(world, feet).isEmpty()) {
            penalty += 0.3; // pushing through grass/plants
        }
        return penalty;
    }

    private static BlockPos snapToGround(World world, BlockPos start) {
        for (int dy = 0; dy >= -3; dy--) {
            BlockPos p = start.up(dy);
            if (isStandable(world, p)) return p;
        }
        return null;
    }

    // ===== math / bookkeeping =====

    private static double heuristic(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dz * dz) + 0.4 * Math.abs(to.getY() - from.getY());
    }

    private static double horizontalSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static List<BlockPos> reconstruct(Map<BlockPos, BlockPos> cameFrom, BlockPos end) {
        List<BlockPos> path = new ArrayList<>();
        BlockPos cur = end;
        while (cur != null) {
            path.add(cur);
            cur = cameFrom.get(cur);
        }
        java.util.Collections.reverse(path);
        if (!path.isEmpty()) path.remove(0); // caller already stands on the start node
        return path;
    }
}
