package cn.gsfy.nmz.client.data;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.Set;

/**
 * 僵尸枪械武器 → 物品映射（对应参考 ZombiesGuns 的 item→gun 表，13 把）。
 *
 * <p>只搬运物品判定所需的最小集合——金价 / 伤害等统计字段在纯客户端无用，不搬。
 */
public final class ZombiesGuns {

    /** 13 把僵尸枪对应的 1.21.4 原生物品（工具 / 剪刀 / 打火石），yarn 名与 mojmap 完全一致。 */
    private static final Set<Item> ZOMBIES_GUN_ITEMS = Set.of(
            Items.WOODEN_HOE,        // 手枪 Pistol
            Items.STONE_HOE,         // 步枪 Rifle
            Items.GOLDEN_SHOVEL,     // 彩虹步枪 Rainbow Rifle
            Items.IRON_HOE,          // 霰弹枪 Shotgun
            Items.STONE_SHOVEL,      // 火箭筒 Rocket Launcher
            Items.WOODEN_SHOVEL,     // 狙击枪 Sniper
            Items.GOLDEN_HOE,        // 火焰喷射器 Flamethrower
            Items.IRON_SHOVEL,       // 吹箭 Blow Dart
            Items.DIAMOND_HOE,       // 水枪 Zombie Soaker
            Items.DIAMOND_PICKAXE,   // 电击枪 Zombie Zapper
            Items.FLINT_AND_STEEL,   // 双管 Double Barrel Shotgun
            Items.SHEARS,            // 长者枪 Elder Gun
            Items.GOLDEN_PICKAXE     // 金矿枪 Gold Digger
    );

    private ZombiesGuns() {
    }

    /** 该物品是否为僵尸枪——供 NoFireParticle 过滤链做武器门控，只认上面那 13 把。 */
    public static boolean isZombiesGun(ItemStack stack) {
        return stack != null && !stack.isEmpty() && ZOMBIES_GUN_ITEMS.contains(stack.getItem());
    }
}
