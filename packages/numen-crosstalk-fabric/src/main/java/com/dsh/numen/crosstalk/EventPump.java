package com.dsh.numen.crosstalk;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 把"别人说了句话"送进同伴的事件队列。
 *
 * <h2>为什么需要它——这是"拉"模型的必要补完</h2>
 * 只有 {@code hear} 工具时，同伴得<b>想起来</b>去听。实测下来它想不起来：
 * 得主人每次手动让它听，否则就算旁边有人刚跟它说过话，它也一无所知。
 * 对一次性对话无所谓，对长程协作是致命的。
 *
 * <p>而 Numen 的 {@code EventQueue} 正好提供了缺的那一环：
 * <b>事件可以攒着，到点自动送进它的上下文</b>。判定在
 * {@code EventQueue.shouldDrain}：
 *
 * <pre>
 *   有 urgent 的          → 立刻排空
 *   条数 ≥ 门槛           → 排空
 *   最老的等了够久        → 排空
 * </pre>
 *
 * <p>所以投递分两档：
 * <ul>
 *   <li><b>普通发言</b> → 非 urgent。不打断它手头的事，
 *       但下次开轮（或攒够了）它自然就看得到。</li>
 *   <li><b>紧急发言</b> → urgent。立刻把它叫起来。</li>
 * </ul>
 *
 * <p>"急不急"由<b>说话的人自己判断</b>（{@code say(text, urgent=true)}），
 * 我们不替它定义。
 *
 * <h2>为什么用反射</h2>
 * Numen 的 {@code EntityAgentLoop} 里带着 Minecraft 类型（intermediary 名），
 * 直接引用会把编译期和运行期的名字搅在一起。这里只需要一个
 * {@code pushEvent(String, String, long, boolean)}，反射足够，也更耐受它改名。
 *
 * <p>找不到就安静地什么都不做——投递是增强，不该让说话本身失败。
 */
public final class EventPump {

    private EventPump() {}

    /** 缓存的 pushEvent 方法，避免每次调用都查一遍。 */
    private static Method pushMethod;
    private static boolean looked;

    /**
     * 把一条发言投给除 {@code fromWho} 之外的所有同伴。
     *
     * @return 实际投给了几个人
     */
    static int pumpToOthers(String fromWho, String text, boolean urgent) {
        List<UUID> ids = companionIds();
        if (ids.isEmpty()) return 0;

        int n = 0;
        for (UUID id : ids) {
            String name = NameResolver.nameOf(id);
            if (name != null && name.equals(fromWho)) continue;   // 不推给说话的人自己
            if (push(id, fromWho + " 说：" + text, urgent)) n++;
        }
        if (n > 0) {
            CrossTalkFabric.LOG.info("[crosstalk] 已投递「{}」给 {} 只同伴（urgent={}）",
                    text.length() > 30 ? text.substring(0, 30) + "…" : text, n, urgent);
        }
        return n;
    }

    /**
     * 推进某个同伴的事件队列。
     *
     * @param text  事件正文。只写事实（谁说了什么），不写"你应该回应"
     * @param urgent true = 立刻打断它开一轮
     */
    static boolean push(UUID companion, String text, boolean urgent) {
        try {
            Object loop = loopOf(companion);
            if (loop == null) return false;

            Method m = pushMethodFor(loop.getClass());
            if (m == null) return false;

            // ts 传 0 → 让 Numen 自己填当前时间（见它字节码里 lconst_0 的分支）
            m.invoke(loop, "crosstalk", text, 0L, urgent);
            return true;
        } catch (Throwable t) {
            CrossTalkFabric.LOG.debug("[crosstalk] 推事件失败 {}: {}", companion, t.toString());
            return false;
        }
    }

    /** 找 {@code pushEvent(String, String, long, boolean)}。 */
    private static Method pushMethodFor(Class<?> cls) {
        if (looked) return pushMethod;
        looked = true;
        try {
            pushMethod = cls.getMethod("pushEvent", String.class, String.class, long.class, boolean.class);
            CrossTalkFabric.LOG.info("[crosstalk] 已接上事件通道: {}", cls.getSimpleName());
        } catch (NoSuchMethodException e) {
            CrossTalkFabric.LOG.warn(
                    "[crosstalk] 这个 Numen 版本的 {} 没有 pushEvent(String,String,long,boolean)，"
                    + "同伴不会自动听到别人说话（hear 工具仍可用）", cls.getSimpleName());
        }
        return pushMethod;
    }

    // ─────────────────────────────────────────────
    // Numen 侧的对象获取（反射，见类注释）
    // ─────────────────────────────────────────────

    /** 取某只同伴的 agent loop。 */
    private static Object loopOf(UUID id) {
        try {
            Class<?> reg = Class.forName("com.dwinovo.numen.client.agent.AgentLoopRegistry");
            Object opt = reg.getMethod("get", UUID.class).invoke(null, id);
            if (opt == null) return null;
            // Optional.get()
            Method get = opt.getClass().getMethod("get");
            return get.invoke(opt);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 现在真正在世界里的同伴 UUID。
     *
     * <p>用 {@code AgentLoopRegistry.loadedEntityUuids()} 而不是
     * {@code CompanionHome.known()}——后者读的是<b>磁盘目录</b>，
     * 回答的是"存在过哪些同伴"，一只休眠的同伴照样在里面。
     * 投递当然只该投给在场的。
     */
    private static List<UUID> companionIds() {
        List<UUID> out = new ArrayList<>();
        try {
            Class<?> reg = Class.forName("com.dwinovo.numen.client.agent.AgentLoopRegistry");
            Object loaded = reg.getMethod("loadedEntityUuids").invoke(null);
            if (loaded instanceof Collection<?> col) {
                for (Object o : col) if (o instanceof UUID id) out.add(id);
            }
        } catch (Throwable t) {
            CrossTalkFabric.LOG.debug("[crosstalk] 取在场同伴失败: {}", t.toString());
        }
        return out;
    }
}
