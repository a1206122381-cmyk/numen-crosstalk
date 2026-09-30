package com.dsh.numen.crosstalk;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * UUID ↔ 个体名。
 *
 * <p>名字是<b>记忆的键</b>：同伴每次重生成都换新 UUID。凡是跨会话的东西
 * （记忆、队列里的署名）都按名字走，否则就是"每次重生都失忆"。
 *
 * <h2>为什么读世界的玩家列表，而不是问 Numen</h2>
 * Numen 有一个 {@code ClientNumenLookup.resolve(UUID)}，看着最直接。但它声明返回
 * {@code class_742}（<b>intermediary 名</b>）——javac 为了确认这次调用必须加载那个
 * 返回类型，于是编译期就报 "cannot access class_742"。而 Numen 发布的制品又没有 refmap，
 * 也没法交给 Loom 去 remap。
 *
 * <p>而同伴在客户端本来就是一个普通玩家实体，所以从世界的玩家列表按 UUID 找，
 * 既准又只用 Minecraft 自己的类型。
 *
 * <h2>⚠️ 这里有个 Fabric 陷阱，踩过一次</h2>
 * remapper 会把源码里的<b>类引用</b>换成运行期名字，但
 * <b>字符串里的类名它一个字都不动</b>。所以：
 *
 * <pre>
 * Class.forName("net.minecraft.client.Minecraft")   → ClassNotFoundException
 * MinecraftClient.class                             → ✅ 被正确 remap
 * </pre>
 *
 * <p>教训：碰 Minecraft 的东西一律用类字面量。
 * 只有 <b>Numen 自己的类</b>（{@code com.dwinovo.*}）才用字符串 Class.forName——
 * 那些名字不是 Minecraft 的，不受 remap 影响。
 *
 * <h2>映射是 Yarn</h2>
 * 所以是 {@code MinecraftClient} 不是 {@code Minecraft}、
 * {@code world} 不是 {@code level}、{@code getPlayers()} 不是 {@code players()}。
 */
public final class NameResolver {

    private NameResolver() {}

    private static final java.util.Map<UUID, String> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    // ─────────────────────────────────────────────
    // 单个名字
    // ─────────────────────────────────────────────

    /** 同伴名。取不到返回 null。 */
    static String nameOf(UUID id) {
        if (id == null) return null;
        String hit = CACHE.get(id);
        if (hit != null) return hit;

        PlayerEntity p = findPlayer(id);
        if (p == null) return null;

        String n = p.getName().getString();
        if (n == null || n.isBlank()) return null;
        CACHE.put(id, n);
        return n;
    }

    private static PlayerEntity findPlayer(UUID id) {
        List<PlayerEntity> all = players();
        if (all == null) return null;
        for (PlayerEntity p : all) {
            if (p != null && id.equals(p.getUuid())) return p;
        }
        return null;
    }

    // ─────────────────────────────────────────────
    // 名单
    // ─────────────────────────────────────────────

    /** 名单来源，用来区分"真的没人"和"我查不到"。 */
    enum Source {
        /** 世界玩家列表——权威。 */
        WORLD,
        /** 同伴目录——只含同伴，且可能是休眠的。 */
        COMPANION_DIRS,
        /** 两条路都失败。 */
        NONE
    }

    record Names(List<String> names, Source source) {}

    /**
     * 现在世界里有哪些个体。
     *
     * <h2>为什么不静默降级</h2>
     * 失败时返回 {@link Source#NONE} 而不是空列表。
     * 空列表的含义是"确认没人"，失败的含义是"我不知道"——必须分开。
     * 实测踩过：解析失败后退回队列路径，而队列只含"最近说过话的人"，
     * 于是工具回答"(nobody else)"——一只同伴连查两次，
     * 告诉主人"这世界只有我一个"，而另一个同伴就站在旁边。
     * <b>工具说错话比说"我不知道"糟得多。</b>
     */
    static Names onlineNames() {
        List<PlayerEntity> ps = players();
        if (ps != null) {
            List<String> out = new ArrayList<>();
            for (PlayerEntity p : ps) {
                if (p == null) continue;
                String n = p.getName().getString();
                if (n != null && !n.isBlank()) out.add(n);
            }
            CrossTalkFabric.LOG.info("[crosstalk] who_is_here：从世界玩家列表读到 {} 个个体", out.size());
            return new Names(out, Source.WORLD);
        }

        List<String> dirs = companionDirs();
        if (dirs != null) {
            CrossTalkFabric.LOG.info("[crosstalk] who_is_here：退回同伴目录，读到 {} 个", dirs.size());
            return new Names(dirs, Source.COMPANION_DIRS);
        }

        return new Names(List.of(), Source.NONE);
    }

    /**
     * 世界里的玩家。
     *
     * @return null 表示"查不到"（没进世界 / 反射失败），区别于空列表
     */
    private static List<PlayerEntity> players() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null) return null;
            ClientWorld world = mc.world;
            if (world == null) return null;              // 还没进世界

            List<AbstractClientPlayerEntity> list = world.getPlayers();
            if (list == null) return null;

            return new ArrayList<>(list);
        } catch (Throwable t) {
            CrossTalkFabric.LOG.warn("[crosstalk] 读世界玩家列表失败: {}", t.toString());
            return null;
        }
    }

    /**
     * 同伴目录里的名字。失败返回 null。
     *
     * <p>{@code CompanionHome} 是 Numen 的类（不受 remap 影响），
     * 所以这里用字符串 Class.forName 是对的。
     */
    private static List<String> companionDirs() {
        try {
            Class<?> cls = Class.forName("com.dwinovo.numen.client.agent.CompanionHome");
            Object known = cls.getMethod("known").invoke(null);
            if (!(known instanceof Collection<?> ids)) return null;

            List<String> out = new ArrayList<>();
            for (Object o : ids) {
                if (o instanceof UUID id) {
                    String n = nameOf(id);
                    if (n != null && !n.isBlank()) out.add(n);
                }
            }
            // 目录里有 uuid 却一个名字都解不出来 = 解析这条路坏了，如实报失败
            if (out.isEmpty() && !ids.isEmpty()) return null;
            return out;
        } catch (Throwable t) {
            CrossTalkFabric.LOG.debug("[crosstalk] 读同伴目录失败: {}", t.toString());
            return null;
        }
    }
}
