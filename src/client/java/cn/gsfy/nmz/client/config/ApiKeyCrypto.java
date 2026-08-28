package cn.gsfy.nmz.client.config;

import cn.gsfy.nmz.NoMoreZombies;
import net.minecraft.client.MinecraftClient;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API Key 落盘加密（本地混淆级防护）——让明文不直接躺在配置文件里，眼不见为净。
 *
 * <p>算法是 AES-256/GCM/NoPadding，密钥由 PBKDF2WithHmacSHA256 从本机 MachineGuid 派生
 * （Windows 注册表 {@code HKLM\SOFTWARE\Microsoft\Cryptography}，本机固定、与账号无关，
 * 固定应用盐 + 65536 次迭代），<b>不额外存储任何密钥材料</b>——所以密文格式固定为
 * {@code enc:v1:<base64(iv||ciphertext+tag)>}：切任意账号都能解密（密钥只绑机器），
 * 换机器则解不开（返回空，需重填）。
 *
 * <p>安全边界（如实说明）：MachineGuid 本机注册表直读就能拿到，所以这是「防本地明文直读」
 * 的混淆级防护，不是强密码学保护——任何能读注册表 / 在本机跑代码的人都能解开 Key。
 * 这是不额外存密钥材料的固有取舍；非 Windows 或 MachineGuid 读取失败时回退账号会话 UUID（旧行为）。
 */
public final class ApiKeyCrypto {

    private static final String PREFIX = "enc:v1:";
    private static final byte[] SALT = "nomorezombies-apikey-v1".getBytes(StandardCharsets.UTF_8);
    private static final int ITERATIONS = 65536;
    private static final int KEY_BITS = 256;
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** MachineGuid 缓存：同一进程只读一次注册表，重复读浪费且可能读到半截。 */
    private static String cachedMachineGuid;
    /** UUID 形状匹配（MachineGuid 与玩家 UUID 都是该格式）：从 reg 输出里直接抠，不怕系统代码页。 */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private ApiKeyCrypto() {
    }

    /** 用给定密钥材料派生 AES 密钥：PBKDF2WithHmacSHA256 + 固定盐 + 65536 迭代，材料统一喂给这一处。 */
    private static SecretKeySpec deriveKey(String secret) {
        if (secret == null || secret.isEmpty()) {
            return null;
        }
        try {
            PBEKeySpec spec = new PBEKeySpec(secret.toCharArray(), SALT, ITERATIONS, KEY_BITS);
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            spec.clearPassword();
            return new SecretKeySpec(key, "AES");
        } catch (Exception e) {
            NoMoreZombies.LOGGER.error("ApiKeyCrypto: failed to derive key", e);
            return null;
        }
    }

    /** 机器级密钥材料 = Windows MachineGuid（注册表，本机固定、与账号无关），换号不解锁；
     * 非 Windows / 读取失败返回 null，交给调用方决定回退。 */
    private static String machineGuid() {
        if (cachedMachineGuid != null) {
            return cachedMachineGuid;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            return null;
        }
        try {
            Process p = new ProcessBuilder("reg", "query",
                    "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid")
                    .redirectErrorStream(true).start();
            // 防子进程挂起：reg 最多等 3 秒，超时就强杀——读不到就当拿不到，别卡住加密
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            // reg 输出编码随系统代码页变化，但 MachineGuid 恒为 UUID 形状，用正则抠最稳
            Matcher m = UUID_PATTERN.matcher(output);
            if (m.find()) {
                cachedMachineGuid = m.group().toLowerCase();
                return cachedMachineGuid;
            }
        } catch (Exception e) {
            NoMoreZombies.LOGGER.warn("ApiKeyCrypto: failed to read MachineGuid, falling back to account UUID", e);
        }
        return null;
    }

    /** 账号级密钥材料 = 当前本地玩家会话 UUID——旧版派生源，留着只为平滑迁移旧密文。 */
    private static String accountUuid() {
        MinecraftClient client = MinecraftClient.getInstance();
        UUID uuid = (client != null && client.getSession() != null)
                ? client.getSession().getUuidOrNull() : null;
        return uuid != null ? uuid.toString() : null;
    }

    /**
     * 加密明文并落盘存储——优先机器密钥，让 Key 随机器走、不随账号走。
     *
     * @param plain 待加密的明文字符串（可为空）
     * @return 形如 {@code enc:v1:...} 的密文；明文为空返回空串；密钥不可用（非 Windows 且离线/异常）
     *         时返回明文原样（降级，调用方已获知此风险）
     */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        // 机器密钥优先（MachineGuid，切号不受影响）；拿不到（非 Windows/读取失败）才回退账号 UUID
        String mg = machineGuid();
        SecretKeySpec key = mg != null ? deriveKey(mg) : deriveKey(accountUuid());
        if (key == null) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            NoMoreZombies.LOGGER.error("ApiKeyCrypto: encryption failed, storing plaintext", e);
            return plain;
        }
    }

    /**
     * 解密已存储的 API Key——按「先机器、后账号」的顺序逐个试，兼顾新密文与旧版本。
     * <ul>
     *   <li>非本格式（旧版明文）→ 原样读入（升级迁移）；</li>
     *   <li>先试机器密钥（MachineGuid，切任意账号都能解），失败再试旧账号密钥（平滑迁移旧版本 UUID 加密的密文）；</li>
     *   <li>密钥不可用或全部失败（换机器/数据损坏）→ 返回空串（需重填）。</li>
     * </ul>
     *
     * @param stored 配置文件中读取的原始值（密文或旧版明文）
     * @return 解密后的明文；非本格式原样返回，解密失败返回空串
     */
    public static String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            return "";
        }
        if (!stored.startsWith(PREFIX)) {
            return stored;
        }
        // 机器密钥优先：MachineGuid 与账号无关，切任意账号都能解同一份密文
        String mg = machineGuid();
        if (mg != null) {
            String plain = tryDecrypt(stored, deriveKey(mg));
            if (plain != null) {
                return plain;
            }
        }
        // 旧账号密钥兜底：旧版本用账号 UUID 派生密钥，这里解一次让不换号的升级直接兼容
        String uuid = accountUuid();
        if (uuid != null) {
            String plain = tryDecrypt(stored, deriveKey(uuid));
            if (plain != null) {
                return plain;
            }
        }
        // 换机器 / 数据损坏 / 密钥不可用：这把 Key 解不开了，只能提示重填
        NoMoreZombies.LOGGER.warn("ApiKeyCrypto: decryption failed (machine or account mismatch?), API key needs to be re-entered");
        return "";
    }

    /** 用给定密钥试解一份密文：成功返回明文，失败（tag 校验不过 / 格式错误）返回 null——解密不抛异常。 */
    private static String tryDecrypt(String stored, SecretKeySpec key) {
        if (key == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (all.length <= IV_LENGTH) {
                return null;
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] pt = cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
