package com.dsh.numen.crosstalk;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 三个工具：{@code say} / {@code hear} / {@code who_is_here}。
 *
 * <h2>为什么身份是可靠的——这是整个方案的关键</h2>
 * 每个调用都从 {@link ToolCall#ctx()} 拿 {@link com.dwinovo.numen.agent.tool.ToolAnchor}，
 * 它带着<b>发起这个调用的同伴的 UUID</b>。
 *
 * <p>这一点决定了为什么不能用 MCP：
 * <b>MCP 协议里没有"谁在调用"这个字段</b>——工具调用只有工具名和参数。
 * 所以外部 MCP 服务器分不清是 Lin 在说还是 Wei 在说。
 * 而 mod 里的工具调用天然带身份。
 *
 * <h2>为什么覆写 invoke 而不是 onServerCall</h2>
 * 见 {@link NumenTool} 的说明：{@code onServerCall} 是<b>身体工具</b>的路径
 * （动同伴的身体、读它的世界，调用要发往服务端活体）。
 * 说话和听不是身体动作——它们读写的是共享文件，纯客户端逻辑
 * （和内置的 {@code todowrite} 同类）。所以覆写 {@code invoke}，当场完成。
 *
 * <h2>措辞纪律</h2>
 * 工具说明只描述<b>能做什么</b>，不规定<b>该做什么</b>。
 * 不写"你应该回应"、"记得主动社交"这类祈使句。
 * 听不听、说不说、什么时候做，都是它自己的判断。
 */
public final class SpeechTools {

    private SpeechTools() {}

    /** 从调用上下文取同伴名。取不到就回退到 uuid 前 8 位（仍然唯一）。 */
    private static String speakerOf(ToolCall call) {
        try {
            UUID id = call.ctx() == null ? null : call.ctx().entityUuid();
            if (id == null) return "unknown";
            String n = NameResolver.nameOf(id);
            return (n != null && !n.isBlank()) ? n : id.toString().substring(0, 8);
        } catch (Throwable t) {
            return "unknown";
        }
    }

    // ─────────────────────────────────────────────
    // say
    // ─────────────────────────────────────────────

    public static final class Say implements NumenTool {

        @Override public String name() { return "say"; }

        @Override public String description() {
            return "Say something out loud, so that anyone here can hear it. "
                    + "This is how you reach the other people in the world — to hand over "
                    + "something, to ask for a hand, to agree on who does what, to say what "
                    + "you found. "
                    + "Normally what you say waits in their inbox and they come across it on "
                    + "their own; mark it urgent when your next move actually depends on them.";
        }

        @Override public Map<String, Object> parameterSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "text", Map.of(
                                    "type", "string",
                                    "description", "What you say. Keep it short and plain."),
                            "urgent", Map.of(
                                    "type", "boolean",
                                    "description", "Reach them right now instead of leaving it in "
                                            + "their inbox. Worth setting when you are waiting on "
                                            + "them to do something, or when a thing cannot wait. "
                                            + "Defaults to false.")
                    ),
                    "required", List.of("text")
            );
        }

        /** 每轮都随请求发出——说话是常用动作，藏进 find_tools 会平白多一次调用。 */
        @Override public Residency residency() { return Residency.RESIDENT; }

        @Override public void invoke(ToolCall call) {
            try {
                if (!SpeechQueue.ready()) {
                    call.complete(TaskResult.fail("speech stream is not configured").toJson());
                    return;
                }
                JsonObject a = call.args();
                String text = a.has("text") ? a.get("text").getAsString().strip() : "";
                if (text.isEmpty()) {
                    call.complete(TaskResult.fail("nothing to say (empty text)").toJson());
                    return;
                }
                boolean urgent = a.has("urgent") && a.get("urgent").getAsBoolean();
                String who = speakerOf(call);
                SpeechQueue.say(who, text, urgent);

                // 除了写进流里，还要主动投递一份。
                //
                // 只写流的话，同伴得「想起来」去 hear——实测它想不起来，
                // 得主人每次手动让它听。对长程协作这是致命的。
                //
                // 投递走 Numen 的事件队列：普通发言不打断它（下次开轮自然看到），
                // urgent 的立刻叫醒。判定在 EventQueue.shouldDrain。
                EventPump.pumpToOthers(who, text, urgent);

                call.complete(TaskResult.ok("said: " + text).toJson());
            } catch (Throwable t) {
                call.complete(TaskResult.fail("say failed: " + t).toJson());
            }
        }
    }

    // ─────────────────────────────────────────────
    // hear
    // ─────────────────────────────────────────────

    public static final class Hear implements NumenTool {

        @Override public String name() { return "hear"; }

        @Override public String description() {
            return "Listen to what the other people here have said since you last listened. "
                    + "Returns them with who said it and when. "
                    + "This is simply what was said while you were busy — nothing is lost by "
                    + "waiting, and nothing obliges you to answer.";
        }

        @Override public Map<String, Object> parameterSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "mark_read", Map.of(
                                    "type", "boolean",
                                    "description", "Set false to just look, without marking "
                                            + "anything as heard. Defaults to true.")
                    )
            );
        }

        @Override public Residency residency() { return Residency.RESIDENT; }

        @Override public void invoke(ToolCall call) {
            try {
                if (!SpeechQueue.ready()) {
                    call.complete(TaskResult.fail("speech queue is not configured").toJson());
                    return;
                }
                JsonObject a = call.args();
                boolean markRead = !(a.has("mark_read") && !a.get("mark_read").getAsBoolean());
                SpeechQueue.Heard h = SpeechQueue.hear(speakerOf(call), markRead);

                if (h.items().isEmpty()) {
                    call.complete(TaskResult.ok(
                            "Nobody has said anything since you last listened.").toJson());
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (JsonObject o : h.items()) {
                    String when = o.has("t") ? timeOf(o.get("t").getAsLong()) : "";
                    sb.append('[').append(when).append("] ")
                            .append(o.get("name").getAsString()).append(": ")
                            .append(o.get("text").getAsString()).append('\n');
                }
                if (h.moreWaiting() > 0) {
                    sb.append('(').append(h.moreWaiting()).append(" more waiting)\n");
                }
                call.complete(TaskResult.ok(sb.toString().strip()).toJson());
            } catch (Throwable t) {
                call.complete(TaskResult.fail("hear failed: " + t).toJson());
            }
        }

        private static String timeOf(long ms) {
            long s = (ms / 1000L) % 86400L;
            return String.format("%02d:%02d", (s / 3600 + 8) % 24, (s % 3600) / 60);   // 东八区
        }
    }

    // ─────────────────────────────────────────────
    // who_is_here
    // ─────────────────────────────────────────────

    public static final class WhoIsHere implements NumenTool {

        @Override public String name() { return "who_is_here"; }

        @Override public String description() {
            return "List who is currently in the world with you, by name.";
        }

        @Override public Map<String, Object> parameterSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override public Residency residency() { return Residency.RESIDENT; }

        @Override public void invoke(ToolCall call) {
            try {
                NameResolver.Names n = NameResolver.onlineNames();

                // 别把"我查不到"说成"没人"。
                //
                // 实测踩过：解析失败时退回队列路径，而队列是空的，
                // 于是工具理直气壮地回答"(nobody else)"——
                // 一只同伴连查两次，然后告诉主人"这世界只有我一个"，
                // 而实际上主人另一个同伴就站在旁边（只是当时在休眠）。
                // 工具说错话比说"我不知道"糟得多。
                if (n.source() == NameResolver.Source.NONE) {
                    call.complete(TaskResult.fail(
                            "could not read the list of who is here").toJson());
                    return;
                }

                List<String> live = n.names();
                if (!live.isEmpty()) {
                    String suffix = n.source() == NameResolver.Source.COMPANION_DIRS
                            ? "  (from the companion list — this may include ones who are "
                              + "currently dormant, and it does not include ordinary players)"
                            : "";
                    call.complete(TaskResult.ok(String.join(", ", live) + suffix).toJson());
                    return;
                }

                if (n.source() == NameResolver.Source.WORLD) {
                    call.complete(TaskResult.ok("(nobody else)").toJson());
                    return;
                }

                // 世界列表读到了但是空的、且同伴目录也给不出东西 —— 退到队列。
                // 语义和"谁在线"不同，所以如实说明，而不是假装两者一样。
                List<String> spoke = SpeechQueue.speakersByRecency();
                if (spoke.isEmpty()) {
                    call.complete(TaskResult.ok("(nobody else)").toJson());
                    return;
                }
                call.complete(TaskResult.ok(String.join(", ", spoke)
                        + "  (from what has been said recently — this is not a full "
                        + "online list, so anyone who has stayed quiet will not show up)").toJson());
            } catch (Throwable t) {
                call.complete(TaskResult.fail("who_is_here failed: " + t).toJson());
            }
        }
    }
}
