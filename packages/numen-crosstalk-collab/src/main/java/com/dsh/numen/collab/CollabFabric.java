package com.dsh.numen.collab;

import com.dwinovo.numen.api.NumenPlugins;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CrossTalk Collab —— 让同伴知道"话也可以对同伴说"。
 *
 * <h2>为什么这是一个独立 mod，而不是基础 mod 的一部分</h2>
 * 基础 mod（{@code numen-crosstalk-fabric}）只给同伴装上说话和听的<b>能力</b>，
 * 一个字都不改它的提示词。装上基础 mod，同伴的行为和没装时一样——
 * 只是手上多了两个工具。
 *
 * <p>而"输出通常只对主人，但需要时也可以对同伴说"这件事，
 * 本质上是<b>改提示词</b>。它会改变同伴的行为方式，所以必须是可选的、
 * 并且一眼能看出装了什么。
 *
 * <p>分开还有一个实际好处：别人可以只装基础 mod 而完全不接受这套措辞；
 * 也可以装了这个但把措辞改掉（文本在外面的文件里，不用重新编译）。
 *
 * <h2>它怎么改提示词——不碰任何配置文件</h2>
 * 用官方钩子 {@code NumenApi.contributeState}：每轮请求现算一段，
 * 挂进这只同伴的 {@code <runtime_state>}。
 *
 * <p>这个钩子原本是给"状态"用的（你换了外观、接了什么设备）。
 * 借它来说一句关于"话可以说给谁听"的话，是<b>刻意的简化</b>——
 * 好处是：
 *
 * <ul>
 *   <li><b>不碰 {@code config/numen.json}</b>。改别人的配置文件是侵入性的，
 *       而且会和用户自己的设置打架。</li>
 *   <li><b>打不碎 prompt 缓存</b>。它挂在请求末端，
 *       不在那个字节级稳定的系统提示里（这是钩子文档里明确写的特性）。</li>
 *   <li><b>一个字都不入会话历史</b>。所以同伴不会"记得"我们跟它说过这句话，
 *       它只是在每一轮里知道这件事。</li>
 * </ul>
 *
 * <h2>措辞纪律</h2>
 * 说的是一件<b>事实</b>（你的话可以被谁听见），不是一条<b>命令</b>
 * （你应该去跟同伴协作）。"什么时候需要协作"由它自己判断。
 */
public final class CollabFabric implements ModInitializer {

    public static final String MOD_ID = "numen_crosstalk_collab";
    public static final Logger LOG = LoggerFactory.getLogger("crosstalk-collab");

    /**
     * 默认文本。
     *
     * <p>写法的两个考虑：
     * <ol>
     *   <li>先说清楚<b>默认</b>是什么（对主人说），再说<b>例外</b>（需要协作时）。
     *       顺序反过来会让同伴以为"对同伴说话"是常态。</li>
     *   <li>用"可以"而不是"应该"。前者是许可，后者是指派。</li>
     * </ol>
     */
    private static final String DEFAULT_TEXT =
            "Your words normally go to your owner. When you need to work something out with "
            + "the other people here — or when your owner asks you to — you can also say it "
            + "to them directly; they will hear it when they choose to listen.";

    /** 外部文件里放了什么就用什么；没有就写一份默认的出去。 */
    private static String loadText(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                String s = Files.readString(file, StandardCharsets.UTF_8).strip();
                if (!s.isEmpty()) {
                    LOG.info("[collab] 协作说明来自 {}", file);
                    return s;
                }
                LOG.warn("[collab] {} 是空的，用默认文本", file);
            } else {
                Files.createDirectories(file.getParent());
                Files.writeString(file, DEFAULT_TEXT + System.lineSeparator(), StandardCharsets.UTF_8);
                LOG.info("[collab] 已写出默认协作说明到 {}（想改就直接编辑这个文件）", file);
            }
        } catch (Throwable t) {
            LOG.warn("[collab] 读写 {} 失败，用默认文本: {}", file, t.toString());
        }
        return DEFAULT_TEXT;
    }

    @Override
    public void onInitialize() {
        Path cfgRoot = FabricLoader.getInstance().getGameDir().resolve("config/crosstalk");
        Path textFile = cfgRoot.resolve("collab-prompt.txt");

        final String text = loadText(textFile);

        try {
            NumenPlugins.register(api -> {
                // 每轮现算。文本是常量，但钩子要求现算——照做，反正很便宜。
                api.contributeState(companion -> text);

                // 备份一份到 mod 自己的配置目录，方便排查"到底生效了没有"。
                try {
                    Path mirror = api.configDir().resolve("crosstalk-collab.txt");
                    Files.createDirectories(mirror.getParent());
                    Files.writeString(mirror, text, StandardCharsets.UTF_8);
                } catch (Throwable ignored) {
                    // 镜像失败不影响功能
                }

                LOG.info("[collab] 已挂上协作说明（{} 字符）", text.length());
            });
        } catch (Throwable t) {
            LOG.warn("[collab] 注册失败（没装 Numen？）: {}", t.toString());
        }
    }
}
