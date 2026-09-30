package com.dsh.numenorchestrator;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Numen Orchestrator —— 让观察者既不用手动召唤同伴，也不用替它发言。
 *
 * <h2>两件事</h2>
 * <ol>
 *   <li><b>自动召唤</b>：进服后自动发 {@code /numen player summon <名字>}。
 *       顺序很重要——{@code NumenOwner} 是执行召唤的人，所以必须由<b>这个客户端</b>召唤，
 *       同伴的大脑才会跑在这里。</li>
 *   <li><b>目标自续</b>：轮询 {@code goal.json}。它消失 = 上一个目标结束
 *       （达成/卡住/额度耗尽，三条路都是 {@code clearGoal} 删文件）。
 *       这时<b>只注入客观处境</b>，让同伴自己产生下一个目标。</li>
 * </ol>
 *
 * <h2>设计纪律（最重要的一条）</h2>
 * <b>绝不替同伴写目标。</b> 本 mod 不做 {@code setGoal}，只做 {@code submitPrompt}。
 * 喂进去的是<b>事实</b>（你还活着、背包里有什么、天亮了），不是祈使句。
 * "下一个目标是什么"这个判断必须由它自己做——否则我们又变成了替它发言，
 * 只是换了个人来替。
 *
 * <h2>为什么不用反射</h2>
 * {@code AgentLoopRegistry} 与 {@code EntityAgentLoop} 的相关方法都是 public：
 * <pre>
 * AgentLoopRegistry.loadedEntityUuids()          → 所有已加载的 loop
 * AgentLoopRegistry.get(uuid)                    → 只读取 loop
 * EntityAgentLoop.goal()                         → 当前目标（null = 无）
 * EntityAgentLoop.setGoal(GoalState, String)     → 设目标（本 mod 故意不用）
 * EntityAgentLoop.clearGoal(String)              → 清目标
 * EntityAgentLoop.submitPrompt(String)           → 主人说话那条路
 * EntityAgentLoop.isBusy()                       → 忙不忙
 * </pre>
 */
@Mod(Orchestrator.MODID)
public class Orchestrator {

    public static final String MODID = "numenorchestrator";
    static final Logger LOG = LogUtils.getLogger();

    // ---- 配置 ----
    private static boolean enabled = true;
    private static boolean autoSummon = true;
    private static int summonDelayTicks = 100;      // 等客户端稳定
    private static int pollIntervalTicks = 100;     // 轮询周期（5 秒）
    private static int settleTicks = 40;            // 出生处境前的等待，避开出生瞬间的忙碌
    private static int idleTimeoutTicks = 6000;     // 兜底阈值（5 分钟无活动才叫醒）

    // ── 调试注入（默认关闭）──
    // 用途：不用等自然行为就能验证通信链路。
    // 走真实聊天包（ClientPacketListener.sendChat），与玩家按回车同一条路径。
    private static boolean debugSayEnabled = false;
    private static int debugSayAfterTicks = 1200;   // 进服后多久发（默认 60 秒）
    private static String debugSayAs = "";          // 以谁的同伴身份发
    private static String debugSayText = "";        // 内容
    private static boolean debugSayDone = false;

    /** 是否把同伴说的话发给世界。默认开——不开的话它们在隔音玻璃后面说话。 */
    private static boolean relaySpeech = true;
    /** 共享发言流路径（配置项，默认指向服务端存档内）。 */
    /** 共享发言流路径（配置项，默认指向服务端存档内）。 */
    private static String speechPathOverride = "";

    /** providers.json 里的配置 id。召唤前挂到同伴名字上，否则它开不了轮。 */
    private static String providerId = "prov-deepseek";

    /** 跨会话记忆（按名字存）。默认开。 */
    private static boolean memoryEnabled = true;
    /** 记忆文件路径。 */
    private static String memoryPath = "";
    private static final List<String> companions = new ArrayList<>();
    /** 开场时要遣散的旧同伴名字（清一次就够，之后请清空本项）。 */
    private static final List<String> removeOnStart = new ArrayList<>();
    private static String situationTemplate =
            "目标结束了。你还活着。";

    // ---- 运行时 ----
    private static boolean configLoaded = false;
    private static boolean summoningDone = false;
    private static boolean summoningStarted = false;
    private static int waitTicks = 0;
    private static int pollCounter = 0;

    /** 每只同伴的观察状态。 */
    private static final class Watch {
        /** 已经给过出生处境了吗——只给一次。 */
        boolean born = false;
        /** 连续"既不忙、也没有目标"的轮询次数。 */
        int idlePolls = 0;
        /** 上一次看到的 activity，用来判断有没有变化（仅日志用）。 */
        String lastActivity = null;
    }
    private static final Map<UUID, Watch> watches = new LinkedHashMap<>();

    public Orchestrator() {
        MinecraftForge.EVENT_BUS.register(this);
        LOG.info("[orch] 已加载");
    }

    // ─────────────────────────────────────────────────────────
    // 配置
    // ─────────────────────────────────────────────────────────

    private static void loadConfig() {
        if (configLoaded) return;
        configLoaded = true;
        try {
            Path cfg = FMLPaths.CONFIGDIR.get().resolve("orchestrator.properties");
            if (!Files.exists(cfg)) {
                Files.createDirectories(cfg.getParent());
                Files.write(cfg, List.of(
                        "# Numen Orchestrator 配置",
                        "#",
                        "# companions: 要自动召唤的同伴名单（逗号分隔，留空则不召唤）",
                        "# 这是实验的自变量，由你决定。",
                        "companions=",
                        "",
                        "# 开场时先遣散的旧同伴（逗号分隔）。清一次就够，之后把这里清空。",
                        "# 遣散由服务端执行，Numen 会自动删掉它在本地的目录。",
                        "remove_on_start=",
                        "",
                        "# 自动召唤开关",
                        "auto_summon=true",
                        "# 进服后等多少 tick 再召唤（100 tick = 5 秒）",
                        "summon_delay_ticks=100",
                        "",
                        "# 目标消失后注入的【客观处境】。",
                        "# 纪律：这里只写事实，不要写祈使句、不要建议它做什么。",
                        "# 可用占位符：{name} {x} {y} {z} {day} {time_of_day}",
                        "situation=目标结束了。你还活着。天{time_of_day}。你在 {x} {y} {z}。",
                        "",
                        "# 轮询周期（tick）。100 = 5 秒",
                        "poll_interval_ticks=100",
                        "# 出生处境前等多少 tick（避开出生瞬间的忙碌）",
                        "settle_ticks=40",
                        "",
                        "# 兜底叫醒阈值（tick）。",
                        "# Numen 本身靠 task_finished 事件自续，正常情况下不该触发这里；",
                        "# 只有链条断了（任务失败/事件丢失/它卡住）才会用到。",
                        "# 默认 6000 tick = 5 分钟无活动。",
                        "idle_timeout_ticks=6000",
                        "",
                        "# ── 调试注入（默认关闭）──",
                        "# 用途：不用等 agent 自发开口，就能验证通信链路。",
                        "# 走真实聊天包（与玩家按回车同一条路径），所以 Mindcraft 收得到。",
                        "# 注意：服务端的 /say 是系统广播，Mindcraft 的 chat 事件收不到。",
                        "debug_say_enabled=false",
                        "# 进服后延迟多少 tick 再发（1200 = 60 秒）",
                        "debug_say_after_ticks=1200",
                        "# 以哪个同伴的身份发",
                        "debug_say_as=Lin",
                        "# 内容",
                        "debug_say_text=(To Andy) do you know where any wood is? I have found nothing."
                ));
                LOG.info("[orch] 写了默认配置 {}", cfg);
            }
            for (String raw : Files.readAllLines(cfg)) {
                String s = raw.strip();
                if (s.isEmpty() || s.startsWith("#")) continue;
                int eq = s.indexOf('=');
                if (eq < 0) continue;
                String k = s.substring(0, eq).strip();
                String v = s.substring(eq + 1).strip();
                switch (k) {
                    case "enabled" -> enabled = Boolean.parseBoolean(v);
                    case "auto_summon" -> autoSummon = Boolean.parseBoolean(v);
                    case "summon_delay_ticks" -> summonDelayTicks = parseInt(v, 100);
                    case "poll_interval_ticks" -> pollIntervalTicks = Math.max(20, parseInt(v, 100));
                    case "settle_ticks" -> settleTicks = Math.max(0, parseInt(v, 40));
                    case "idle_timeout_ticks" -> idleTimeoutTicks = Math.max(200, parseInt(v, 6000));
                    case "debug_say_enabled" -> debugSayEnabled = Boolean.parseBoolean(v);
                    case "debug_say_after_ticks" -> debugSayAfterTicks = Math.max(100, parseInt(v, 1200));
                    case "debug_say_as" -> debugSayAs = v;
                    case "debug_say_text" -> debugSayText = v;
                    case "relay_speech" -> relaySpeech = Boolean.parseBoolean(v);
                    case "speech_path" -> speechPathOverride = v;
                    case "roster_path" -> NumenBridge.setRosterPath(v);
                    case "provider_id" -> providerId = v;
                    case "memory_enabled" -> memoryEnabled = Boolean.parseBoolean(v);
                    case "memory_path" -> {
                        memoryPath = v;
                        Memory.bindPath(v);
                    }
                    case "heard_path" -> ClientChatRelay.setQueuePath(v);
                    case "situation" -> { if (!v.isBlank()) situationTemplate = v; }
                    case "companions" -> {
                        companions.clear();
                        for (String n : v.split(",")) {
                            String t = n.strip();
                            if (!t.isEmpty()) companions.add(t);
                        }
                    }
                    case "remove_on_start" -> {
                        removeOnStart.clear();
                        for (String n : v.split(",")) {
                            String t = n.strip();
                            if (!t.isEmpty()) removeOnStart.add(t);
                        }
                    }
                    default -> { /* 未知键忽略 */ }
                }
            }
            LOG.info("[orch] 配置: enabled={} auto_summon={} companions={} situation=\"{}\"",
                    enabled, autoSummon, companions, situationTemplate);
        } catch (Throwable t) {
            LOG.warn("[orch] 读配置失败，用默认值: {}", t.toString());
        }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.strip()); } catch (Exception e) { return def; }
    }

    // ─────────────────────────────────────────────────────────
    // 主循环
    // ─────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) return;   // 还没进世界

        loadConfig();
        if (!enabled) return;

        waitTicks++;

        // ── 1. 开场：先遣散旧的，再召唤新的（只做一次）──
        if (autoSummon && !summoningStarted && waitTicks >= summonDelayTicks) {
            summoningStarted = true;
            despawnOld(mc);
            if (companions.isEmpty()) {
                LOG.info("[orch] 同伴名单为空，不召唤（在 config/orchestrator.properties 里填）");
            } else {
                summonAll(mc);
                summoningDone = true;
            }
        }

        // ── 2. 目标自续（周期性）──
        if (++pollCounter < pollIntervalTicks) return;
        pollCounter = 0;
        renewGoals(mc);
        maybeDebugSay();
        relayCompanionSpeech(mc);
        pushUrgentSpeech(mc);
        saveMemories(mc);
    }

    // ── 紧急推送 ──

    /** 已经推过的紧急消息（按 seq 记，避免重复推）。 */
    private static final java.util.Set<Long> pushedUrgent = new java.util.LinkedHashSet<>();

    /**
     * 把 {@code urgent} 的发言推成<b>事件</b>，唤醒同伴。
     *
     * <h2>为什么只有紧急的才推</h2>
     * 普通发言走<b>拉</b>（同伴自己调 {@code hear()}）——不唤醒、不占窗口、不烧 token。
     * 但有些事等不了："我被僵尸围了"几分钟后才被听见就太晚了。
     *
     * <p>所以：<b>普通聊天进队列，紧急的推事件</b>。
     * 而"急不急"由<b>说话的人自己判断</b>（它调 {@code say(text, urgent=true)}）——
     * 我们不替它定义什么算紧急。
     *
     * <h2>为什么走 pushEvent 而不是 submitPrompt</h2>
     * {@code submitPrompt} 是<b>主人说话</b>的通道，会被记进主人和助手的对话历史。
     * 别的 AI 的呼喊不该出现在那里。{@code pushEvent} 走 EventQueue，是"世界发生的事"。
     */
    private static void pushUrgentSpeech(Minecraft mc) {
        if (!relaySpeech) return;
        Path f = speechPath();
        if (f == null) return;
        try {
            if (!Files.isRegularFile(f)) return;
            long last = urgentCursor;
            long size = Files.size(f);
            if (size <= last) {
                if (size < last) urgentCursor = 0;   // 文件被截断/换存档
                return;
            }
            // 从上次位置往后读新增部分
            String chunk;
            try (var in = Files.newInputStream(f)) {
                in.skip(last);
                chunk = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            urgentCursor = size;

            for (String raw : chunk.split("\n")) {
                String s = raw.strip();
                if (s.isEmpty() || !s.contains("\"urgent\":true")) continue;
                long seq = longField(s, "seq", -1);
                if (seq >= 0 && pushedUrgent.contains(seq)) continue;
                String name = strField(s, "name");
                String text = strField(s, "text");
                if (name == null || text == null) continue;
                if (seq >= 0) {
                    pushedUrgent.add(seq);
                    if (pushedUrgent.size() > 500) {
                        var it = pushedUrgent.iterator();
                        it.next();
                        it.remove();
                    }
                }
                List<String> sent = NumenBridge.pushEventToOthers(name, "urgent_speech",
                        name + " 说：" + text, true);
                if (!sent.isEmpty()) {
                    LOG.info("[orch] 紧急推送（{}）: {} → {}", name,
                            text.length() > 50 ? text.substring(0, 50) + "…" : text, sent);
                }
            }
        } catch (Throwable t) {
            LOG.debug("[orch] 紧急推送异常: {}", t.toString());
        }
    }

    private static long urgentCursor = 0;

    /** 从一行 JSON 里抠出数字字段。够用就行，不引 JSON 库。 */
    private static long longField(String line, String key, long dflt) {
        int k = line.indexOf("\"" + key + "\":");
        if (k < 0) return dflt;
        int e = k + key.length() + 3;
        int st = e;
        while (e < line.length() && (Character.isDigit(line.charAt(e)) || line.charAt(e) == '-')) e++;
        if (e == st) return dflt;
        try { return Long.parseLong(line.substring(st, e)); } catch (Throwable t) { return dflt; }
    }

    /** 从一行 JSON 里抠出字符串字段（只处理简单转义）。 */
    private static String strField(String line, String key) {
        int k = line.indexOf("\"" + key + "\":\"");
        if (k < 0) return null;
        int i = k + key.length() + 4;
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
        return null;
    }

    /**
     * 周期性把同伴当前的状态存成记忆（按名字）。
     *
     * <p>为什么不在退出时存：客户端可能被直接关掉、崩溃、或断线，
     * 那时没有机会执行"退出钩子"。<b>只有定期存才靠得住。</b>
     *
     * <p>存的是它自己最近说过的话——保留它的判断和立场，
     * 而不是我们替它总结的要点（见 {@link Memory#summarizeFromChat}）。
     */
    private static void saveMemories(Minecraft mc) {
        if (!memoryEnabled || memoryPath.isBlank()) return;
        if (!NumenBridge.isAvailable()) return;
        List<UUID> uuids;
        try {
            uuids = NumenBridge.listLoaded();
        } catch (Throwable t) {
            return;
        }
        if (uuids == null) return;

        for (UUID uuid : uuids) {
            String name = NumenBridge.displayName(uuid);
            if (name == null || name.isBlank() || name.length() > 16) continue;   // 名字还没解析出来
            String s = Memory.summarizeFromChat(uuid, 600);
            if (s.isBlank()) continue;
            // 只在内容真的变了才写盘，避免每 5 秒重写一次文件
            if (!s.equals(Memory.recall(name))) {
                Memory.remember(name, s);
            }
        }
    }

    /**
     * 把同伴说的话转发到公开聊天。
     *
     * <h2>为什么需要</h2>
     * 实测发现 Numen 的同伴用<b>普通文本</b>回应，而普通文本走
     * {@code TurnPresenter.streamToChat()} → 客户端 {@code ChatComponent.addMessage}
     * ——<b>只显示给主人看，不进公开聊天</b>。
     *
     * <p>后果：同伴能听见 Andy 说话、会思考、会讨论他的观点（实测它甚至说
     * "Andy. Stop for a second."），但<b>Andy 一个字都收不到</b>。
     * 等于它们在隔音玻璃后面说话。
     *
     * <h2>为什么不改提示词</h2>
     * 让同伴"主动用 say 工具"属于硬引导——那是规定它该怎么表达。
     * 这里只是让"说话"这件事真的被听见：<b>它想说什么仍然完全由它自己决定</b>，
     * 我们只是不再把它的声音关掉。这与"真实世界里人说话对方就该听得见"一致。
     */
    /**
     * 把同伴说的话发给世界（<b>方案 D + A</b>）。
     *
     * <h2>为什么不再用 sendChat 转发</h2>
     * 早期做法是 {@code mc.getConnection().sendChat(text)} 直接发进公开聊天。
     * 但聊天包只能由<b>本机连接</b>发出，服务端校验后记的是<b>本机玩家的名字</b>：
     * <pre>
     * [Not Secure] &lt;a1206122381&gt; Andy. Stop for a second. ...
     *                  ↑ 本该是 &lt;Wei&gt;
     * </pre>
     * 结果是<b>伪造了身份</b>——同伴的话看起来是观察者说的。
     * 而且同伴自己也收不到（被"是本机玩家说的"过滤掉），形成单向哑铃。
     *
     * <h2>现在的做法：写共享发言流</h2>
     * 写进 {@code <服务端存档>/numenresident/speech.jsonl}，一行一条：
     * <pre>
     * {"t":1790...,"name":"Wei","text":"Andy. Stop for a second."}
     * </pre>
     * <b>身份是真的</b>（我们知道是谁说的），不需要伪造任何东西。
     * Mindcraft 侧轮询这个文件即可看见同伴的发言。
     *
     * <p>代价：它不是游戏内聊天，所以不会出现在玩家聊天框里。
     * 但这是<b>诚实的代价</b>——比伪造身份好。
     */
    private static void relayCompanionSpeech(Minecraft mc) {
        if (!relaySpeech) return;
        if (!NumenBridge.isAvailable()) return;
        List<UUID> uuids;
        try {
            uuids = NumenBridge.listLoaded();
        } catch (Throwable t) {
            return;
        }
        if (uuids == null) return;

        for (UUID uuid : uuids) {
            List<String> lines;
            try {
                lines = NumenBridge.newAssistantLines(uuid);
            } catch (Throwable t) {
                continue;
            }
            if (lines == null || lines.isEmpty()) continue;

            String name = NumenBridge.displayName(uuid);
            for (String raw : lines) {
                String text = normalizeSpeech(raw);
                if (text.isEmpty()) continue;
                appendSpeech(name, text);
                // 一次别刷太多
                break;
            }
        }
    }

    /** 同伴的文本可能带换行、命令、或很长，清一清。 */
    private static String normalizeSpeech(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("\\s+", " ").strip();
        if (s.startsWith("<") && s.contains(">")) {
            int i = s.indexOf('>');
            if (i < 60) s = s.substring(i + 1).strip();
        }
        // 丢掉落成"思考过程"的行——内部独白不该被当成对世界说的话。
        if (s.isEmpty()) return "";
        if (s.startsWith("!") || s.contains("!newAction(") || s.contains("!lookAt")
                || s.startsWith("Let me ") || s.startsWith("I need to ")
                || s.contains("executing code") || s.contains("```")) {
            return "";
        }
        // 不要截太短。实测踩过：截到 240 字符会把话切断，
        // 而同伴的发言常常 280-350 字符——切掉后半句会让语义完全变味
        // （有一次是在争论"你到底是谁"，后半句才是重点）。
        // 800 足够容纳一次完整发言，同时挡住病态的极长输出。
        if (s.length() > 800) s = s.substring(0, 800) + "…";
        return s;
    }

    /** 追加一条到共享发言流。原子追加，Mindcraft 是另一个进程。 */
    private static void appendSpeech(String name, String text) {
        try {
            Path dir = speechPath();
            if (dir == null) return;
            Files.createDirectories(dir.getParent());
            String line = "{\"t\":" + System.currentTimeMillis()
                    + ",\"name\":\"" + jsonEsc(name) + "\""
                    + ",\"text\":\"" + jsonEsc(text) + "\"}" + System.lineSeparator();
            Files.writeString(dir, line, java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            LOG.info("[orch] 同伴发言（{}）: {}", name, text);
        } catch (Throwable t) {
            LOG.warn("[orch] 写发言流失败: {}", t.toString());
        }
    }

    private static Path speechFile = null;

    /** 共享发言流的位置。默认指向服务端存档内的 numenresident 目录。 */
    private static Path speechPath() {
        if (speechFile != null) return speechFile;
        if (speechPathOverride != null && !speechPathOverride.isBlank()) {
            speechFile = Path.of(speechPathOverride);
            return speechFile;
        }
        return null;   // 没配就不写
    }

    private static String jsonEsc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /**
     * 调试注入：到点以指定同伴的身份说一句话，然后只做一次。
     *
     * <p>为什么需要它：等 agent 自发开口可能要很久，不利于调试通信链路。
     * 走真实聊天包，所以 Mindcraft 的 {@code chat} 事件能收到（服务端 {@code /say} 收不到）。
     */
    private static void maybeDebugSay() {
        if (!debugSayEnabled || debugSayDone) return;
        if (waitTicks < debugSayAfterTicks) return;
        if (debugSayText.isBlank()) return;

        debugSayDone = true;
        boolean ok = NumenBridge.sendChatAs(null, debugSayText);
        LOG.info("[orch] 调试注入（本机玩家身份发聊天）{}: {}", ok ? "成功" : "失败", debugSayText);
    }

    /** 逐个召唤。用真聊天命令走正常网络路径——服务端命令要在服务端执行。 */
    private static void summonAll(Minecraft mc) {
        LOG.info("[orch] 开始自动召唤 {} 只同伴: {}", companions.size(), companions);
        for (String name : companions) {
            try {
                // 先绑定模型，再召唤。
                //
                // 这一步之前漏了，后果很实在：同伴生成时读不到绑定，providerEntryId
                // 就是 null，于是每次开轮都报"这个同伴还没有绑定模型配置"，
                // 它站在那里一句话都说不出来。后补 binding.json 也没用——
                // 那个字段在生成时就定下了，之后不重读。
                //
                // 官方机制是 ProviderLibrary.pendSummon(name, providerId)：
                // 把"这只同伴该用哪条模型配置"先挂起来，生成时自动取走。
                bindProvider(name);

                // 以玩家身份发出服务端命令
                mc.player.connection.sendCommand("numen player summon " + name);
                LOG.info("[orch] → /numen player summon {}", name);
            } catch (Throwable t) {
                LOG.warn("[orch] 召唤 {} 失败: {}", name, t.toString());
            }
        }
    }

    /** 召唤前把模型配置挂到名字上。取不到就如实报错，不静默失败。 */
    private static void bindProvider(String name) {
        String pid = providerId;
        if (pid == null || pid.isBlank()) {
            LOG.warn("[orch] 未配置 provider_id，{} 生成后会因缺模型配置开不了轮", name);
            return;
        }
        try {
            var lib = com.dwinovo.numen.agent.llm.ProviderLibrary.instance();
            if (lib == null) {
                LOG.warn("[orch] ProviderLibrary 未就绪，{} 可能绑定不上", name);
                return;
            }
            // 先确认这条配置真的存在，免得挂个死 id 上去
            if (lib.get(pid) == null) {
                LOG.warn("[orch] providers.json 里没有 id={}，{} 将无法开轮", pid, name);
                return;
            }
            com.dwinovo.numen.agent.llm.ProviderLibrary.pendSummon(name, pid);
            LOG.info("[orch] 已挂模型配置 {} → {}", pid, name);
        } catch (Throwable t) {
            LOG.warn("[orch] 挂模型配置失败（{}）: {}", name, t.toString());
        }
    }

    /**
     * 遣散旧同伴。
     *
     * <p>同样由客户端发服务端命令——服务端会把它移出名册，
     * 客户端收到名册后 {@code CompanionHome.reconcile} 会自动删掉它的目录（含会话与目标）。
     *
     * <h2>为什么必须先落盘再遣散</h2>
     * 上面那句"自动删掉它的目录"是<b>不可逆</b>的：会话记录（{@code chat.jsonl}）
     * 一旦被删就再也读不到。而记忆摘要正是从那里抽出来的——
     * 所以抽的时机必须在删除之前。
     *
     * <p>周期性的 {@link #saveMemories} 通常已经把摘要存得差不多了，
     * 但"差不多"不够：最后几轮对话可能还没被抽到。
     * 重启前明确落一次盘，代价很小，能保证不丢。
     *
     * <p>实测教训：本项目早期版本就是因为没做这一步，
     * 重启后同伴对话记录被删，记忆全部丢失。
     */
    private static void despawnOld(Minecraft mc) {
        if (removeOnStart.isEmpty()) return;

        // ── 先落盘，再遣散。顺序不能反。──
        for (String name : removeOnStart) {
            try {
                UUID uuid = NumenBridge.findByName(name);
                if (uuid == null) {
                    LOG.warn("[orch] 遣散前落盘：找不到同伴 {}，跳过（它可能本来就不在）", name);
                    continue;
                }
                String s = Memory.summarizeFromChat(uuid, 600);
                if (s.isBlank()) {
                    LOG.info("[orch] 遣散前落盘：{} 没有可抽的内容", name);
                    continue;
                }
                // 无条件写：这是最后一次机会。即使内容和上次一样也无所谓——
                // 一样就说明盘上已经是最新的。
                Memory.remember(name, s);
                LOG.info("[orch] 遣散前落盘 {} 的记忆（{} 字符）", name, s.length());
            } catch (Throwable t) {
                LOG.warn("[orch] 遣散前落盘 {} 失败: {}", name, t.toString());
            }
        }

        LOG.info("[orch] 先遣散旧同伴 {} 只: {}", removeOnStart.size(), removeOnStart);
        for (String name : removeOnStart) {
            try {
                mc.player.connection.sendCommand("numen player despawn " + name);
                LOG.info("[orch] → /numen player despawn {}", name);
            } catch (Throwable t) {
                LOG.warn("[orch] 遣散 {} 失败: {}", name, t.toString());
            }
        }
    }

    /**
     * 目标自续。
     *
     * <p>判据：{@code EntityAgentLoop.goal() == null} 且它不忙。
     * 光看 {@code goal.json} 文件不够——loop 里的 {@code goal} 字段才是真源，
     * 文件只是它的落盘。
     */
    /**
     * 同伴的看护逻辑：<b>出生给一次，之后只在长时间无活动时才兜底叫醒。</b>
     *
     * <h2>为什么不再"每次目标结束都推"</h2>
     * 最初的设计是盯着 {@code goal.json} 消失就注入处境。实测发现那是多余且错误的假设：
     * <b>Numen 本身就自续</b>——同伴派发任务后，服务端做完会推
     * {@code [numen-event] task_finished URGENT → 客户端}，
     * 这条事件自己就会开起新一轮，它接着决定下一步。整条链不需要外部推。
     *
     * <p>而且实测中<b>没有一个同伴用过 {@code /goal}</b>——它们靠对话与事件链行动，
     * {@code goal.json} 从未生成。所以"等目标消失"这个触发条件根本不会发生。
     *
     * <h2>那还留着兜底做什么</h2>
     * 万一某条链断了（任务失败、事件丢失、它自己卡住不动），得有人叫醒它。
     * 但正常运行时<b>绝不该触发</b>——所以阈值放到 {@code idle_timeout_ticks}（默认 5 分钟）。
     */
    private static void renewGoals(Minecraft mc) {
        // 所有 Numen 调用都经 NumenBridge 隔离——链接失败时只死那一个类，不炸客户端。
        if (!NumenBridge.isAvailable()) return;

        List<UUID> uuids;
        try {
            uuids = NumenBridge.listLoaded();
        } catch (Throwable t) {
            return;   // Numen 不在（软依赖）——安静跳过
        }
        if (uuids == null || uuids.isEmpty()) return;

        for (UUID uuid : uuids) {
            Boolean hasGoal = NumenBridge.hasGoal(uuid);
            if (hasGoal == null) continue;          // 查不到，跳过

            Boolean busy = NumenBridge.isBusy(uuid);
            Watch w = watches.computeIfAbsent(uuid, k -> new Watch());

            // ── 出生：刚出现、还没给过出生处境 ──
            if (!w.born) {
                if (Boolean.TRUE.equals(busy)) { w.idlePolls = 0; continue; }
                // 等它能被解析到（进了客户端的实体列表）再开口。
                // 刚 summon 出来的那一刻 resolve() 还是 null，那时候给的处境会缺坐标——
                // 实测出现过一次只有"天快亮。"、没有坐标的出生处境。
                if (NumenBridge.positionOf(uuid) == null) {
                    LOG.debug("[orch] {} 还没进客户端实体列表，出生处境等一下", NumenBridge.displayName(uuid));
                    w.idlePolls = 0;
                    continue;
                }
                w.idlePolls++;
                int need = Math.max(1, settleTicks / Math.max(1, pollIntervalTicks));
                if (w.idlePolls < need) continue;
                w.born = true;
                w.idlePolls = 0;
                feedBirth(uuid);
                continue;
            }

            // ── 看护：忙 / 有目标 → 它自己在跑，什么都不做 ──
            if (Boolean.TRUE.equals(busy) || hasGoal) {
                w.idlePolls = 0;
                continue;
            }

            // ── 兜底：既不忙、也没有目标，持续 idle_timeout_ticks ──
            w.idlePolls++;
            int idleNeed = Math.max(1, idleTimeoutTicks / Math.max(1, pollIntervalTicks));
            if (w.idlePolls < idleNeed) continue;

            w.idlePolls = 0;
            LOG.info("[orch] {} 已 {} tick 无活动，兜底叫醒（正常运行时不应出现）",
                    NumenBridge.displayName(uuid), idleTimeoutTicks);
            feedSituation(uuid);
        }
    }

    /**
     * 把【客观处境】喂回去，让它自己产生下一个目标。
     *
     * <p><b>这里只提交输入，不设目标。</b> 我们给的是事实，不是任务。
     * 它收到之后会自己决定接下来做什么——那才是它的目标。
     */
    private static void feedSituation(UUID uuid) {
        String name = NumenBridge.displayName(uuid);
        String text = buildSituation(uuid);
        boolean ok = NumenBridge.submitSituation(uuid, text);
        if (ok) {
            LOG.info("[orch] 目标结束 → 注入处境给 {}: {}", name, text);
        } else {
            LOG.warn("[orch] 处境注入未送达 {}", name);
        }
    }

    /**
     * 出生处境：同伴刚出现、还没有过任何目标时给一次。
     *
     * <p>同样<b>只给事实</b>——「你刚在这个世界醒来，什么都没有」是处境，不是任务。
     * 给完这一次之后，它接什么目标、做多久，全由它自己决定；
     * 后续每次目标结束都由 {@link #feedSituation} 接上。
     */
    private static void feedBirth(UUID uuid) {
        String name = NumenBridge.displayName(uuid);
        String text = buildBirth(uuid);
        boolean ok = NumenBridge.submitSituation(uuid, text);
        if (ok) {
            LOG.info("[orch] 出生处境 → {}: {}", name, text);
        } else {
            LOG.warn("[orch] 出生处境未送达 {}", name);
        }
    }

    /** 出生处境的文本。只写事实；坐标取<b>同伴自己</b>的。 */
    private static String buildBirth(UUID uuid) {
        Minecraft mc = Minecraft.getInstance();
        StringBuilder sb = new StringBuilder();
        sb.append("你刚在这个世界醒来，什么都没有。");
        sb.append("天").append(timeOfDay(mc)).append("。");
        appendOwnPosition(sb, uuid);
        appendMemory(sb, NumenBridge.displayName(uuid));
        return sb.toString();
    }

    /**
     * 附上跨会话记忆（按名字取）。
     *
     * <h2>为什么按名字而不是 UUID</h2>
     * 每轮重启同伴都是新实体、新 UUID。按 UUID 存 = 每次重生都失忆。
     *
     * <h2>措辞纪律</h2>
     * 只写「你记得这些」，把记忆当成<b>它自己的回忆</b>交还给它——
     * 不是我们给它的任务简报。所以：
     * <ul>
     *   <li>不写"你应该继续做 X"（那是指派任务）</li>
     *   <li>保留它自己的判断和立场（"我认为某人说话不算数"）——
     *       立场丢了人格就断了</li>
     * </ul>
     */
    private static void appendMemory(StringBuilder sb, String name) {
        if (!memoryEnabled) return;
        String m = Memory.recall(name);
        if (m == null || m.isBlank()) return;
        sb.append("你记得这些：").append(m);
    }

    /**
     * 追加同伴<b>自己</b>的坐标。取不到就整段省略——
     * <b>绝不能回退到观察者的坐标</b>，那是喂假消息。
     */
    private static void appendOwnPosition(StringBuilder sb, UUID uuid) {
        int[] p = NumenBridge.positionOf(uuid);
        if (p == null) {
            LOG.debug("[orch] {} 坐标取不到（不在视野内），处境里省略坐标", NumenBridge.displayName(uuid));
            return;
        }
        sb.append("你在 ").append(p[0]).append(' ').append(p[1]).append(' ').append(p[2]).append('。');
    }

    /**
     * 填处境模板。只填事实占位符——不填任何任务描述。
     *
     * <p>坐标用<b>单个</b> {@code {pos}} 占位符，不是 {@code {x} {y} {z}} 三个。
     * 理由：坐标取不到时（同伴不在客户端视野内），三个独立占位符会留下
     * "你在   。" 这种残缺句子，而"你在"是模板里的字、删不掉；
     * 单个占位符连它前面的引导词一起替换，缺失时整段干净消失。
     */
    private static String buildSituation(UUID uuid) {
        Minecraft mc = Minecraft.getInstance();
        String out = situationTemplate;

        out = out.replace("{time_of_day}", timeOfDay(mc));
        out = out.replace("{day}", String.valueOf(safeDay(mc)));

        // 坐标：同伴自己的。取不到就把整个 {pos}（含前面的"你在"之类引导）去掉。
        int[] p = NumenBridge.positionOf(uuid);
        if (p != null) {
            out = out.replace("{pos}", p[0] + " " + p[1] + " " + p[2]);
        } else {
            // 连同引导词一起吃掉，避免留下悬空的"你在"
            out = out.replaceAll("[^。！？\\n]*\\{pos\\}", "");
            out = out.replace("{pos}", "");
            LOG.debug("[orch] {} 坐标取不到，处境里去掉坐标段", NumenBridge.displayName(uuid));
        }
        // 清掉因删段留下的重复标点（"天黑。。" → "天黑。"）
        out = out.replaceAll("([。！？])\\1+", "$1");
        return out.strip();
    }

    private static String timeOfDay(Minecraft mc) {
        try {
            long t = mc.level.getDayTime() % 24000L;
            if (t < 12000L) return "亮";
            if (t < 13800L) return "黑";
            if (t < 22200L) return "夜";
            return "快亮";
        } catch (Throwable t) {
            return "夜";
        }
    }

    private static long safeDay(Minecraft mc) {
        try { return mc.level.getDayTime() / 24000L; } catch (Throwable t) { return 0L; }
    }
}