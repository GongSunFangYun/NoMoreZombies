package cn.gsfy.nmz.client.data;

import cn.gsfy.nmz.NoMoreZombies;
import cn.gsfy.nmz.client.data.model.GameData;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Parses every table under {@code assets/nomorezombies/data/*.json} and
 * assembles them into one wholesale-replaceable {@link GameData}—the single
 * source for waves, boss rounds, powerup patterns, and AA command details.
 *
 * <p>Hooked into the client resource reload as an
 * {@link IdentifiableResourceReloadListener}, so F3+T refreshes data too.
 * Parsing builds up on a fresh {@code newData} object first, then swaps the
 * static {@code data} field in one assignment—readers always see a complete
 * table, never a half-assembled one. Other modules read it read-only through
 * {@link #get()}.
 */
public class DataManager {

    private static final String DATA_DIR = "data";
    private static GameData data = new GameData();

    /**
     * Current live game data. The reload swaps the {@code data} reference
     * only after the whole table is parsed on the prepare thread, so reads
     * see the old table or the initial empty one—never a half-assembled one.
     *
     * @return the shared instance by reference; callers treat it as
     *   read-only—in-place mutation writes back to every reader. Never
     *   {@code null}.
     */
    public static GameData get() {
        return data;
    }

    /**
     * Registers the reload listener—wires data refresh into the client
     * resource reload (F3+T included), so editing data files does not need
     * a game restart. Call once during mod initialization.
     */
    public static void init() {
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES)
                .registerReloadListener(new IdentifiableResourceReloadListener() {
                    /**
                     * Listener id ({@code nomorezombies:game_data}). Fabric
                     * orders reload stages by it, and log lines use it.
                     */
                    @Override
                    public Identifier getFabricId() {
                        return Identifier.of(NoMoreZombies.MOD_ID, "game_data");
                    }

                    /**
                     * Fires on first load or F3+T. {@code load} runs on
                     * prepareExecutor and does both the parse and the
                     * reference swap, then the synchronizer barrier is
                     * awaited; the apply stage carries only an empty action,
                     * so the returned future spans the whole reload.
                     *
                     * @param synchronizer Fabric's prepare/apply stage
                     *   synchronizer
                     * @param manager the current client resource manager
                     * @param prepareExecutor the background prepare executor
                     * @param applyExecutor the apply-stage executor
                     * @return a future completing both stages; an unexpected
                     *   runtime exception completes it exceptionally
                     */
                    @Override
                    public CompletableFuture<Void> reload(Synchronizer synchronizer, ResourceManager manager,
                                                          Executor prepareExecutor, Executor applyExecutor) {
                        return CompletableFuture.runAsync(() -> load(manager), prepareExecutor)
                                .thenCompose(synchronizer::whenPrepared)
                                .thenRunAsync(() -> {
                                }, applyExecutor);
                    }
                });
    }

    /**
     * Parses the four tables one by one on the background prepare thread and
     * assembles a new {@link GameData}. One table failing drops only that
     * table (falls back to empty), never the whole reload.
     *
     * @param manager client resource manager, used to locate the data files
     */
    private static void load(ResourceManager manager) {
        GameData newData = new GameData();
        // Today wave_times rows and caps line up: AA covers 1–105,
        // DE/BB/Prison cover 1–30. The empty-table fallback still stays:
        // a missing or corrupt hot-reload resource must not take down the
        // other three tables.
        loadTable(manager, "wave_times", newData::addWaveTimes);
        loadTable(manager, "boss_rounds", newData::addBossRounds);
        loadTable(manager, "powerup_patterns", newData::addPowerupPatterns);
        loadTable(manager, "aa_round_details", newData::addAaRoundDetails);
        data = newData;
    }

    /**
     * Reads and parses one data table. Any failure only rolls back that
     * table (logged, stays empty); it does not throw to the caller or
     * interrupt the other tables' loading.
     *
     * @param manager client resource manager
     * @param name data table file name (without the {@code .json} suffix,
     *   under the data directory)
     * @param parser parse callback receiving the root JSON object
     */
    private static void loadTable(ResourceManager manager, String name, Consumer<JsonObject> parser) {
        try {
            parser.accept(readJson(manager, name));
        } catch (Exception e) {
            NoMoreZombies.LOGGER.error("Failed to parse data file {}; keeping empty table for it", name, e);
        }
    }

    /**
     * Reads and parses the named data file into a top-level JSON object.
     * Missing file, read error, and parse failure all return an empty object
     * and log.
     *
     * @param manager client resource manager
     * @param name file name (without the {@code .json} suffix, under the
     *   data directory)
     * @return the file's root JSON object; an empty object on a missing file
     *   or parse failure
     */
    private static JsonObject readJson(ResourceManager manager, String name) {
        Identifier id = Identifier.of(NoMoreZombies.MOD_ID, DATA_DIR + "/" + name + ".json");
        Optional<Resource> resource = manager.getResource(id);
        if (resource.isEmpty()) {
            NoMoreZombies.LOGGER.warn("Missing data file: {}", id);
            return new JsonObject();
        }
        try (InputStream in = resource.get().getInputStream();
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException | JsonParseException e) {
            NoMoreZombies.LOGGER.error("Failed to read data file: {}", id, e);
            return new JsonObject();
        }
    }

    private DataManager() {
    }
}