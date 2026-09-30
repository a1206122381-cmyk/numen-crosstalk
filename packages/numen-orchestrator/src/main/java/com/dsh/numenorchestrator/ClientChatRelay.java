package com.dsh.numenorchestrator;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把别人的话投递给同伴（<b>客户端侧</b>）。
 *
 * <h2>为什么必须在客户端</h2>
 * 同伴的大脑跑在客户端。服务端侧的 {@code NumenApi.enqueue} 在专用服务端上是
 * <b>空操作</b>——{@code NumenGateway} 引用 {@code client/agent}、{@code client/skin}，
 * 而 {@code NumenPlugins.enqueue} 是静态 volatile 字段，只有客户端 {@code bindClient}
 * 之后才有值。
 *
 * <p>实测证据：服务端注入后日志说"已发出"，但同伴的 {@code chat.jsonl} 里一个字都没有。
 *
 * <h2>它做什么</h2>
 * 监听客户端的聊天包（{@code ClientChatReceivedEvent}），把非系统消息转交给同伴的
 * agent loop —— 走 {@code submitPrompt}，与<b>主人在聊天框里打字是同一条路</b>。
 *
 * <h2>它只做一件事：投递，不引导</h2>
 * 只把"谁说了什么"这个<b>事实</b>送进去。要不要理会、怎么回应，是它自己的判断。
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = Orchestrator.MODID)
public final class ClientChatRelay {

    private static final Logger LOG = LogUtils.getLogger();

    /** 匹配 "&lt;名字&gt; 内容" 形态的玩家聊天。 */
    private static final Pattern PLAYER_CHAT = Pattern.compile("^<([A-Za-z0-9_]{1,16})>\\s*(.*)$");

    /** 一次性写入上限，防止超长文本把队列撑坏。 */
    private static final int MAX_TEXT = 400;

    /** 是否启用。默认开。 */
    private static volatile boolean enabled = true;

    /** 队列文件。听到的话都写这里，由同伴自己调 MCP 的 {@code hear()} 来取。 */
    private static Path queueFile = null;

    /** 单调递增的消息序号——游标靠它，不靠行号（见 speech-mcp.mjs 的注释）。 */
    private static long seq = 0;
    private static boolean seqLoaded = false;

    private ClientChatRelay() {}

    static void setEnabled(boolean v) { enabled = v; }
    static void setQueuePath(String p) { if (p != null && !p.isBlank()) queueFile = Path.of(p); }

    @SubscribeEvent
    public static void onChat(ClientChatReceivedEvent event) {
        if (!enabled) return;
        try {
            // 只处理玩家聊天。系统消息（指令反馈、广播等）一律不投递——
            // 它们不是"某个个体在说话"，投进去只会变成噪音。
            if (event.isSystem()) return;

            Component msg = event.getMessage();
            String text = msg.getString();
            if (text == null || text.isBlank()) return;

            Matcher m = PLAYER_CHAT.matcher(text.strip());
            if (!m.matches()) return;   // 不是 "<名字> 内容" 形态，跳过

            String speaker = m.group(1);
            String body = m.group(2);
            if (body.isBlank()) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            // 自己说的话不投递——那会形成回环：
            // 同伴听到自己刚说的话 → 回应 → 又被自己听到。
            if (speaker.equals(mc.player.getName().getString())) return;

            String line = body.length() > MAX_TEXT ? body.substring(0, MAX_TEXT) + "…" : body;

            // 这里**故意没有冷却**。
            //
            // 早期版本有个 1.5 秒冷却，那是给"直接注入"模式用的：那边每投一条
            // 都会唤醒同伴开一轮，不设限就烧 token。但换成队列之后它变成纯粹的损失——
            // 队列是持久的、hear() 自己有上限、没人会被打扰，
            // 而丢掉的消息永远不会再出现。
            //
            // 实测踩过：一段密集对话里连着几句被冷却吃掉，同伴只听到零星片段，
            // 还以为对方说话断断续续（它自己的原话："你的消息又说到一半断了"）。

            deliver(speaker, line);
        } catch (Throwable t) {
            LOG.warn("[orch] 聊天投递异常: {}", t.toString());
        }
    }

    /**
     * 把听到的话排进队列。
     *
     * <h2>为什么这里没有别的分支</h2>
     * 早期版本在这里分了两条路：{@code push} 会调
     * {@code NumenBridge.submitSituation}（→ {@code submitPrompt}）把话直接注进
     * agent loop。那条路已经<b>删除</b>，不是"默认不走"。
     *
     * <p>理由是它<b>同时错了两件事</b>：
     * <ol>
     *   <li><b>占了主人的位置</b>。{@code submitPrompt} 是主人说话的通道，
     *       会被记进主人和助手的对话历史。别的 AI 的闲聊不该出现在那里——
     *       主人说十句话中间插三十句 AI 的对话，这在普通游玩对局里不可接受。</li>
     *   <li><b>而且是多余的</b>。主人自己说的话，Numen 原生就有
     *       {@code <query>} 通道送进同伴，根本不需要我们再注入一次。</li>
     * </ol>
     *
     * <p>所以现在只有队列一条路：<b>写下来，谁想听谁自己来取。</b>
     */
    private static void deliver(String speaker, String line) {
        enqueue(speaker, line);
    }

    /**
     * 把听到的话排进队列（pull 模式）。
     *
     * <p>写一行 JSON，带一个<b>单调递增序号</b>。序号是给游标用的：
     * 行号会因文件重写/截断/末尾换行而错位，序号不会。
     *
     * <p>这里<b>不唤醒任何人</b>——同伴想听的时候自己调 {@code hear()}。
     */
    private static void enqueue(String speaker, String line) {
        if (queueFile == null) return;
        try {
            Files.createDirectories(queueFile.getParent());
            if (!seqLoaded) {
                seq = lastSeq();      // 续上旧文件的序号，别从头开始
                seqLoaded = true;
            }
            String json = "{\"seq\":" + (++seq)
                    + ",\"t\":" + System.currentTimeMillis()
                    + ",\"name\":\"" + jsonEsc(speaker) + "\""
                    + ",\"text\":\"" + jsonEsc(line) + "\"}"
                    + System.lineSeparator();
            Files.writeString(queueFile, json, java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable t) {
            LOG.warn("[orch] 队列写入失败: {}", t.toString());
        }
    }

    /** 读最后一行的 seq，用于续号。 */
    private static long lastSeq() {
        try {
            List<String> all = Files.readAllLines(queueFile, java.nio.charset.StandardCharsets.UTF_8);
            for (int i = all.size() - 1; i >= 0; i--) {
                String s = all.get(i);
                int k = s.indexOf("\"seq\":");
                if (k < 0) continue;
                int e = k + 6;
                while (e < s.length() && Character.isDigit(s.charAt(e))) e++;
                return Long.parseLong(s.substring(k + 6, e));
            }
        } catch (Throwable ignored) { }
        return 0;
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
}
