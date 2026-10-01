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
 * At-rest encryption for the API Key (local obfuscation-grade protection):
 * keeps the plaintext from sitting directly in the config file.
 *
 * <p>AES-256/GCM/NoPadding, with the key derived by PBKDF2WithHmacSHA256
 * from the local MachineGuid (Windows registry
 * {@code HKLM\SOFTWARE\Microsoft\Cryptography}, fixed per machine and
 * account independent, fixed app salt + 65536 iterations). <b>No key
 * material is stored anywhere else</b>, so the ciphertext format is fixed
 * as {@code enc:v1:base64(iv||ciphertext+tag)}: any account can decrypt
 * (the key is bound to the machine only); on another machine decryption
 * fails (returns empty, the key must be re-entered).
 *
 * <p>Security boundary, stated honestly: MachineGuid is readable straight
 * from the local registry, so this is obfuscation against local plaintext
 * reading, not strong cryptography - anyone who can read the registry or
 * run code on this machine can recover the Key.
 * That trade-off is inherent to storing no key material; on non-Windows,
 * or when the MachineGuid read fails, the fallback is the account session
 * UUID, which binds to the account only, not the machine.
 */
public final class ApiKeyCrypto {

    private static final String PREFIX = "enc:v1:";
    private static final byte[] SALT = "nomorezombies-apikey-v1".getBytes(StandardCharsets.UTF_8);
    private static final int ITERATIONS = 65536;
    private static final int KEY_BITS = 256;
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** MachineGuid cache: the registry is read once per process; repeat
     * reads are wasteful and can catch a half-written value */
    private static String cachedMachineGuid;
    /** UUID-shape match (MachineGuid and player UUIDs share this format):
     * pulled straight from the reg output, immune to the system code page */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private ApiKeyCrypto() {
    }

    /** Derive the AES key from the given secret: PBKDF2WithHmacSHA256,
     * fixed salt + 65536 iterations; all secrets funnel through here */
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

    /** Machine-level secret = Windows MachineGuid (registry, fixed per
     * machine, account independent), so switching accounts keeps the Key
     * unlocked. Returns null on non-Windows / read failure; the caller
     * decides the fallback */
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
            // Guard against a hung child process: give reg at most 3 seconds,
            // then force-kill - an unreadable value counts as no value, and
            // encryption must not stall on it
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            // reg output encoding varies with the system code page, but
            // MachineGuid is always UUID-shaped; a regex pull is the most
            // robust extraction
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

    /** Account-level secret = the local player's session UUID - the
     * fallback derivation source when the machine key is unavailable,
     * shared by both the encrypt and decrypt paths */
    private static String accountUuid() {
        MinecraftClient client = MinecraftClient.getInstance();
        UUID uuid = (client != null && client.getSession() != null)
                ? client.getSession().getUuidOrNull() : null;
        return uuid != null ? uuid.toString() : null;
    }

    /**
     * Encrypt the plaintext for at-rest storage - machine key first, so the
     * Key follows the machine, not the account.
     *
     * <p>When no key material is available the plaintext is returned as-is,
     * rather than rejecting the save or storing an empty string: the only
     * purpose of this config is handing the Key to the query logic. Storing
     * an empty string throws away the Key the player typed; rejecting the
     * save makes the setting look like it was never saved in the GUI.
     * Plaintext at least keeps the feature working, at the cost of a bare
     * key in the file - see the security boundary note in the class
     * comment.
     *
     * @param plain plaintext to encrypt (may be empty)
     * @return ciphertext of the form {@code enc:v1:...}; empty string for
     *  empty plaintext; the plaintext unchanged when no key material is
     *  available (non-Windows + offline/error) - the downgrade boundary
     *  lives in {@link ConfigApiKey#getAsJsonElement()}
     */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        // Machine key first: MachineGuid is account independent; fall back
        // to the account UUID only when it is unavailable
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
     * Decrypt a stored API Key, trying machine key first, then account key.
     * <ul>
     *  <li>Not in this format (plaintext): read back unchanged;</li>
     *  <li>Machine key first (MachineGuid, decrypts under any account),
     *  then the account key on failure (for ciphertext encrypted with the
     *  account-UUID-derived key);</li>
     *  <li>No key material or both fail (new machine / corrupted data):
     *  empty string (the key must be re-entered)</li>
     * </ul>
     *
     * @param stored raw value read from the config file (ciphertext or
     *  plaintext)
     * @return decrypted plaintext; unchanged for foreign formats, empty
     *  string on decryption failure
     */
    public static String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            return "";
        }
        if (!stored.startsWith(PREFIX)) {
            return stored;
        }
        // Machine key first: MachineGuid is account independent, so any
        // account decrypts the same ciphertext
        String mg = machineGuid();
        if (mg != null) {
            String plain = tryDecrypt(stored, deriveKey(mg));
            if (plain != null) {
                return plain;
            }
        }
        // Account key fallback: derived from the local player's session
        // UUID; switching accounts invalidates it
        String uuid = accountUuid();
        if (uuid != null) {
            String plain = tryDecrypt(stored, deriveKey(uuid));
            if (plain != null) {
                return plain;
            }
        }
        // New machine / corrupted data / no key material: this Key is
        // unrecoverable, only re-entering helps
        NoMoreZombies.LOGGER.warn("ApiKeyCrypto: decryption failed (machine or account mismatch?), API key needs to be re-entered");
        return "";
    }

    /** Try to decrypt one ciphertext with the given key: plaintext on
     * success, null on failure (tag check / format error) - decryption
     * never throws */
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
