package com.dsh.numenorchestrator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Numen 调用的<b>隔离层</b>。
 *
 * <h2>为什么单独一个类</h2>
 * 本 mod 引用的 {@code AgentLoopRegistry} / {@code EntityAgentLoop} 住在 Numen 的
 * jarjar 内嵌 jar 里。如果类加载链接失败（Numen 没装、版本对不上、加载器隔离），
 * <b>失败会发生在"第一次用到这个类"的时候</b>。
 *
 * <p>所以把所有对 Numen 的引用集中到这里：{@link Orchestrator} 只通过本类的静态方法
 * 间接触碰 Numen。这样最坏情况是<b>本类</b>抛 {@code NoClassDefFoundError}，
 * 被调用方 catch 住，而不是在构造 Orchestrator 时就炸掉客户端。
 *
 * <p><b>注意</b>：这里刻意不使用泛型/继承等会触发早期链接的写法，
 * 所有 Numen 类型都写在方法体内部。
 */
final class NumenBridge {

    private NumenBridge() {}

    /** 本 mod 是否已确认能访问 Numen（第一次成功调用后置位）。 */
    private static volatile Boolean available = null;

    static boolean isAvailable() {
        if (available == null) {
            try {
                // 只碰一个最轻的静态方法作为探针
                listLoaded();
                available = Boolean.TRUE;
            } catch (Throwable t) {
                Orchestrator.LOG.info("[orch] Numen 不可用（软依赖，功能停用）: {}", t.toString());
                available = Boolean.FALSE;
            }
        }
        return available;
    }

    /** 所有已加载 loop 的 UUID。失败抛 Throwable。 */
    static List<UUID> listLoaded() {
        return com.dwinovo.numen.client.agent.AgentLoopRegistry.loadedEntityUuids();
    }

    /** 一只同伴的目标是否存在。返回 null = 查不到（同伴不在/出错了）。 */
    static Boolean hasGoal(UUID uuid) {
        try {
            var opt = com.dwinovo.numen.client.agent.AgentLoopRegistry.get(uuid);
            if (opt.isEmpty()) return null;
            return opt.get().goal() != null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 一只同伴是否忙。null = 查不到。 */
    static Boolean isBusy(UUID uuid) {
        try {
            var opt = com.dwinovo.numen.client.agent.AgentLoopRegistry.get(uuid);
            if (opt.isEmpty()) return null;
            return opt.get().isBusy();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 是否还在等待 LLM 回应（避免在它思考时插队）。 */
    static Boolean goalLive(UUID uuid) {
        return hasGoal(uuid);
    }

    /**
     * 把一句话作为"主人说的"交给同伴。
     *
     * <p>走的是 {@code submitPrompt}——和主人在聊天框里打字<b>同一条路</b>。
     * 本 mod 刻意不碰 {@code setGoal}：目标是它自己产生的，不是我们设的。
     *
     * @return true = 已接受（false 或异常 = 没送进去）
     */
    static boolean submitSituation(UUID uuid, String text) {
        try {
            var opt = com.dwinovo.numen.client.agent.AgentLoopRegistry.get(uuid);
            if (opt.isEmpty()) return false;
            opt.get().submitPrompt(text);
            return true;
        } catch (Throwable t) {
            Orchestrator.LOG.warn("[orch] submitPrompt 失败 {}: {}", uuid, t.toString());
            return false;
        }
    }

    /**
     * 推一个<b>事件</b>给同伴（走 EventQueue，不是主人对话通道）。
     *
     * <h2>与 {@link #submitSituation} 的区别——这是本项目的关键区分</h2>
     * <table border="1">
     *   <tr><th></th><th>submitPrompt</th><th>pushEvent</th></tr>
     *   <tr><td>通道</td><td><b>主人说话</b></td><td>世界事件</td></tr>
     *   <tr><td>占用主人窗口</td><td><b>是</b></td><td>否</td></tr>
     *   <tr><td>唤醒它开轮</td><td>是</td><td>看 urgent</td></tr>
     * </table>
     *
     * <p>一般化场景里 {@code submitPrompt} 应当完全不用——那是主人的位置。
     * 只有 {@code urgent} 的消息才值得唤醒它，走这条路。
     *
     * @param type  事件类型标签，会出现在它的 {@code <event>} 里
     * @param text  事件内容。只写事实，不写"你应该回应"
     * @param urgent 是否打断当前回合
     * @return true = 已入队
     */
    static boolean pushEvent(UUID uuid, String type, String text, boolean urgent) {
        try {
            var opt = com.dwinovo.numen.client.agent.AgentLoopRegistry.get(uuid);
            if (opt.isEmpty()) return false;
            // ts 传 0 → Numen 自己填当前时间（见它的字节码：lconst_0 比较后取 currentTimeMillis）
            opt.get().pushEvent(type, text, 0L, urgent);
            return true;
        } catch (Throwable t) {
            Orchestrator.LOG.warn("[orch] pushEvent 失败 {}: {}", uuid, t.toString());
            return false;
        }
    }

    /** 把事件推给除 {@code exceptName} 之外的所有同伴。 */
    static List<String> pushEventToOthers(String exceptName, String type, String text, boolean urgent) {
        List<String> sent = new ArrayList<>();
        List<UUID> uuids;
        try {
            uuids = listLoaded();
        } catch (Throwable t) {
            return sent;
        }
        if (uuids == null) return sent;
        for (UUID u : uuids) {
            String n = displayName(u);
            if (n != null && n.equals(exceptName)) continue;   // 不推给说话的人自己
            if (pushEvent(u, type, text, urgent)) sent.add(n);
        }
        return sent;
    }

    /** 同伴名字（取不到就回退到 uuid 前 8 位）。 */
    static String displayName(UUID uuid) {
        // 优先从实体拿真名。实体可能暂不可解析（刚生成 / 不在视野），
        // 所以加一层缓存——名字是稳定的，缓存住就不会退化成 uuid。
        String cached = nameCache.get(uuid);
        if (cached != null) return cached;

        try {
            var player = com.dwinovo.numen.client.agent.ClientNumenLookup.resolve(uuid);
            if (player != null) {
                String n = player.getName().getString();
                if (n != null && !n.isBlank()) {
                    nameCache.put(uuid, n);
                    return n;
                }
            }
        } catch (Throwable ignored) { }

        // 退路：从名册文件读（服务端的 numen-resident 维护的，含 uuid → name）
        String fromRoster = nameFromRoster(uuid);
        if (fromRoster != null) {
            nameCache.put(uuid, fromRoster);
            return fromRoster;
        }
        return uuid.toString().substring(0, 8);
    }

    private static final Map<UUID, String> nameCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 从名册文件按 uuid 找名字。 */
    private static String nameFromRoster(UUID uuid) {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null) return null;
            Path p = mc.gameDirectory.toPath()
                    .resolve("config").resolve("numen").resolve("companions")
                    .resolve(uuid.toString()).resolve("binding.json");
            // binding.json 里没有名字；名字只能从实体或服务端名册拿。
            // 服务端名册在存档里，客户端不一定能读到——所以这里只做兜底：
            // 尝试读我们自己的 speech 目录旁的 agents.json
            Path roster = Path.of(rosterPathHint);
            if (!Files.isRegularFile(roster)) return null;
            String all = Files.readString(roster);
            int i = all.indexOf(uuid.toString());
            if (i < 0) return null;
            // 往回找最近的 "name":"..."
            int j = all.lastIndexOf("\"name\"", i);
            if (j < 0) return null;
            int c = all.indexOf(':', j);
            int q1 = all.indexOf('"', c + 1);
            int q2 = all.indexOf('"', q1 + 1);
            if (q1 < 0 || q2 < 0) return null;
            return all.substring(q1 + 1, q2);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String rosterPathHint = "";
    static void setRosterPath(String p) { if (p != null) rosterPathHint = p; }

    /** 当前目标正文（null = 无目标）。仅用于日志观察。 */
    static String goalText(UUID uuid) {
        try {
            var opt = com.dwinovo.numen.client.agent.AgentLoopRegistry.get(uuid);
            if (opt.isEmpty()) return null;
            var g = opt.get().goal();
            return g == null ? null : g.objective();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 同伴<b>自己</b>的坐标。
     *
     * <p>为什么要单独一个方法：处境文本里如果填了观察者的坐标，就是<b>喂假消息</b>——
     * 同伴被告知"你在 X"，而它实际在别处，它会照着错误前提做决策，而且不会察觉。
     * 宁可这一项缺失，也不能填错。
     *
     * <p>取不到（同伴不在客户端视野内 / 已被移除）时返回 {@code null}，
     * 调用方应<b>整段省略坐标</b>，而不是回退到别的坐标。
     */
    static int[] positionOf(UUID uuid) {
        try {
            var player = com.dwinovo.numen.client.agent.ClientNumenLookup.resolve(uuid);
            if (player == null) return null;
            return new int[]{
                    (int) Math.floor(player.getX()),
                    (int) Math.floor(player.getY()),
                    (int) Math.floor(player.getZ())
            };
        } catch (Throwable t) {
            return null;
        }
    }

    /** 空列表常量，避免调用方 null 判断。 */
    static final List<UUID> NONE = new ArrayList<>();

    /**
     * 发一句聊天（<b>调试注入用</b>）。
     *
     * <p>走 {@code ClientPacketListener.sendChat(String)} —— 也就是<b>真实聊天包</b>，
     * 与玩家按回车发送完全同一条路径。这一点很关键：
     * 实测发现服务端的 {@code /say} 是系统广播（发送者 {@code [Server]}），
     * <b>不会</b>触发 Mindcraft 的 {@code chat} 事件；而真实聊天包会。
     *
     * <h2>为什么不能"以同伴身份"发</h2>
     * 聊天包由<b>本地玩家</b>的连接发出，服务端会校验发送者身份。
     * {@code AbstractClientPlayer} 上没有 {@code connection} 字段——
     * 每个客户端只有一个连接，属于本机玩家。
     * 所以要注入同伴的话，只能让它自己调工具说（那是它的自由意志），
     * 或者从服务端用 RCON（但那是系统广播，Mindcraft 收不到）。
     *
     * <p>因此本方法以<b>本机玩家</b>身份发言。对验证链路来说这已经够了：
     * 它能确认"真实聊天包能否到达 Mindcraft"，以及"Mindcraft 回话时 Numen 能否听到"。
     *
     * @param who  仅用于日志标注（实际由本机玩家发出）
     * @return true = 已发出
     */
    static boolean sendChatAs(UUID who, String text) {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            var conn = mc.getConnection();
            if (conn == null) {
                Orchestrator.LOG.warn("[orch] 调试注入失败：客户端未连接");
                return false;
            }
            conn.sendChat(text);
            return true;
        } catch (Throwable t) {
            Orchestrator.LOG.warn("[orch] 调试注入失败: {}", t.toString());
            return false;
        }
    }

    /** 按名字找同伴 UUID。找不到返回 null。 */
    static UUID findByName(String name) {
        try {
            for (UUID u : listLoaded()) {
                var player = com.dwinovo.numen.client.agent.ClientNumenLookup.resolve(u);
                if (player != null && name.equalsIgnoreCase(player.getName().getString())) {
                    return u;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    // ─────────────────────────────────────────────────────────
    // 读同伴说过的话（用于把它转发到公开聊天）
    //
    // 为什么走文件而不是 EntityAgentLoop.display()：
    //   实测 `ConvoState$Msg` 是一个**空接口**（`public interface ConvoState$Msg {}`），
    //   没有任何公共方法可读，所以拿不到文本。而 chat.jsonl 的格式我们自己控制、
    //   已经验证过：每行一个 JSON，含 "role" 与 "content"。
    //   轮询文件的代价可以接受（每几秒读一次小文件）。
    // ─────────────────────────────────────────────────────────

    /** 记住每只同伴已经转发到第几行，避免重复。 */
    private static final Map<UUID, Integer> lastReadLine = new java.util.HashMap<>();

    /**
     * 取某只同伴<b>新说</b>的话（上次之后新增的 assistant 文本）。
     *
     * @return 新增的发言列表；读不到返回空列表
     */
    static List<String> newAssistantLines(UUID uuid) {
        List<String> out = new ArrayList<>();
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null) return out;

            // 与 CompanionHome 的布局一致：<gamedir>/config/numen/companions/<uuid>/chat.jsonl
            Path p = mc.gameDirectory.toPath()
                    .resolve("config").resolve("numen").resolve("companions")
                    .resolve(uuid.toString()).resolve("chat.jsonl");
            if (!Files.isRegularFile(p)) return out;

            List<String> lines = Files.readAllLines(p);
            int from = lastReadLine.getOrDefault(uuid, Math.max(0, lines.size() - 1));
            if (lines.size() <= from) return out;

            for (int i = from; i < lines.size(); i++) {
                String s = lines.get(i);
                if (s == null || s.isBlank()) continue;
                // 只认 assistant 行。用简单包含判断而不是完整 JSON 解析——
                // 这文件是流式追加的，最后一行可能是半截，解析会抛。
                if (!s.contains("\"role\":\"assistant\"")) continue;
                String text = extractContent(s);
                if (text != null && !text.isBlank()) out.add(text);
            }
            lastReadLine.put(uuid, lines.size());
        } catch (Throwable t) {
            Orchestrator.LOG.debug("[orch] 读同伴发言失败 {}: {}", uuid, t.toString());
        }
        return out;
    }

    /** 从一行 JSON 里抠出 "content" 的值。够用就行，不引 JSON 库。 */
    private static String extractContent(String line) {
        int i = line.indexOf("\"content\"");
        if (i < 0) return null;
        i = line.indexOf(':', i);
        if (i < 0) return null;
        i++;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) i++;
        if (i >= line.length() || line.charAt(i) != '"') return null;
        i++;
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (; i < line.length(); i++) {
            char c = line.charAt(i);
            if (esc) {
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(c);
                }
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;   // 没收尾，说明是半截行
    }

    /** 供转发去重用。 */
    static String nameOf(UUID uuid) {
        return displayName(uuid);
    }

    /**
     * 召唤一个新同伴。
     *
     * <p>走官方 {@code NumenActuator.create(String name)}——这是**客户端** API，
     * 而 orchestrator 就跑在客户端，所以能直接用。
     *
     * <p>语义上：新同伴的<b>主人是执行创建的那个客户端</b>，
     * 所以由我们的客户端造出来的同伴，大脑也会跑在这里。
     *
     * @return 描述结果的一行文本（给日志/工具回执用）
     */
    static String createCompanion(String name) {
        if (name == null || name.isBlank()) return "需要一个名字";
        String n = name.strip();
        if (!n.matches("[A-Za-z0-9_]{1,16}")) {
            return "名字不合法（只能字母/数字/下划线，1-16 字符）";
        }
        try {
            Boolean ok = com.dwinovo.numen.api.NumenActuator.create(n).get(20, java.util.concurrent.TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(ok)) {
                return "已召唤 " + n;
            }
            return "召唤 " + n + " 失败（返回 " + ok + "）";
        } catch (Throwable t) {
            return "召唤 " + n + " 出错: " + t;
        }
    }

    /** 当前所有同伴的 (uuid, name)。 */
    static List<String> companionNames() {
        List<String> out = new ArrayList<>();
        try {
            for (UUID u : listLoaded()) {
                out.add(displayName(u));
            }
        } catch (Throwable ignored) { }
        return out;
    }
}
