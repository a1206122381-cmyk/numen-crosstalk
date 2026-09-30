package com.dsh.numenorchestrator;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨会话记忆（<b>按名字</b>存，不按 UUID）。
 *
 * <h2>为什么必须按名字</h2>
 * 每轮重启后同伴都是新实体、新 UUID，但名字是稳定的。
 * 记忆按 UUID 存就等于"每次重生都失忆"——实测踩过这个坑：
 * 因为 {@code remove_on_start} 遣散重建，旧目录被删，250 KB 的对话历史读不回来。
 *
 * <h2>存什么</h2>
 * <b>存结论与状态，不存对话记录。</b>
 * <pre>
 *   我住在 (-61, 64, -136) 附近
 *   我有一把石镐，熔炉在 (3, 56, -6)
 *   我和 Wei 交换过煤
 *   我认为 Andy 说话不算数        ← 立场也要存，那是人格的一部分
 * </pre>
 *
 * <p>为什么不做向量检索：agent 的记忆是<b>连贯的自我叙事</b>
 * （"我一直往西北走，因为我说过要走"），检索会把它打碎成互不相关的片段。
 * 而且检索工具本身要占用上下文——净效果往往是变差。
 * 这里直接给一份短的、结构化的摘要，够用且便宜。
 */
public final class Memory {

    private static final Logger LOG = LogUtils.getLogger();

    /** 一个同伴的记忆条目。 */
    public record Entry(String name, long updated, String summary) {}

    private static Path file = null;
    private static final List<Entry> entries = new ArrayList<>();

    private Memory() {}

    static void bindPath(String path) {
        if (path == null || path.isBlank()) return;
        file = Path.of(path);
        load();
    }

    /** 从磁盘读。格式：每行一条 {@code 名字<TAB>时间戳<TAB>摘要}。 */
    private static void load() {
        entries.clear();
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] p = line.split("\t", 3);
                if (p.length < 3) continue;
                try {
                    entries.add(new Entry(p[0], Long.parseLong(p[1]), p[2]));
                } catch (NumberFormatException ignored) { }
            }
            LOG.info("[orch] 记忆已载入：{} 条", entries.size());
        } catch (Throwable t) {
            LOG.warn("[orch] 记忆载入失败: {}", t.toString());
        }
    }

    private static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            for (Entry e : entries) {
                // 摘要里的换行会破坏一行一条的格式，压成空格
                sb.append(e.name()).append('\t').append(e.updated()).append('\t')
                  .append(e.summary().replace('\n', ' ').replace('\r', ' ')).append('\n');
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            LOG.warn("[orch] 记忆保存失败: {}", t.toString());
        }
    }

    /** 取某个名字的记忆摘要。没有就返回空串。 */
    static String recall(String name) {
        for (Entry e : entries) {
            if (e.name().equalsIgnoreCase(name)) return e.summary();
        }
        return "";
    }

    /** 写入/更新某个名字的记忆。 */
    static void remember(String name, String summary) {
        if (name == null || name.isBlank() || summary == null) return;
        String s = summary.strip();
        if (s.isEmpty()) return;
        entries.removeIf(e -> e.name().equalsIgnoreCase(name));
        entries.add(new Entry(name, System.currentTimeMillis(), s));
        save();
        LOG.info("[orch] 记忆更新（{}，{} 字）", name, s.length());
    }

    static boolean has(String name) {
        return !recall(name).isBlank();
    }

    /**
     * 从同伴的对话记录里抽一份摘要。
     *
     * <p>这里故意用<b>抽取式</b>而不是让模型总结：总结要额外一次 LLM 调用，
     * 而且会引入"模型认为重要"的偏差。抽取它自己说过的最后几条，
     * 保留的是<b>它自己的话</b>——包括它的判断和立场。
     *
     * @param uuid 同伴 uuid（用来找它的 chat.jsonl）
     * @param maxChars 摘要长度上限
     */
    static String summarizeFromChat(java.util.UUID uuid, int maxChars) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return "";
            Path p = mc.gameDirectory.toPath()
                    .resolve("config").resolve("numen").resolve("companions")
                    .resolve(uuid.toString()).resolve("chat.jsonl");
            if (!Files.isRegularFile(p)) return "";

            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            List<String> said = new ArrayList<>();
            // 从后往前取它说过的话
            for (int i = lines.size() - 1; i >= 0 && said.size() < 6; i--) {
                String s = lines.get(i);
                if (s == null || !s.contains("\"role\":\"assistant\"")) continue;
                String text = extractContent(s);
                if (text == null || text.isBlank()) continue;
                // 跳过明显的工具噪音
                String t = text.strip();
                if (t.startsWith("!") || t.length() < 20) continue;
                said.add(0, t);
            }
            if (said.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            for (String s : said) {
                if (sb.length() + s.length() > maxChars) break;
                if (sb.length() > 0) sb.append(" / ");
                sb.append(s);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 从一行 JSON 里抠 "content" 的值（与 NumenBridge 同款，不引 JSON 库）。 */
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
                    case 'n' -> sb.append(' ');
                    case 't' -> sb.append(' ');
                    case 'r' -> sb.append(' ');
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
}
