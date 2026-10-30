package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.core.entity.SpawnerSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the linked spawner's ranges in the world while toggled on in the editor, so they stay
 * visible after closing it: the player range as a wireframe sphere (the spawner runs while a
 * player's feet are inside) and the spawn area as a box (mobs appear up to that far out on X
 * and Z, and one block above or below the spawner).
 *
 * <p>Uses the game's debug gizmos, emitted every client tick (the game collects gizmos during
 * a tick and draws the latest tick's each frame).
 */
final class RangeView {
    private static final int SPHERE_COLOR = 0xC0FFD24D, BOX_COLOR = 0xC07BD88F, MATCH_COLOR = 0xFFB48CFF;
    private static final int RINGS = 12, MERIDIANS = 16, SEGMENTS = 48;

    private RangeView() {
    }

    static void tick() {
        // Copies found (or just written) by "Same change for nearby copies".
        for (BlockPos p : SpawnerBatch.highlighted()) {
            Gizmos.cuboid(new AABB(p).inflate(0.02), GizmoStyle.strokeAndFill(MATCH_COLOR, 2.5f, 0x30B48CFF)).setAlwaysOnTop();
        }
        BlockPos pos = Session.spawnerPos;
        if (pos == null || (!Session.showPlayerRange && !Session.showSpawnRange) || !WorldSpawner.exists(pos)) {
            return;
        }
        Vec3 centre = Vec3.atCenterOf(pos);
        if (Session.showPlayerRange) {
            sphere(centre, Session.spawner.get(SpawnerSpec.Setting.PLAYER_RANGE));
        }
        if (Session.showSpawnRange) {
            int r = Session.spawner.get(SpawnerSpec.Setting.SPAWN_RANGE);
            AABB box = new AABB(centre.x - r, pos.getY() - 1, centre.z - r, centre.x + r, pos.getY() + 2, centre.z + r);
            Gizmos.cuboid(box, GizmoStyle.strokeAndFill(BOX_COLOR, 2f, 0x187BD88F));
        }
    }

    /** Latitude rings and meridians; more segments for big spheres would only cost lines. */
    private static void sphere(Vec3 c, double r) {
        if (r <= 0) {
            return;
        }
        for (int i = 1; i < RINGS; i++) {
            double lat = Math.PI * i / RINGS - Math.PI / 2;
            double y = c.y + r * Math.sin(lat), rr = r * Math.cos(lat);
            Vec3 prev = null;
            for (int s = 0; s <= SEGMENTS; s++) {
                double a = 2 * Math.PI * s / SEGMENTS;
                Vec3 p = new Vec3(c.x + rr * Math.cos(a), y, c.z + rr * Math.sin(a));
                if (prev != null) {
                    Gizmos.line(prev, p, SPHERE_COLOR, i == RINGS / 2 ? 3f : 1.5f);
                }
                prev = p;
            }
        }
        for (int m = 0; m < MERIDIANS; m++) {
            double a = 2 * Math.PI * m / MERIDIANS;
            Vec3 prev = null;
            for (int s = 0; s <= SEGMENTS / 2; s++) {
                double lat = Math.PI * s / (SEGMENTS / 2.0) - Math.PI / 2;
                double rr = r * Math.cos(lat);
                Vec3 p = new Vec3(c.x + rr * Math.cos(a), c.y + r * Math.sin(lat), c.z + rr * Math.sin(a));
                if (prev != null) {
                    Gizmos.line(prev, p, SPHERE_COLOR, 1.5f);
                }
                prev = p;
            }
        }
    }
}
