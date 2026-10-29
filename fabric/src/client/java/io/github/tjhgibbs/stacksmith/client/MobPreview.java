package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.client.ui.Theme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** The live mob preview box: the mob wearing its gear, turning to follow the mouse. */
final class MobPreview {
    private MobPreview() {
    }

    static void draw(GuiGraphicsExtractor g, Font font, Entity preview, int x, int y, int w, int h, int mouseX, int mouseY) {
        g.fillGradient(x, y, x + w, y + h, 0xFF20303C, 0xFF0E1418);
        if (preview instanceof LivingEntity living) {
            // Fit the bounding box (with room for a name tag) into the box. Height uses the box's
            // diagonal: wide mobs (guardians, ghasts) tilt toward the mouse and look taller.
            // Small mobs (silverfish, babies) are capped so they don't fill the box past its edges.
            float bh = Math.max(0.3f, living.getBbHeight()), bw = Math.max(0.6f, living.getBbWidth());
            float tall = Math.max(1.1f, (float) Math.sqrt(bh * bh + bw * bw));
            int size = (int) Math.max(6, Math.min(h * 0.62f / tall, w * 0.55f / bw));
            InventoryScreen.extractEntityInInventoryFollowsMouse(g, x, y, x + w, y + h, size, 0.0625f + yNudge(living), mouseX, mouseY, living);
        } else {
            g.centeredText(font, Component.literal(preview == null ? "No preview" : "Not a mob: no preview"), x + w / 2, y + h / 2 - 4, Theme.TEXT_DIM);
        }
    }

    /** Blocks to move a mob down: the elder guardian's model is scaled past its hitbox and draws high. */
    private static float yNudge(LivingEntity e) {
        return e instanceof net.minecraft.world.entity.monster.ElderGuardian ? 0.5f : 0f;
    }
}
