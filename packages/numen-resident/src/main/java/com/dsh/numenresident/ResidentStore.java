package com.dsh.numenresident;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 数据落盘位置。
 *
 * <h2>为什么不放在 NumenApi.configDir()</h2>
 * {@code configDir()} 返回的是<b>全局</b> {@code config/numen/}，不在存档里。
 * 而 Numen 自己的同伴名册存在 {@code <存档>/data/numen_companions.dat}。
 * 两边不一致会出现这种事：把存档拷到另一台机器，同伴跟着过去了，
 * 记忆却没跟过去——同伴醒来后不认识任何人。
 *
 * <p>本 mod 的记忆属于「这个存档里发生的事」，所以必须放在<b>存档内</b>。
 * 这样"换个存档也能用"才是真的：存档带走，记忆一起带走。
 *
 * <p>目录形状：
 * <pre>
 * &lt;存档&gt;/numen_resident/
 *     memory/&lt;companion-uuid&gt;/events.jsonl
 * </pre>
 */
public final class ResidentStore {

    private static volatile Path worldRoot;

    private ResidentStore() {}

    /** 服务端启动时调用一次。 */
    static void bindWorld(MinecraftServer server) {
        try {
            // 取绝对路径并规范化：MinecraftServer.getWorldPath(ROOT) 在专用服务端上
            // 返回的是相对于工作目录的路径（如 ".\world"），规范化后日志才是干净的绝对路径。
            Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            worldRoot = root.resolve(NumenResident.MODID);
            Files.createDirectories(worldRoot);
            NumenResident.LOGGER.info("[resident] 数据目录（存档内）= {}", worldRoot);
        } catch (Throwable t) {
            worldRoot = null;
            NumenResident.LOGGER.warn("[resident] 无法确定存档数据目录，本局不落盘: {}", t.toString());
        }
    }

    /**
     * 本存档的数据根目录。
     *
     * @return 目录路径；若服务端尚未启动或无存档上下文，返回 {@code null}
     */
    public static Path root() {
        return worldRoot;
    }

    /**
     * 取某个同伴的数据目录，并确保存在。
     *
     * @param companionUuid 同伴 UUID
     * @return 目录路径；不可用时返回 {@code null}
     */
    public static Path companionDir(java.util.UUID companionUuid) {
        Path r = worldRoot;
        if (r == null || companionUuid == null) {
            return null;
        }
        Path dir = r.resolve("memory").resolve(companionUuid.toString());
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            NumenResident.LOGGER.warn("[resident] 无法创建同伴目录 {}: {}", dir, e.toString());
            return null;
        }
    }
}
