package io.github.tjhgibbs.stacksmith.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.tjhgibbs.stacksmith.mod.StacksmithMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Client entrypoint: J opens the spawner you're looking at, or else the editor on the tab used last. */
public final class StacksmithClient implements ClientModInitializer {
    static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(StacksmithMod.MOD_ID, "main"));
    static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.stacksmith.open", InputConstants.Type.KEYBOARD, InputConstants.KEY_J, CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            // Key mappings only see presses while no screen has the input, so this never opens
            // the editor over another screen.
            while (openKey.consumeClick()) {
                // Looking at a spawner: edit that spawner. Otherwise the tab used last.
                net.minecraft.core.BlockPos spawner = WorldSpawner.lookedAtSpawner();
                if (spawner != null && spawner.equals(Session.spawnerPos)) {
                    // Already editing this one: keep any unsaved changes.
                    mc.setScreenAndShow(new SpawnerEditorScreen(null));
                } else if (spawner != null) {
                    WorldSpawner.load(spawner, () -> mc.setScreenAndShow(new SpawnerEditorScreen(null)));
                } else {
                    mc.setScreenAndShow(StacksmithScreen.open(StacksmithScreen.lastTab, null));
                }
            }
        });
    }
}
