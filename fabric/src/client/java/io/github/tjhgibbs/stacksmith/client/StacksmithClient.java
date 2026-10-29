package io.github.tjhgibbs.stacksmith.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.tjhgibbs.stacksmith.mod.StacksmithMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Client entrypoint: J opens the editor (on the item tab, or the spawner tab if that was last). */
public final class StacksmithClient implements ClientModInitializer {
    static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(StacksmithMod.MOD_ID, "main"));
    static KeyMapping openKey;
    /** Which editor the key reopens; set by the editors as they open. */
    static boolean spawnerLast;

    @Override
    public void onInitializeClient() {
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.stacksmith.open", InputConstants.Type.KEYBOARD, InputConstants.KEY_J, CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            // Key mappings only see presses while no screen has the input, so this never opens
            // the editor over another screen.
            while (openKey.consumeClick()) {
                mc.setScreenAndShow(spawnerLast ? new SpawnerEditorScreen(null) : new ItemEditorScreen(null));
            }
        });
    }
}
