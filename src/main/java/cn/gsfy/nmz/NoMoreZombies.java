package cn.gsfy.nmz;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组主类（Fabric 入口）——一个「轻到不能再轻」的门面。
 *
 * <p>这里只持有全局常量（mod id / 显示名 / 日志器）；真正的客户端逻辑都在
 * {@code cn.gsfy.nmz.client.NoMoreZombiesClient} 里点亮，{@link #onInitialize()} 留给
 * 服务端 / 通用初始化（目前为空，纯客户端 mod 用不上）。
 */
public class NoMoreZombies implements ModInitializer {

    /** 模组标识符——纹理、配置、数据包等一切资源的命名空间前缀。 */
    public static final String MOD_ID = "nomorezombies";
    /** 模组显示名，用于聊天前缀 {@code [NoMoreZombies]} 与日志器命名。 */
    public static final String MOD_NAME = "NoMoreZombies";
    /** 全局日志器。 */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    @Override
    public void onInitialize() {
    }
}
