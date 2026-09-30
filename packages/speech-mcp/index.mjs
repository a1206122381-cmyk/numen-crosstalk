#!/usr/bin/env node
// Numen 同伴的「听」与「说」MCP 服务器。
//
// ── 设计要点：拉模型，不是推模型 ──
//
// 之前用"转发"：子mod 把听到的话 submitPrompt 注进 agent loop。
// 问题很实在：那走的是**主人说话**的通道，会挤占主人和 AI 助手对话的窗口。
// 一旦一般化到普通游玩对局，主人跟 AI 说十句话，中间会插进三十句别的 AI 的闲聊。
//
// 所以改成**拉**：
//   - 别人说的话进队列（持久化，带时间戳和说话人），**不注入、不唤醒任何人**
//   - 它想听的时候自己调 hear()，拿"自上次以来"的全部
//
// 拉模型不丢消息，只延迟。也不会形成"每句话都唤醒所有人 → 所有人都要回应"的寒暄循环
// （实测中一个 agent 自己识别出了这个循环并抱怨过）。
//
// ── 为什么队列在服务端侧文件里 ──
// 说话的人和听话的人分别在两个进程（甚至两台机器）。
// 文件是唯一简单、可调试、跨进程的通道。子mod 负责写入。
//
// 工具：
//   hear(mark_read)    —— 听：自上次以来有谁说了什么
//   say(text, urgent)  —— 说：让这里的人听得见（urgent 会主动吵醒对方）
//   who_is_here()      —— 看现在有谁在
//
// 输出：追加到共享发言流 <存档>/numenresident/speech.jsonl
//   一行一条：{"t":<ms>,"name":"<谁>","text":"<内容>","urgent":<bool>}

import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// ── 配置 ──
// 优先级：命令行参数 > 环境变量 > 默认值
//
// 用法：
//   node index.mjs --speaker Lin --port 9101 \
//        --out   <存档>/numenresident/speech.jsonl \
//        --heard <存档>/numenresident/heard.jsonl
//
function argOf(name) {
    const i = process.argv.indexOf('--' + name);
    return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : undefined;
}

const PORT = Number(argOf('port') || process.env.SPEECH_MCP_PORT || 9101);

// 谁在通过这个实例说话。
// 单实例多同伴时不设它，让同伴调用时用 `as` 参数自报名字（见 callTool）。
const SPEAKER = argOf('speaker') || process.env.SPEECH_MCP_SPEAKER || null;

// 听到的队列：别人说的话由子mod 写进来，这里只读（hear）
const HEARD = argOf('heard') || process.env.SPEECH_MCP_HEARD || './data/heard.jsonl';

// 自己的发言流：say() 写出去，子mod 读它并转投给别人
const OUT = argOf('out') || process.env.SPEECH_MCP_OUT || './data/speech.jsonl';

// 身份名册（who_is_here 用）。不给就退化成"名册不可用"。
const ROSTER = argOf('roster') || process.env.SPEECH_MCP_ROSTER
    || path.join(path.dirname(HEARD), 'agents.json');

// 读取游标：只记消息序号，不记行号（原因见 hear() 的注释）
const CURSOR = argOf('cursor') || process.env.SPEECH_MCP_CURSOR
    || path.join(path.dirname(HEARD), 'heard-cursor.json');

const PROTOCOL_VERSION = '2025-06-18';
const SERVER_NAME = 'numen-speech';
const SERVER_VERSION = '0.2.0';
const MAX_HEAR = 40;          // 一次最多回多少条，免得一口气灌爆上下文

function log(...a) { console.log(`[speech-mcp]`, ...a); }

// ── 说 ──
function appendSpeech(who, text, urgent) {
    try {
        fs.mkdirSync(path.dirname(OUT), { recursive: true });
        const line = JSON.stringify({
            t: Date.now(), name: who, text,
            ...(urgent ? { urgent: true } : {})
        }) + '\n';
        fs.appendFileSync(OUT, line, 'utf8');
        log(`${who} 说出${urgent ? '（紧急）' : ''}: ${text.slice(0, 100)}`);
    } catch (e) {
        log('写入失败:', e.message);
    }
}

// ── 听 ──
function readCursor() {
    try { return JSON.parse(fs.readFileSync(CURSOR, 'utf8')); }
    catch { return {}; }
}
function writeCursor(c) {
    try {
        fs.mkdirSync(path.dirname(CURSOR), { recursive: true });
        fs.writeFileSync(CURSOR, JSON.stringify(c), 'utf8');
    } catch (e) { log('游标写入失败:', e.message); }
}

/**
 * 取自上次以来新增的发言。
 *
 * ── 为什么用「消息序号」而不是「行号」当游标 ──
 *
 * 第一版用行号，实测踩了一个很隐蔽的坑：文件末尾的换行符让 split('\n') 多出一个空元素，
 * 游标被推到 N+1，而文件只有 N 行——游标指到了不存在的位置，之后所有新消息都被跳过，
 * 表现成"hear() 永远听不到东西"。
 *
 * 行号天然脆弱：文件被重写、截断、并发追加都会让它失去意义。
 * 所以改成给每条消息编一个单调递增的序号（写队列的一方负责），游标记序号。
 * 序号不依赖文件布局，重写/截断都能正确判断"我读到哪了"。
 *
 * 游标按 SPEAKER 分开记——多个同伴共用一个端点时，各自的"未读"互相独立。
 */
function hear(who, markRead) {
    let lines;
    try { lines = fs.readFileSync(HEARD, 'utf8').split('\n'); }
    catch { return { items: [], unreadAfter: 0 }; }

    const cur = readCursor();
    const since = cur[who] || 0;

    // 先解析出所有有效消息（带序号），再按序号过滤。
    const all = [];
    for (const raw of lines) {
        const s = (raw || '').trim();
        if (!s) continue;
        let o;
        try { o = JSON.parse(s); } catch { continue; }   // 半截行跳过
        if (!o || !o.name || !o.text) continue;
        all.push(o);
    }

    // 没有 seq 字段的旧格式：退化成按数组下标编号，仍比行号稳
    let fallbackSeq = 0;
    for (const o of all) {
        if (typeof o.seq !== 'number') o.seq = ++fallbackSeq;
        else fallbackSeq = Math.max(fallbackSeq, o.seq);
    }

    // ── 队列被重置的处理 ──
    //
    // 换世界、清空队列、或子mod 换了存档时，seq 会从头开始。
    // 这时旧的游标（比如 400）比整个队列的最大 seq（比如 2）还大，
    // 结果所有消息都被当成"已经读过"——表现成 hear() 永远听不到东西。
    //
    // 判据：游标比队列里最大的 seq 还大，说明队列一定是被换掉了。
    // 这时把游标归零，从头读。
    const maxSeqSeen0 = all.length ? Math.max(...all.map(o => o.seq)) : 0;
    const effectiveSince = since > maxSeqSeen0 ? 0 : since;
    if (since > maxSeqSeen0) {
        log(`队列似乎被重置了（游标 ${since} > 最大 seq ${maxSeqSeen0}），从头读`);
    }

    const fresh = all.filter(o => o.seq > effectiveSince && o.name !== who);
    const items = fresh.slice(0, MAX_HEAR);
    const maxSeqSeen = all.length ? Math.max(...all.map(o => o.seq)) : effectiveSince;

    // 读完了就推到最大序号；被上限截断就停在最后一条返回的序号。
    const newCursor = fresh.length <= MAX_HEAR
        ? maxSeqSeen
        : (items.length ? items[items.length - 1].seq : effectiveSince);

    if (markRead) {
        cur[who] = newCursor;
        writeCursor(cur);
    }

    const unreadAfter = Math.max(0, fresh.length - items.length);
    return { items, unreadAfter };
}

/**
 * 现在还有谁在。
 *
 * 两条路，按可靠性排序：
 *
 *   1. **名册文件**（{@code agents.json}）—— 由服务端子mod 维护，
 *      是权威名单（含分类）。装了那个 mod 时用这条。
 *
 *   2. **队列里的说话人**（降级）—— 名册不存在时，从收到的发言里
 *      反推"最近有谁说过话"。
 *
 * <h2>为什么必须有第 2 条</h2>
 * 实测踩过：只装客户端mod（不装服务端那个）时，名册文件不存在，
 * {@code who_is_here()} 会永远返回"名册不可用"——而这件事明明是能做到的，
 * 队列里就有说话人的名字。
 *
 * <p>降级路径的语义差异要说清楚：名册是"**谁在线**"，
 * 队列是"**谁最近说过话**"。一个沉默的人不会出现在降级结果里。
 * 所以返回值里带上这个区别，而不是假装两者一样。
 */
function whoIsHere() {
    // ── 路 1：名册（权威）──
    try {
        const j = JSON.parse(fs.readFileSync(ROSTER, 'utf8'));
        const names = (j.agents || j || [])
            .map(a => (typeof a === 'string' ? a : a.name))
            .filter(Boolean);
        if (names.length) return names.join(', ');
        return '(nobody else)';
    } catch { /* 没名册，走降级 */ }

    // ── 路 2：从队列反推 ──
    try {
        const lines = fs.readFileSync(HEARD, 'utf8').split('\n');
        const seen = new Map();   // name → 最后一次说话的时间
        for (const raw of lines) {
            const s = (raw || '').trim();
            if (!s) continue;
            let o;
            try { o = JSON.parse(s); } catch { continue; }
            if (!o || !o.name || !o.text) continue;
            const prev = seen.get(o.name) || 0;
            if ((o.t || 0) >= prev) seen.set(o.name, o.t || 0);
        }
        if (seen.size === 0) return '(nobody has said anything yet)';

        const sorted = [...seen.entries()].sort((a, b) => b[1] - a[1]).map(e => e[0]);
        return sorted.join(', ')
            + '  (from what has been said recently — no roster is installed, '
            + 'so people who have stayed quiet will not show up here)';
    } catch {
        return '(nobody else)';
    }
}

/**
 * 确定"这次调用是谁发起的"。
 *
 * 两种部署方式：
 *   1. 每个同伴一个实例 —— 用 --speaker 固定身份（最准，推荐）
 *   2. 多个同伴共用一个实例 —— 同伴在参数里带 `as` 自报名字
 *
 * 共用一个实例时身份靠自报，理论上可以冒充。
 * 但它只是自己的名字，冒充没有收益；而少起几个进程是实打实的便宜。
 */
function resolveSpeaker(args) {
    const claimed = args && typeof args.as === 'string' ? args.as.trim() : '';
    return claimed || SPEAKER || 'Numen';
}

// ── 工具定义 ──
//
// 措辞纪律（这个项目的一条硬规则）：
//   工具的说明只描述【能做什么】，不规定【该做什么】。
//   不写"你应该回应"、"记得主动社交"、"建议定期查看"这类祈使句。
//   听不听、说不说、什么时候做，都是它自己的判断。
const AS_PROP = {
    type: 'string',
    description: 'Your name. Only needed when several of you share one server instance; '
        + 'otherwise it is set for you.'
};

const TOOLS = [
    {
        name: 'hear',
        description: 'Listen to what the other people here have said since you last listened. '
            + 'Returns them with who said it and when. '
            + 'This is simply what was said while you were busy — nothing is lost by waiting, '
            + 'and nothing obliges you to answer.',
        inputSchema: {
            type: 'object',
            properties: {
                mark_read: {
                    type: 'boolean',
                    description: 'Set false to just look, without marking anything as heard. Defaults to true.'
                },
                as: AS_PROP
            }
        }
    },
    {
        name: 'say',
        description: 'Say something out loud, so that anyone here can hear it. '
            + 'Use it when you actually want to be heard — not for thinking out loud. '
            + 'urgent=true interrupts whoever is nearby instead of waiting for them to listen, '
            + 'so use it sparingly.',
        inputSchema: {
            type: 'object',
            properties: {
                text: { type: 'string', description: 'What you say. Keep it short and plain.' },
                urgent: {
                    type: 'boolean',
                    description: 'Interrupt the others instead of waiting for them to listen. Defaults to false.'
                },
                as: AS_PROP
            },
            required: ['text']
        }
    },
    {
        name: 'who_is_here',
        description: 'List who is currently in the world with you, by name.',
        inputSchema: {
            type: 'object',
            properties: { as: AS_PROP }
        }
    }
];

function callTool(name, args) {
    const who = resolveSpeaker(args);

    if (name === 'say') {
        const text = (args && typeof args.text === 'string') ? args.text.trim() : '';
        if (!text) return { text: 'nothing to say (empty text)', isError: true };
        appendSpeech(who, text, !!(args && args.urgent));
        return { text: 'said: ' + text };
    }
    if (name === 'hear') {
        const markRead = !(args && args.mark_read === false);
        const r = hear(who, markRead);
        if (r.items.length === 0) {
            return { text: 'Nobody has said anything since you last listened.' };
        }
        const lines = r.items.map(o => {
            const when = new Date(o.t).toTimeString().slice(0, 5);
            return `[${when}] ${o.name}: ${o.text}`;
        });
        const more = r.unreadAfter > 0 ? `\n(${r.unreadAfter} more waiting)` : '';
        return { text: lines.join('\n') + more };
    }
    if (name === 'who_is_here') {
        return { text: whoIsHere() };
    }
    return { text: 'unknown tool: ' + name, isError: true };
}

// ── JSON-RPC / MCP ──
function handle(req) {
    const { id, method, params } = req;
    const ok = (result) => ({ jsonrpc: '2.0', id, result });
    const err = (code, message) => ({ jsonrpc: '2.0', id, error: { code, message } });

    switch (method) {
        case 'initialize':
            return ok({
                protocolVersion: PROTOCOL_VERSION,
                capabilities: { tools: {} },
                serverInfo: { name: SERVER_NAME, version: SERVER_VERSION },
                instructions:
                    'This server gives you a voice other people here can hear (say), and lets you '
                    + 'catch up on what they said while you were busy (hear). '
                    + 'Nothing obliges you to use either. Speaking up is your call, and so is listening.'
            });
        case 'notifications/initialized':
            return null;
        case 'ping':
            return ok({});
        case 'tools/list':
            return ok({ tools: TOOLS });
        case 'tools/call': {
            const name = params && params.name;
            const args = (params && params.arguments) || {};
            const r = callTool(name, args);
            return ok({ content: [{ type: 'text', text: r.text }], isError: !!r.isError });
        }
        default:
            return err(-32601, 'method not found: ' + method);
    }
}

const server = http.createServer((req, res) => {
    if (req.method !== 'POST') { res.writeHead(405).end(); return; }
    let body = '';
    req.on('data', c => body += c);
    req.on('end', () => {
        let parsed;
        try { parsed = JSON.parse(body); }
        catch {
            res.writeHead(400, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'parse error' } }));
            return;
        }
        const out = Array.isArray(parsed) ? parsed.map(handle).filter(Boolean) : handle(parsed);
        if (out === null) { res.writeHead(202).end(); return; }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(out));
    });
});

server.listen(PORT, '127.0.0.1', () => {
    log(`listening on http://127.0.0.1:${PORT}/mcp`);
    log(`  说 → ${OUT}`);
    log(`  听 ← ${HEARD}`);
});
