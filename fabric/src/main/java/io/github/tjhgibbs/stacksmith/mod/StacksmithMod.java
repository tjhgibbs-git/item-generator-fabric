package io.github.tjhgibbs.stacksmith.mod;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common entrypoint: register optional world hooks on integrated and dedicated Fabric servers. */
public final class StacksmithMod implements ModInitializer {
    public static final String MOD_ID = "stacksmith";
    public static final Logger LOG = LoggerFactory.getLogger("Stacksmith");

    @Override
    public void onInitialize() {
        io.github.tjhgibbs.stacksmith.server.FabricHooks.register();
        LOG.info("Stacksmith ready (optional world hooks registered)");
    }
}
