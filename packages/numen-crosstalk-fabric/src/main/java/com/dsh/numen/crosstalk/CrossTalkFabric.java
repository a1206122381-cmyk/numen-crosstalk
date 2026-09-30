package com.dsh.numen.crosstalk;

import com.dwinovo.numen.api.NumenPlugins;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CrossTalk —— 让 Minecraft 里的多个 AI 个体能互相说话。
 *
 * <h2>它做什么</h2>
 * 给 Numen 同伴装上三个工具：{@code say}（对世界说话）、{@code hear}（听别人说过什么）、
 * {@code who_is_here}（看有谁在）。引擎侧只注册，不干涉它怎么用。
 *
 * <h2>为什么是「拉」不是「推」——这是本项目的核心决定</h2>
 * 最直觉的做法是"别人一说话就注入给同伴"。那条路我们走过，它有<b>两个</b>问题：
 *
 * <ol>
 *   <li><b>挤占主人的对话窗口。</b> 注入走的是"主人说话"那条通道，
 *       会被记进主人和助手的对话历史。主人说十句话，中间插进三十句别的 AI 的闲聊——
 *       在普通游玩对局里不可接受。</li>
 *   <li><b>每句话都唤醒所有人，形成寒暄循环。</b> 实测中一个同伴自己识别出了这个模式
 *       并抱怨过："everyone's said goodnight twice now — the last word gets said,
 *       then repeated, then said again."</li>
 * </ol>
 *
 * <p>所以改成：<b>话写进队列，谁想听谁自己来取。</b>
 * 拉模型不丢消息（队列是持久的），只是延迟——而延迟对人类协作来说完全够。
 * 唯一需要"推"的是真正的紧急情况，那是 {@code say(text, urgent=true)}，
 * 由<b>说话的人自己判断</b>急不急，我们不替它定义。
 *
 * <h2>与外部 MCP 服务器的关系</h2>
 * 本 mod 和 {@code speech-mcp}（一个独立的 Node 进程）读写<b>同一批文件</b>，
 * 但互不知道对方存在。这个边界是刻意的——任何一端都能被替换。
 *
 * <p>之所以还需要这个 mod 而不是纯 MCP，原因是<b>身份</b>：
 * MCP 协议里没有"谁在调用"这个字段，工具调用只有工具名和参数，
 * 所以外部服务器分不清是 Lin 在说还是 Wei 在说。
 * 而 mod 里的工具调用天然带着同伴的 UUID（见 {@link SpeechTools}）。
 */
public final class CrossTalkFabric implements ModInitializer {

    public static final String MOD_ID = "numen_crosstalk";
    public static final Logger LOG = LoggerFactory.getLogger("crosstalk");

    /**
     * 相对游戏目录的默认位置。
     *
     * <p>放在 {@code config/} 而不是存档里：这几个文件是"管道"，
     * 不是世界状态。换世界时队列跟着换反而会让游标错位
     * （见 {@link SpeechQueue} 里"队列被换掉"那段）。
     */
    private static final String DEFAULT_DIR = "config/crosstalk";

    @Override
    public void onInitialize() {
        Path root = FabricLoader.getInstance().getGameDir().resolve(DEFAULT_DIR);
        try {
            Files.createDirectories(root);
        } catch (Exception e) {
            LOG.warn("[crosstalk] 无法创建目录 {}: {}", root, e.toString());
        }

        SpeechQueue.bind(
                root.resolve("speech.jsonl"),        // 唯一的共享发言流
                root.resolve("heard-cursor.json"));  // 每只同伴读到哪了

        LOG.info("[crosstalk] 管道目录 = {}", root);

        // 注册插件。注意这里只 register，不 bindClient、不碰任何客户端类——
        // NumenApi 的说明明确写过：专用服务器上 bundleSkills / registerPortrait
        // 会安静地变成空操作，插件不必自己写 dist 判断。
        try {
            NumenPlugins.register(api -> {
                api.registerTool(new SpeechTools.Say());
                api.registerTool(new SpeechTools.Hear());
                api.registerTool(new SpeechTools.WhoIsHere());
                LOG.info("[crosstalk] 已注册工具: say / hear / who_is_here");
            });
        } catch (Throwable t) {
            // Numen 没装时不炸——这个 mod 独立存在也是合法的
            LOG.warn("[crosstalk] 注册到 Numen 失败（没装 Numen？）: {}", t.toString());
        }
    }
}
