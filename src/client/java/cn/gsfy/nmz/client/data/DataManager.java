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
 * 把 {@code assets/nomorezombies/data/*.json} 里的各张数据表统一解析、组装成一份
 * 可整体替换的 {@link GameData}——波次、Boss 轮、道具规律、AA 指挥详情的唯一来源。
 *
 * <p>以 {@link IdentifiableResourceReloadListener} 挂进客户端资源加载阶段，于是 F3+T
 * 也能热刷新数据。解析先在新对象 {@code newData} 上攒齐，再一次赋值替换静态字段
 * {@code data}——读者永远看到「完整的一张表」，而不是拼到一半的半成品；其余模块只
 * 通过 {@link #get()} 只读访问。
 */
public class DataManager {

    private static final String DATA_DIR = "data";
    private static GameData data = new GameData();

    /**
     * 取当前生效的游戏数据——重载未完成期间可能仍是旧表或空表，调用方按需容忍。
     *
     * @return 当前已解析的 {@link GameData}
     */
    public static GameData get() {
        return data;
    }

    /**
     * 注册资源重载监听器——把数据表刷新挂进客户端资源重载（含 F3+T），
     * 改数据文件不用重启游戏。建议只在模组初始化阶段调用一次。
     */
    public static void init() {
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES)
                .registerReloadListener(new IdentifiableResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return Identifier.of(NoMoreZombies.MOD_ID, "game_data");
                    }

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
     * 在后台准备线程里把四张数据表逐个解析、组装成新的 {@link GameData}。
     * 单表失败只丢那一张（回退空表），不拖垮整次资源重载。
     *
     * @param manager 客户端资源管理器，用于定位数据文件
     */
    private static void load(ResourceManager manager) {
        GameData newData = new GameData();
        // 文件级隔离：单表解析失败只丢该表（回退空表），不拖垮整个资源重载。
        // 之所以要兜底：AA 波次表只有 103 行，而回合数上限为 105，缺的那两行
        // 由 CheckSpawnTimes/SpawnNotice 的 isValidIndex 守卫静默跳过。
        loadTable(manager, "wave_times", newData::addWaveTimes);
        loadTable(manager, "boss_rounds", newData::addBossRounds);
        loadTable(manager, "powerup_patterns", newData::addPowerupPatterns);
        loadTable(manager, "aa_round_details", newData::addAaRoundDetails);
        data = newData;
    }

    /**
     * 读取并解析单张数据表；任一步异常只回退该表（记日志、保持空表），
     * 不向调用方抛错，也不中断其它表的加载。
     *
     * @param manager 客户端资源管理器
     * @param name    数据表文件名（不含 {@code .json} 后缀，位于 data 目录）
     * @param parser  解析回调，接收读取到的根 JSON 对象
     */
    private static void loadTable(ResourceManager manager, String name, Consumer<JsonObject> parser) {
        try {
            parser.accept(readJson(manager, name));
        } catch (Exception e) {
            NoMoreZombies.LOGGER.error("Failed to parse data file {}; keeping empty table for it", name, e);
        }
    }

    /**
     * 读取并解析指定数据文件为顶层 JSON 对象。
     * 文件缺失 / 读取出错 / 解析失败都返回空对象并记日志——宁可给空表，
     * 也不让一份坏文件把整个资源重载崩掉。
     *
     * @param manager 客户端资源管理器
     * @param name    文件名（不含 {@code .json} 后缀，位于 data 目录）
     * @return 数据文件的根 JSON 对象；文件缺失或解析失败时为空对象
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
