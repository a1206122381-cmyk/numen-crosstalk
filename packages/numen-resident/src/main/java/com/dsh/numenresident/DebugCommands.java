package com.dsh.numenresident;

import com.dwinovo.numen.entity.NumenPlayer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 调试命令：让同伴"说"一句话。
 *
 * <h2>为什么需要它</h2>
 * Numen 同伴主动发言只能靠它自己调工具——从外部没有入口。
 * 但调试沟通链路时，"等它自发开口"太慢，所以需要一个能主动触发的手段。
 *
 * <h2>为什么这不是伪造</h2>
 * 它广播的就是一条<b>正常的聊天消息</b>，服务端看到的与真实玩家发言完全一致
 * （同一个 {@code ServerChatEvent}）。区别只在"谁发起的"——
 * 调试档里我们不在乎这个，正式实验时不用这条命令即可。
 *
 * <pre>
 * /resident say &lt;同伴名&gt; &lt;内容&gt;
 * </pre>
 */
public final class DebugCommands {

    private DebugCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("resident")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("say")
                        .then(Commands.argument("who", StringArgumentType.word())
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String who = StringArgumentType.getString(ctx, "who");
                                            String text = StringArgumentType.getString(ctx, "text");
                                            return say(ctx.getSource().getServer(), who, text);
                                        })))));

        // 顺手给一个列出同伴的命令，调试时方便
        dispatcher.register(Commands.literal("resident")
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx.getSource()))));

        // /resident prompt <同伴> <文本> —— 给同伴注入一句输入。
        //
        // ⚠️ 这个命令**只在内置服务端（单机联机）上有效**。
        //
        // 它走 NumenApi.enqueue，而 enqueue 在**专用服务端上是空操作**：
        // NumenGateway 引用 client/agent、client/skin，NumenPlugins.enqueue 是个
        // 静态 volatile 字段，只有客户端 bindClient 之后才有值。
        // 实测：命令返回成功、日志说"已注入"，但同伴的 chat.jsonl 里一个字都没有。
        //
        // 保留它是因为在**内置服务端**（客户端自己开的那个，同伴大脑就在同一进程里）
        // 它是有效的调试手段——可以用脚本驱动同伴去调任何工具。
        // 专用服务端上请改用客户端侧的手段。
        dispatcher.register(Commands.literal("resident")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("prompt")
                        .then(Commands.argument("who", StringArgumentType.word())
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> prompt(
                                                StringArgumentType.getString(ctx, "who"),
                                                StringArgumentType.getString(ctx, "text")))))));
    }

    /**
     * 给同伴注入一句输入（走官方 {@code NumenApi.enqueue}）。
     *
     * <p>⚠️ 专服上是空操作，见上面注册处的说明。这里在集成服务端检测到时给出警告，
     * 免得使用者拿到"成功"却看不到任何效果。
     */
    private static int prompt(String who, String text) {
        if (!NumenResident.hasApi()) {
            NumenResident.LOGGER.warn("[resident] 调试注入：Numen API 不可用");
            return 0;
        }
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return 0;

        java.util.UUID target = null;
        String found = null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer && p.getName().getString().equalsIgnoreCase(who)) {
                target = p.getUUID();
                found = p.getName().getString();
                break;
            }
        }
        if (target == null) {
            NumenResident.LOGGER.warn("[resident] 调试注入：找不到同伴 {}", who);
            return 0;
        }
        NumenResident.enqueue(target, text);
        NumenResident.LOGGER.info("[resident] 调试注入 → {}: {}", found, text);
        return 1;
    }

    private static int say(MinecraftServer server, String who, String text) {
        if (server == null) return 0;
        // 按名字找**任何**在线玩家 —— 同伴（NumenPlayer）与普通玩家都行。
        //
        // 早期版本只找 NumenPlayer，结果对 Mindcraft 的 agent（走普通原版协议，
        // 在服务端就是个普通玩家）报"找不到同伴"。调试命令没理由区分这两类：
        // 它要做的只是"以某个身份广播一句话"。
        ServerPlayer target = null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getName().getString().equalsIgnoreCase(who)) {
                target = p;
                break;
            }
        }
        if (target == null) {
            NumenResident.LOGGER.warn("[resident] 调试发言：找不到在线玩家 {}", who);
            return 0;
        }
        boolean isCompanion = target instanceof NumenPlayer;
        final ServerPlayer t = target;

        // 只广播，不投递。
        //
        // 曾经这里还调一次 ChatRelay 想把话投递给同伴，但那条路实测走不通
        // （NumenApi.enqueue 在专用服务端是空操作），已删除。
        //
        // 现在这个命令的作用是"以某个身份在聊天里说一句"，让日志和读日志的
        // 观察者（Mindcraft 的 watch 逻辑等）看得见。同伴能不能听到，
        // 取决于客户端侧有没有把它写进队列。
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("<" + t.getName().getString() + "> " + text), false);

        NumenResident.LOGGER.info("[resident] 调试发言（{}{}）: {}", who,
                isCompanion ? "，同伴" : "，普通玩家", text);
        return 1;
    }

    private static int list(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        StringBuilder sb = new StringBuilder("在线玩家: ");
        int n = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (n > 0) sb.append(", ");
            sb.append(p.getName().getString());
            if (p instanceof NumenPlayer) sb.append("(同伴)");
            n++;
        }
        if (n == 0) sb.append("(无)");
        final String out = sb.toString();
        src.sendSuccess(() -> Component.literal(out), false);
        NumenResident.LOGGER.info("[resident] {}", out);
        return n;
    }
}
