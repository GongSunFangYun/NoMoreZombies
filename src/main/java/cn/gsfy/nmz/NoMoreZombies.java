package cn.gsfy.nmz;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod main class (Fabric entry) - a facade as light as it gets.
 *
 * <p>It only holds global constants (mod id / display name / logger); the
 * real client logic lives in {@code cn.gsfy.nmz.client.NoMoreZombiesClient},
 * and {@link #onInitialize()} is left for common/server-side initialization
 * (empty: a client-only mod has no use for it).
 */
public class NoMoreZombies implements ModInitializer {

    /** Mod identifier - namespace prefix for textures, configs, data packs
     * and every other resource */
    public static final String MOD_ID = "nomorezombies";
    /** Mod display name: chat prefix {@code [NoMoreZombies]} and logger naming */
    public static final String MOD_NAME = "NoMoreZombies";
    /** Global logger, named after the display name; all client features take
     * it from here so logs filter by one name */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    /**
     * Fabric common entry - a client-only mod registers nothing here; the
     * empty body just gets the main class loaded by the framework.
     */
    @Override
    public void onInitialize() {
    }
}
