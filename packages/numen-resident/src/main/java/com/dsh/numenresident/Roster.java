package com.dsh.numenresident;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 身份名册：<b>这个世界里有哪些"行动者"，谁是 AI。</b>
 *
 * <h2>为什么需要它</h2>
 * 实测发现两种 agent 的相互感知是<b>缺失且不对称</b>的：
 * <ul>
 *   <li>Mindcraft 把 Numen 同伴当作 <b>{@code Human player: Lin}</b> —— 它不知道那是同类</li>
 *   <li>Numen 同伴的上下文里<b>完全没有"别人"这一项</b>（其事件类型全与自身/主人相关）</li>
 * </ul>
 * 而要做社会实验，agent 最起码得知道"这里有别的行动者"——
 * 这是你定的底线：<b>可以让它意识到别人存在，但不要指定互动形式、不要硬引导。</b>
 *
 * <h2>它只写事实</h2>
 * 名册只有身份与类别（{@code numen} / {@code mindcraft} / {@code human}），
 * <b>不含任何关系标签</b>（没有 trust、没有 friend/enemy）。
 * "该怎么对待它"是 agent 自己的判断。
 *
 * <h2>为什么放在服务端</h2>
 * 服务端是三方（客户端 Numen、外部 Mindcraft、将来的其他工具）唯一的共同观察点。
 * 文件落在<b>存档内</b>，所以随存档迁移。
 *
 * <pre>
 * &lt;存档&gt;/numenresident/agents.json
 * {
 *   "updated": 1790000000000,
 *   "agents": [
 *     { "name": "Lin",  "type": "numen",     "uuid": "..." },
 *     { "name": "Andy", "type": "mindcraft", "uuid": "..." },
 *     { "name": "alice","type": "human",     "uuid": "..." }
 *   ]
 * }
 * </pre>
 */
public final class Roster {

    private static final String FILE = "agents.json";

    /**
     * 已知的 Mindcraft agent 名。
     *
     * <p>为什么要在服务端硬编码/配置：Mindcraft 的 bot 走的是<b>完全正常的原版协议</b>
     * （我们给它做了 Forge 握手，但协议层看就是普通玩家），
     * 服务端<b>没有任何技术手段</b>能把它和真人区分开。
     * 所以这一项只能由外部告知。
     *
     * <p>放在 {@code config/numenresident-roster.properties} 里：
     * {@code MINDCRAFT_AGENTS=Andy,Bob}
     */
    private static final Set<String> MINDCRAFT_AGENTS = new LinkedHashSet<>();

    /** 配置文件路径与上次读入时的修改时间 —— 用于热重载。 */
    private static Path configFile;
    private static long configStamp = -1L;

    private Roster() {}

    /**
     * 从配置读 Mindcraft agent 名单。<b>支持热重载</b>——
     * 每次 {@link #write} 前检查文件修改时间，变了才重读。
     *
     * <p>为什么必须热重载：实测踩过一次——把 {@code Rui} 加进配置后，
     * 服务端仍然只认 {@code Andy}，因为原来是"启动时读一次"。
     * 而改这个名单每次都重启服务端，会把所有 agent 踢下线、打断它们的长期行为，
     * 对"观察持续生活"的实验来说是很大的干扰。
     */
    static void loadConfig(Path configDir) {
        try {
            Path f = configDir.resolve("numenresident-roster.properties");
            configFile = f;
            if (!Files.exists(f)) {
                Files.createDirectories(configDir);
                Files.write(f, List.of(
                        "# 哪些玩家名是 Mindcraft 的 agent（逗号分隔）。",
                        "#",
                        "# 为什么要人工声明：Mindcraft 走的是完全正常的原版协议，",
                        "# 服务端在协议层无法把它和真人区分开。",
                        "# 不声明的话，它会被当作 human。",
                        "#",
                        "# 本文件热重载：改完保存即可，不必重启服务端。",
                        "mindcraft_agents="
                ));
                configStamp = Files.getLastModifiedTime(f).toMillis();
                return;
            }
            long stamp = Files.getLastModifiedTime(f).toMillis();
            if (stamp == configStamp) return;   // 没变，不重读
            configStamp = stamp;

            Set<String> next = new LinkedHashSet<>();
            for (String raw : Files.readAllLines(f)) {
                String s = raw.strip();
                if (s.isEmpty() || s.startsWith("#")) continue;
                int eq = s.indexOf('=');
                if (eq < 0) continue;
                if (!s.substring(0, eq).strip().equalsIgnoreCase("mindcraft_agents")) continue;
                for (String n : s.substring(eq + 1).split(",")) {
                    String t = n.strip();
                    if (!t.isEmpty()) next.add(t);
                }
            }
            MINDCRAFT_AGENTS.clear();
            MINDCRAFT_AGENTS.addAll(next);
            NumenResident.LOGGER.info("[resident] 名册配置（热重载）：{} 个 Mindcraft agent = {}",
                    MINDCRAFT_AGENTS.size(), MINDCRAFT_AGENTS);
        } catch (Throwable t) {
            NumenResident.LOGGER.warn("[resident] 名册配置读取失败: {}", t.toString());
        }
    }

    /**
     * 根据当前在线玩家重建名册并落盘。
     *
     * <p>身份判定顺序：
     * <ol>
     *   <li>{@code NumenPlayer} 实例 → {@code numen}</li>
     *   <li>名字在 Mindcraft 名单里 → {@code mindcraft}</li>
     *   <li>其余 → {@code human}</li>
     * </ol>
     */
    static void write(MinecraftServer server) {
        Path root = ResidentStore.root();
        if (root == null) return;
        // 写之前先看配置有没有被改过（热重载）——改了立即生效，不必重启服务端
        if (configFile != null) {
            loadConfig(configFile.getParent());
        }
        try {
            JsonObject out = new JsonObject();
            out.addProperty("updated", System.currentTimeMillis());
            out.addProperty("_comment",
                    "本文件由 numen-resident 维护。只记录身份与类别，不含任何关系标签。");

            JsonArray arr = new JsonArray();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                String name = p.getGameProfile().getName();
                String type = classify(p, name);
                JsonObject o = new JsonObject();
                o.addProperty("name", name);
                o.addProperty("type", type);
                o.addProperty("uuid", p.getUUID().toString());
                arr.add(o);
            }
            out.add("agents", arr);

            Files.createDirectories(root);
            Path target = root.resolve(FILE);
            // 原子写：Mindcraft 是另一个进程，可能正好在读——不能让它读到半个文件
            Path tmp = root.resolve(FILE + ".tmp");
            Files.writeString(tmp, out.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            NumenResident.LOGGER.warn("[resident] 名册写入失败: {}", t.toString());
        }
    }

    private static String classify(ServerPlayer p, String name) {
        // Numen 的同伴是 ServerPlayer 的子类，这一条能可靠区分
        if (p.getClass().getName().contains("NumenPlayer")) return "numen";
        if (MINDCRAFT_AGENTS.contains(name)) return "mindcraft";
        return "human";
    }

    /** 当前名册快照（给日志/调试用）。 */
    static List<String> describe(MinecraftServer server) {
        List<String> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            out.add(p.getGameProfile().getName() + ":" + classify(p, p.getGameProfile().getName()));
        }
        return out;
    }
}
