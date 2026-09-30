package com.dsh.numen.crosstalk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 共享发言流——所有人说，所有人听。
 *
 * <h2>⚠️ 一个文件，不是两个（踩过一次，值得写清楚）</h2>
 * 最早的版本分了两个文件：{@code speech.jsonl} 装"我说的话"，
 * {@code heard.jsonl} 装"我要听的话"，设想有个东西把前者转成后者。
 *
 * <p><b>那个东西从来不存在。</b> 于是 {@code say} 写得漂漂亮亮、
 * {@code hear} 永远听到空——实测中一只同伴对另一只说了句好话，
 * 对方查了两次都说"没人说过任何话"。
 *
 * <p>正确设计本来就更简单：<b>一个人说的话，对别人来说就是要听到的东西。</b>
 * 同一个流，各人有各人的读取位置（游标）。一个文件就够。
 *
 * <h2>游标记「序号」不记「行号」</h2>
 * 行号是文件布局的投影——末尾换行、并发追加、文件被重写都会让它错位。
 * 早期版本就因此把游标推到了文件行数之外，于是所有新消息都被当成"已读"，
 * 表现成 {@code hear()} 永远听不到东西。序号不依赖布局。
 *
 * <h2>与外部 MCP 服务器的关系</h2>
 * 本类和 {@code speech-mcp}（一个独立的 Node 进程）读写<b>同一个文件</b>，
 * 但互不知道对方存在。这个边界是刻意的——任何一端都能被替换。
 */
public final class SpeechQueue {

    private SpeechQueue() {}

    /** 唯一的共享文件。所有人说、所有人听，都走它。 */
    private static volatile Path streamPath;
    /** 每只同伴读到哪了：名字 → 已读到的最大 seq。 */
    private static volatile Path cursorPath;

    private static final Object WRITE_LOCK = new Object();

    static void bind(Path stream, Path cursor) {
        streamPath = stream;
        cursorPath = cursor;
    }

    static boolean ready() {
        return streamPath != null && cursorPath != null;
    }

    // ─────────────────────────────────────────────
    // 说
    // ─────────────────────────────────────────────

    /**
     * 记下"某只同伴说了一句话"。
     *
     * <p>署名是<b>真的</b>——就是调用它的那只同伴。这一点比听起来重要：
     * 1.20.1 那条线里只能用本机玩家的连接发包，署名必然变成主人，
     * 结果同伴的话在别人看来是主人说的。这里没有那个问题。
     *
     * @param urgent 是否值得打断别人（对方会被唤醒，而不是等它来听）
     */
    static void say(String speaker, String text, boolean urgent) {
        if (!ready() || speaker == null || text == null || text.isBlank()) return;
        JsonObject o = new JsonObject();
        o.addProperty("seq", nextSeq());
        o.addProperty("t", System.currentTimeMillis());
        o.addProperty("name", speaker);
        o.addProperty("text", text);
        if (urgent) o.addProperty("urgent", true);
        append(o.toString());
    }

    /**
     * 分配序号。
     *
     * <p>用内存计数器而不是每写一次都读文件——文件可能很大，
     * 而写这条路径会被频繁调用。启动时（惰性）从文件末尾续一次号。
     */
    private static long seqCounter = -1;

    private static synchronized long nextSeq() {
        if (seqCounter < 0) seqCounter = lastSeqOnDisk();
        return ++seqCounter;
    }

    private static long lastSeqOnDisk() {
        long max = 0;
        for (JsonObject o : readAll()) {
            if (o.has("seq") && o.get("seq").isJsonPrimitive()) {
                max = Math.max(max, o.get("seq").getAsLong());
            }
        }
        return max;
    }

    // ─────────────────────────────────────────────
    // 听
    // ─────────────────────────────────────────────

    /** 一次最多回多少条，免得一口气灌爆上下文。 */
    private static final int MAX_HEAR = 40;

    /** hear 的结果。 */
    record Heard(List<JsonObject> items, int moreWaiting) {}

    /**
     * 取自上次以来别人说过的话。
     *
     * @param who      谁在听（游标按它分开记）
     * @param markRead false 时只看不推进游标（peek）
     */
    static Heard hear(String who, boolean markRead) {
        List<JsonObject> all = readAll();

        // 没有 seq 的旧格式：补一个，仍然比行号稳
        long fallback = 0;
        for (JsonObject o : all) {
            if (o.has("seq") && o.get("seq").isJsonPrimitive()) {
                fallback = Math.max(fallback, o.get("seq").getAsLong());
            } else {
                o.addProperty("seq", ++fallback);
            }
        }

        long since = readCursor(who);

        // ── 队列被换掉的处理 ──
        //
        // 换存档或清空文件时 seq 会从头开始，旧游标（比如 400）比整个流的最大
        // 序号（比如 2）还大，于是所有消息都被当成"已读"——又表现成听不到东西。
        if (since > fallback) {
            CrossTalkFabric.LOG.info(
                    "[crosstalk] {} 的读取位置 {} 超过流的最大序号 {}，判定流已重置，从头读",
                    who, since, fallback);
            since = 0;
        }

        List<JsonObject> fresh = new ArrayList<>();
        for (JsonObject o : all) {
            if (o.get("seq").getAsLong() <= since) continue;
            String n = o.has("name") ? o.get("name").getAsString() : null;
            if (who != null && who.equals(n)) continue;   // 自己说的不用听回来
            fresh.add(o);
        }

        List<JsonObject> items = fresh.size() > MAX_HEAR
                ? new ArrayList<>(fresh.subList(0, MAX_HEAR)) : fresh;

        if (markRead) {
            long next = fresh.size() <= MAX_HEAR
                    ? fallback
                    : (items.isEmpty() ? since : items.get(items.size() - 1).get("seq").getAsLong());
            writeCursor(who, next);
        }

        return new Heard(items, Math.max(0, fresh.size() - items.size()));
    }

    /** 从流里反推"最近有谁说过话"。装不了名册时是唯一的来源。 */
    static List<String> speakersByRecency() {
        Map<String, Long> last = new LinkedHashMap<>();
        for (JsonObject o : readAll()) {
            if (!o.has("name")) continue;
            String n = o.get("name").getAsString();
            if (n == null || n.isBlank()) continue;
            long t = o.has("t") ? o.get("t").getAsLong() : 0L;
            last.merge(n, t, Math::max);
        }
        List<Map.Entry<String, Long>> e = new ArrayList<>(last.entrySet());
        e.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Long> x : e) out.add(x.getKey());
        return out;
    }

    // ─────────────────────────────────────────────
    // 底层
    // ─────────────────────────────────────────────

    /** 追加一行。加锁是因为可能同时有多个同伴在说话。 */
    private static void append(String line) {
        synchronized (WRITE_LOCK) {
            try {
                Files.createDirectories(streamPath.getParent());
                Files.writeString(streamPath, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                CrossTalkFabric.LOG.warn("[crosstalk] 写入发言流失败: {}", e.toString());
            }
        }
    }

    /** 逐行解析。坏行跳过，不影响其他行——文件是流式追加的，最后一行可能是半截。 */
    private static List<JsonObject> readAll() {
        List<JsonObject> out = new ArrayList<>();
        if (streamPath == null) return out;
        List<String> lines;
        try {
            lines = Files.readAllLines(streamPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return out;   // 文件还没生成——不是错误
        }
        for (String raw : lines) {
            String s = raw == null ? "" : raw.strip();
            if (s.isEmpty()) continue;
            try {
                JsonObject o = JsonParser.parseString(s).getAsJsonObject();
                if (o.has("name") && o.has("text")) out.add(o);
            } catch (RuntimeException ignored) {
                // 半截行 / 脏数据
            }
        }
        return out;
    }

    // ── 游标 ──

    private static Map<String, Long> cursors;

    private static synchronized long readCursor(String who) {
        if (cursors == null) cursors = loadCursors();
        return cursors.getOrDefault(who == null ? "" : who, 0L);
    }

    private static synchronized void writeCursor(String who, long seq) {
        if (cursors == null) cursors = loadCursors();
        cursors.put(who == null ? "" : who, seq);
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Long> e : cursors.entrySet()) o.addProperty(e.getKey(), e.getValue());
        synchronized (WRITE_LOCK) {
            try {
                Files.createDirectories(cursorPath.getParent());
                Files.writeString(cursorPath, o.toString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                CrossTalkFabric.LOG.warn("[crosstalk] 写游标失败: {}", e.toString());
            }
        }
    }

    private static Map<String, Long> loadCursors() {
        Map<String, Long> m = new LinkedHashMap<>();
        try {
            String s = Files.readString(cursorPath, StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(s).getAsJsonObject();
            for (String k : new ArrayList<>(o.keySet())) m.put(k, o.get(k).getAsLong());
        } catch (Exception ignored) {
            // 没有游标文件 = 第一次运行，从头发
        }
        return m;
    }
}
