package com.dsh.numenresident;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.provider.IToolSpec;
import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Numen Resident —— 给 Numen 同伴长期记忆与他人意识。
 *
 * <p>设计原则（重要，勿在后续开发中破坏）：
 * <ul>
 *   <li>只提供<b>能力</b>：记得住、查得到、知道别人存在。</li>
 *   <li>不规定<b>互动形式</b>：不写入 trust / relationship 之类的社会概念字段，
 *       不告诉同伴"应该"怎么对待别人。</li>
 *   <li>记忆只存<b>事实</b>（谁在何时说了什么、做了什么、结果如何），
 *       判断与归纳由同伴自己完成。</li>
 * </ul>
 *
 * <p>本 mod 对 Numen 是<b>软依赖</b>：没装 Numen 时不报错，只是不生效。
 * 状态一律存于存档目录内，因此可随存档迁移。
 *
 * <p><b>当前阶段</b>：脚手架 + 前置验证（V1/V2）。
 * 这一步只确认扩展点可用，还不实现记忆逻辑。
 */
@Mod(NumenResident.MODID)
public class NumenResident {

    public static final String MODID = "numenresident";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** 名册热重载的计数器（100 tick = 5 秒检查一次配置文件的修改时间）。 */
    private static int rosterReloadTicks = 0;

    public NumenResident() {
        // 数据落盘位置必须在服务端起来之后才能确定（要拿存档路径）。
        // 放在这里绑定，早于任何同伴生成。
        //
        // 时序注意：插件的 setup() 跑在 modloading-worker 线程上，早于服务端启动，
        // 所以那时 ResidentStore.root() 还是 null。真正落盘要等这两个事件之后。
        MinecraftForge.EVENT_BUS.addListener(
                (net.minecraftforge.event.server.ServerAboutToStartEvent e) -> ResidentStore.bindWorld(e.getServer()));

        // 服务端就绪后做一次落盘自检 —— 证明确实写进了存档而不是全局目录。
        MinecraftForge.EVENT_BUS.addListener(
                (net.minecraftforge.event.server.ServerStartedEvent e) -> {
                    verifyWorldStorage();
                    // 读名册配置（哪些名字是 Mindcraft 的 agent），再写一份名册
                    Roster.loadConfig(e.getServer().getServerDirectory().toPath().resolve("config"));
                    Roster.write(e.getServer());
                    LOGGER.info("[resident] 身份名册已建立: {}", Roster.describe(e.getServer()));
                });

        // ── 身份名册：玩家进出时重建 ──
        // 这是 CrossTalk 的基础设施：让三方（Numen 客户端 / Mindcraft / 其他工具）
        // 都能知道"这个世界里有哪些行动者、谁是 AI"。
        // 只写身份与类别，不含任何关系标签。
        MinecraftForge.EVENT_BUS.addListener(
                (net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent e) -> {
                    if (e.getEntity().getServer() != null) Roster.write(e.getEntity().getServer());
                });
        MinecraftForge.EVENT_BUS.addListener(
                (net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) -> {
                    if (e.getEntity().getServer() != null) Roster.write(e.getEntity().getServer());
                });

        // ── 名册配置热重载 ──
        // 每 5 秒看一次文件的修改时间，变了就重读并重建名册。
        // 为什么要这样：改 mindcraft_agents 每次都重启服务端会踢掉所有 agent、
        // 打断它们的长期行为——对"观察持续生活"的实验是很大的干扰。
        // 开销极小：一次 stat + 一个 long 比较。
        MinecraftForge.EVENT_BUS.addListener(
                (net.minecraftforge.event.TickEvent.ServerTickEvent e) -> {
                    if (e.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
                    if (++rosterReloadTicks < 100) return;   // 100 tick = 5 秒
                    rosterReloadTicks = 0;
                    Roster.write(e.getServer());
                });

        // ── 调试命令 ──
        // /resident say <同伴> <内容>  —— 让同伴"说"一句话，用于测试沟通链路。
        // 调试档专用：正式实验时不用它即可。
        MinecraftForge.EVENT_BUS.addListener(
                (net.minecraftforge.event.RegisterCommandsEvent e) -> {
                    try {
                        DebugCommands.register(e.getDispatcher());
                        LOGGER.info("[resident] 调试命令已注册（/resident say|list）");
                    } catch (Throwable t) {
                        LOGGER.warn("[resident] 调试命令注册失败: {}", t.toString());
                    }
                });

        // 时序说明：
        //   mods.toml 里对 numen 声明了 ordering=AFTER，因此本类构造时
        //   NumenCoreForge 已经跑完 NumenCore.init()，公共 API 可用。
        //   但仍加 try/catch —— 本 mod 应当能在没有 Numen 的环境里安静地不生效。
        try {
            NumenPlugins.register(new ResidentPlugin());
            LOGGER.info("[resident] 插件已注册到 Numen");
        } catch (Throwable t) {
            LOGGER.warn("[resident] Numen 不可用，本 mod 本局不生效（这不是错误）: {}", t.toString());
        }

        // ── 关于服务端侧的聊天路由：不做 ──
        //
        // 曾经在这里挂过一个 ServerChatEvent 监听，想把别人的话投递给同伴。
        // 实测**架构上走不通**：NumenApi.enqueue 在专用服务端上是空操作——
        // NumenGateway 引用 client/agent、client/skin，而 NumenPlugins.enqueue
        // 是个静态 volatile 字段，只有客户端 bindClient 之后才有值。
        //
        // 证据很直接：注入后日志说"已发出"，但同伴的 chat.jsonl 里一个字都没有。
        //
        // 同伴的大脑在客户端，所以"投递"这件事只能在客户端做。
        // 而一般化版本里连客户端侧也不投递了——改成把话写进队列，
        // 让同伴自己调 MCP 的 hear() 来取（拉模型，见 speech-mcp）。
        //
        // 这个 mod 现在只负责与身份有关的事：名册。
    }

    // ── API 访问器（封住 Numen 的可选依赖）──

    private static volatile com.dwinovo.numen.api.NumenApi API;

    /** Numen 的公共 API 是否可用。 */
    static boolean hasApi() {
        return API != null;
    }

    /**
     * 把一句话交给同伴的内置大脑。
     *
     * <p>走官方 {@code NumenApi.enqueue} —— 它的设计用途正是"把外部渠道接进同伴"，
     * 官方自己拿它做 QQ 桥接。
     */
    static void enqueue(java.util.UUID companion, String message) {
        var api = API;
        if (api == null) return;
        api.enqueue(companion, message);
    }

    /** 服务端启动后自检：数据目录是否真的落在存档内。 */
    private static void verifyWorldStorage() {
        try {
            Path world = ResidentStore.root();
            if (world == null) {
                LOGGER.warn("[resident] 自检 FAIL 存档数据目录未绑定");
                return;
            }
            java.nio.file.Files.createDirectories(world);
            Path probe = world.resolve("portability-probe.txt");
            java.nio.file.Files.writeString(probe,
                    "此文件位于存档内，随存档迁移。" + System.lineSeparator()
                  + "生成时间: " + java.time.Instant.now() + System.lineSeparator());
            LOGGER.info("[resident] 自检 OK 存档内数据目录可写 = {}", world);
            LOGGER.info("[resident] 自检文件 = {}", probe);
        } catch (Throwable t) {
            LOGGER.warn("[resident] 自检 FAIL: {}", t.toString());
        }
    }

    /** 插件入口。 */
    static final class ResidentPlugin implements NumenPlugin {
        @Override
        public void setup(NumenApi api) {
            LOGGER.info("[resident] setup() 被调用");

            // 保存 API 引用 —— 聊天路由要用它把外部消息投进同伴的内置大脑
            API = api;
            LOGGER.info("[resident] 聊天路由已就绪（enqueue 可用）");

            // ── V1：数据落盘位置对比 ──
            // NumenApi.configDir() 给的是**全局** config/numen/，不在存档里。
            // 我们自己的记忆必须待在存档内（见 ResidentStore 的说明）。
            // 这里把两者都打出来，差别一眼可见。
            try {
                Path global = api.configDir();
                LOGGER.info("[resident] V1a 全局配置目录（Numen 给的）= {}", global);
            } catch (Throwable t) {
                LOGGER.warn("[resident] V1a FAIL: {}", t.toString());
            }
            try {
                Path world = ResidentStore.root();
                if (world != null) {
                    java.nio.file.Files.createDirectories(world);
                    Path probe = world.resolve("portability-probe.txt");
                    java.nio.file.Files.writeString(probe,
                            "此文件位于存档内，随存档迁移。" + System.lineSeparator()
                          + "生成时间: " + java.time.Instant.now() + System.lineSeparator());
                    LOGGER.info("[resident] V1b OK 存档内数据目录 = {}（随存档迁移）", world);
                } else {
                    // 正常情况：服务端尚未启动。数据目录会在 ServerAboutToStart 时绑定。
                    LOGGER.info("[resident] V1b 存档数据目录尚未绑定（等 ServerAboutToStart）");
                }
            } catch (Throwable t) {
                LOGGER.warn("[resident] V1b FAIL: {}", t.toString());
            }

            // ── V2：contributeState 是否挂得上 ──
            // 这是"他者意识"的机制。先注入一段探针文本，
            // 用它确认同伴的 <runtime_state> 里真的会出现我们加的东西。
            // 探针内容刻意是中性事实陈述，不含任何祈使句。
            try {
                api.contributeState(companionUuid ->
                        "<resident_probe>通道可用。companion=" + companionUuid + "</resident_probe>");
                LOGGER.info("[resident] V2 OK contributeState 已注册");
            } catch (Throwable t) {
                LOGGER.warn("[resident] V2 FAIL contributeState 注册失败: {}", t.toString());
            }

            // ── 事件订阅是否可用 ──
            try {
                api.on(CompanionEvent.SPAWN, companion ->
                        LOGGER.info("[resident] 观察到同伴生成: {}", companion));
                api.on(CompanionEvent.REMOVE, companion ->
                        LOGGER.info("[resident] 观察到同伴移除: {}", companion));
                LOGGER.info("[resident] 事件订阅已注册");
            } catch (Throwable t) {
                LOGGER.warn("[resident] 事件订阅注册失败: {}", t.toString());
            }

            // ── V3（编译期）：自定义工具能否注册 ──
            // 这一步先只验证接口能否实现、能否注册进去；工具名用探针名。
            try {
                api.registerTool(new ProbeTool());
                LOGGER.info("[resident] V3 OK 探针工具已注册");
            } catch (Throwable t) {
                LOGGER.warn("[resident] V3 FAIL 工具注册失败: {}", t.toString());
            }

            LOGGER.info("[resident] setup() 完成");
        }
    }

    /**
     * 探针工具：确认第三方能注册一个被同伴看见并调用的工具。
     * 后续会被真正的 recall 之类的记忆工具取代。
     */
    static final class ProbeTool implements NumenTool {

        @Override
        public String name() {
            return "resident_probe";
        }

        @Override
        public String description() {
            return "Returns a short confirmation string. Used only to verify that a "
                 + "third-party tool is visible and callable.";
        }

        @Override
        public Map<String, Object> parameterSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", new LinkedHashMap<String, Object>());
            return schema;
        }

        @Override
        public void onServerCall(String toolCallId, JsonObject args,
                                 NumenPlayer companion, Consumer<String> reply) {
            reply.accept("{\"ok\":true,\"from\":\"numenresident\"}");
        }
    }
}
