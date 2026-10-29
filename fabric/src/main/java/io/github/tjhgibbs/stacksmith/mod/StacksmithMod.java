package io.github.tjhgibbs.stacksmith.mod;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common entrypoint. Stacksmith needs nothing on the server; this only exists for the id and log. */
public final class StacksmithMod implements ModInitializer {
    public static final String MOD_ID = "stacksmith";
    public static final Logger LOG = LoggerFactory.getLogger("Stacksmith");

    @Override
    public void onInitialize() {
        LOG.info("Stacksmith ready");
    }
}
