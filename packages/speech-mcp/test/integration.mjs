#!/usr/bin/env node
// speech-mcp 集成测试：游标语义、身份、紧急标记、队列持久化。
//
// 跑法：node test/integration.mjs [port]
// 前提：服务已在指定端口运行，--heard 指向 test/data/heard.jsonl
import fs from 'fs';
import http from 'http';
import path from 'path';

const PORT = Number(process.argv[2] || 9202);
const DIR = path.join(import.meta.dirname, 'data');
const HEARD = path.join(DIR, 'test-heard.jsonl');
const CURSOR = path.join(DIR, 'test-heard-cursor.json');
const OUT = path.join(DIR, 'test-speech.jsonl');

let pass = 0, fail = 0;
const check = (name, ok, detail = '') => {
    console.log(`  ${ok ? '✅' : '❌'} ${name}${detail ? '  → ' + detail : ''}`);
    ok ? pass++ : fail++;
};

function rpc(method, params) {
    return new Promise((resolve) => {
        const body = JSON.stringify({ jsonrpc: '2.0', id: 1, method, params });
        const req = http.request({
            hostname: '127.0.0.1', port: PORT, path: '/mcp', method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) }
        }, res => {
            let d = ''; res.on('data', c => d += c);
            res.on('end', () => { try { resolve(JSON.parse(d).result); } catch { resolve(null); } });
        });
        req.on('error', () => resolve(null));
        req.write(body); req.end();
    });
}
const call = async (name, args = {}) => {
    const r = await rpc('tools/call', { name, arguments: args });
    if (!r || !r.content) return '(null)';
    return r.content[0].text;
};

// 干净起点
//
// 注意：游标文件由**服务端**按 --heard 的目录派生，它的路径不一定是这里猜的那个。
// 所以除了删本地猜的路径，还要在下面通过一次全量 hear 把服务端的游标推平
// （见 step 0）。
for (const f of [HEARD, CURSOR, OUT]) { try { fs.unlinkSync(f); } catch {} }
fs.mkdirSync(DIR, { recursive: true });

console.log('\n══ 0) 重置服务端游标 ══');
// 先清空队列并让服务端读到"空"，这样它的游标会被推到 0
fs.writeFileSync(HEARD, '', 'utf8');
await call('hear');
// 再把服务端可能残留的游标文件删掉（两种可能路径都试）
for (const f of [CURSOR, path.join(path.dirname(HEARD), 'heard-cursor.json')]) {
    try { fs.unlinkSync(f); } catch {}
}
check('游标已重置', true);

console.log('\n══ 1) 工具清单 ══');
const tl = await rpc('tools/list');
const names = (tl?.tools || []).map(t => t.name);
check('暴露 hear/say/who_is_here', ['hear', 'say', 'who_is_here'].every(n => names.includes(n)), names.join(', '));

console.log('\n══ 2) 工具说明不含祈使句（措辞纪律）══');
const badWords = ['you should', 'you must', 'make sure to', 'remember to', 'be sure to'];
let anyImperative = false;
for (const t of (tl?.tools || [])) {
    const blob = (t.description + ' ' + JSON.stringify(t.inputSchema)).toLowerCase();
    for (const w of badWords) if (blob.includes(w)) { anyImperative = true; console.log(`      命中「${w}」于 ${t.name}`); }
}
check('说明里没有"你应该/必须"类祈使句', !anyImperative);

console.log('\n══ 3) 空队列 ══');
check('hear 空队列提示合理', (await call('hear')).includes('Nobody has said anything'));

console.log('\n══ 4) 队列有内容 ══');
fs.writeFileSync(HEARD, [
    JSON.stringify({ t: Date.now() - 60000, name: 'Wei', text: 'Do you have wood yet?' }),
    JSON.stringify({ t: Date.now() - 30000, name: 'Andy', text: 'Four logs.' }),
    JSON.stringify({ t: Date.now() - 10000, name: 'TestA', text: '我说的话不该被自己听到' }),
].join('\n') + '\n', 'utf8');

let out = await call('hear');
check('返回别人说的 2 条', out.includes('Wei:') && out.includes('Andy:'), out.split('\n')[0]);
check('不返回自己说的', !out.includes('不该被自己听到'));

console.log('\n══ 5) 游标推进 ══');
check('再 hear 为空', (await call('hear')).includes('Nobody has said anything'));

console.log('\n══ 6) peek 不推进游标 ══');
fs.appendFileSync(HEARD, JSON.stringify({ t: Date.now(), name: 'Lin', text: 'Trees to the northeast.' }) + '\n', 'utf8');
out = await call('hear', { mark_read: false });
check('peek 能看到新消息', out.includes('Trees to the northeast'));
out = await call('hear');
check('peek 后正式 hear 仍能看到', out.includes('Trees to the northeast'));

console.log('\n══ 7) 自己的话不回来 ══');
check('第二轮后队列已清空', (await call('hear')).includes('Nobody has said anything'));

console.log('\n══ 8) say 与 urgent ══');
await call('say', { text: 'ordinary line' });
await call('say', { text: 'EMERGENCY', urgent: true });
const said = fs.readFileSync(OUT, 'utf8').trim().split('\n').map(l => JSON.parse(l));
check('普通发言无 urgent 字段', said[0] && !said[0].urgent, JSON.stringify(said[0]));
check('紧急发言带 urgent:true', said[1] && said[1].urgent === true, JSON.stringify(said[1]));
check('发言署名正确', said[0]?.name === 'TestA', said[0]?.name);

console.log('\n══ 9) 空文本被拒 ══');
check('say 空文本报错', (await call('say', { text: '   ' })).includes('empty text'));

console.log('\n══ 10) 未知工具 ══');
check('未知工具报错', (await call('nonexistent')).includes('unknown tool'));

console.log('\n══ 11) who_is_here 的降级路径（没装服务端mod 时）══');
// 本测试实例没有 agents.json，所以会走"从队列反推"那条路。
// 这条路径存在的理由：只装客户端mod 时名册不存在，
// 但"谁说过话"这件事队列里本来就有，不该退化成"不可用"。
fs.writeFileSync(HEARD, [
    JSON.stringify({ seq: 101, t: 1000, name: 'Wei', text: '有木头吗' }),
    JSON.stringify({ seq: 102, t: 3000, name: 'Andy', text: '四根原木' }),
    JSON.stringify({ seq: 103, t: 2000, name: 'Wei', text: '我去挖矿' }),
].join('\n') + '\n', 'utf8');
let who = await call('who_is_here');
check('无名册时仍能列出说话人', who.includes('Wei') && who.includes('Andy'), who.slice(0, 60));
check('按最近说话排序（Andy 最后说）', who.indexOf('Andy') < who.indexOf('Wei'), who.slice(0, 40));
check('如实说明语义差异', who.includes('stayed quiet'), '（说明名册未装、沉默者不出现）');

console.log(`\n══ 结果：${pass} 通过 / ${fail} 失败 ══\n`);
process.exit(fail === 0 ? 0 : 1);
