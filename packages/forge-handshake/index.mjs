/**
 * mineflayer 插件：Forge 1.20.1 客户端握手
 * ==========================================================================
 * 全部结论均来自真实 Forge 客户端抓包（proxy-capture.log）与 Forge 字节码反汇编。
 *
 * 【一】Handshake 注入 FML 标记
 *   真实客户端 ServerAddress = "主机名\0FML3\0主机名"
 *   Forge 用 ConnectionType.forVersionFlag 找 "FML" 判定 MODDED。
 *
 * 【二】握手走原版 Login Plugin Request/Response
 *   data  = VarInt(len) + "fml:handshake" + VarInt(innerLen) + inner
 *   inner = VarInt 判别符 + VarInt 登录序号 + 消息体
 *   判别符：S2CModList=5、S2CChannelMismatchData=6、S2CRegistry=3、
 *           C2SModListReply=2、C2SAcknowledge=1
 *
 * 【三】S2CModList 体（实测 87 字节，无元素个数前缀，连续三元组）
 *   09"minecraft" 09"Minecraft" 06"1.20.1"
 *   05"forge"     05"Forge"     07"47.4.23"
 *   09"numen_api" 05"Numen"     05"0.1.3"
 *   05"numen"     05"Numen"     05"0.1.3"
 */

const CH_WRAPPER = 'fml:loginwrapper';
const CH_HS = 'fml:handshake';
/**
 * SimpleChannel 判别符（取自 NetworkInitialization 的字节码注册参数，实测确认）。
 * 注意 S2CModList 实际线上判别符是 5（注册参数与线上值不同，以抓包为准）。
 */
const DISC = {
  S2C_REGISTRY: 1,            // 服务端发来的注册表数据（本轮实测判别符=1）
  S2C_MOD_LIST: 5,            // 线上实测 = 5
  S2C_CHANNEL_MISMATCH: 6,
  C2S_ACKNOWLEDGE: 99,        // ★ 字节码：bipush 99
  C2S_MOD_LIST_REPLY: 2,      // 线上实测 = 2
};

// ---------------- 原语：显式偏移 ----------------
function readVarInt(b, off) {
  let v = 0, s = 0, p = off;
  while (true) { const x = b[p++]; v |= (x & 0x7f) << s; if ((x & 0x80) === 0) break; s += 7; if (s > 35) throw new Error('varint'); }
  return { value: v >>> 0, len: p - off };
}
function readString(b, off) {
  const r = readVarInt(b, off);
  const start = off + r.len;
  return { value: b.toString('utf8', start, start + r.value), len: r.len + r.value };
}
function writeVarInt(v) { const o = []; let x = v >>> 0; do { let t = x & 0x7f; x >>>= 7; if (x) t |= 0x80; o.push(t); } while (x); return Buffer.from(o); }
function writeString(s) { const b = Buffer.from(s, 'utf8'); return Buffer.concat([writeVarInt(b.length), b]); }
const hex = (b) => Buffer.from(b).toString('hex').replace(/(..)/g, '$1 ').trim();

// ---------------- 外层解包 ----------------
function decodeWrapper(data) {
  const raw = Buffer.from(data);
  const ch = readString(raw, 0);
  let p = ch.len;
  const il = readVarInt(raw, p); p += il.len;
  if (p + il.value > raw.length) throw new Error(`内层越界 ${p}+${il.value}>${raw.length}`);
  const inner = raw.subarray(p, p + il.value);
  const d = readVarInt(inner, 0);
  const li = readVarInt(inner, d.len);
  return { channel: ch.value, disc: d.value, loginIndex: li.value, body: inner.subarray(d.len + li.len) };
}

// ---------------- S2CModList ----------------
/** 无个数前缀，直接连续三元组；末尾若有剩余再读 channels / registries */
function parseModList(body) {
  let p = 0;
  const mods = [];
  while (p + 3 <= body.length) {
    const save = p;
    try {
      const id = readString(body, p);
      if (!/^[a-z][a-z0-9_]{1,40}$/.test(id.value)) { p = save; break; }
      const name = readString(body, p + id.len);
      const ver = readString(body, p + id.len + name.len);
      const total = id.len + name.len + ver.len;
      if (p + total > body.length) { p = save; break; }
      mods.push({ id: id.value, name: name.value, ver: ver.value });
      p += total;
    } catch { p = save; break; }
  }
  const chOrder = [], chVers = [], registries = [];
  if (p < body.length) {
    const nCh = readVarInt(body, p); p += nCh.len;
    for (let i = 0; i < nCh.value && p < body.length; i++) {
      const k = readString(body, p); p += k.len;
      const v = readString(body, p); p += v.len;
      chOrder.push(k.value); chVers.push(v.value);
    }
  }
  if (p < body.length) {
    const nRg = readVarInt(body, p); p += nRg.len;
    for (let i = 0; i < nRg.value && p < body.length; i++) { const r = readString(body, p); p += r.len; registries.push(r.value); }
  }
  return { mods, chOrder, chVers, registries, consumed: p };
}

/**
 * Forge 客户端固有通道表。
 * 来源：真实 Forge 1.20.1 客户端 C2SModListReply 抓包（proxy-capture.log 包[9]）。
 * 服务端的 S2CModList **不发送**这张表，所以只能写死。
 */
const FORGE_CLIENT_CHANNELS = [
  ['fml:loginwrapper', 'FML3'],
  ['forge:tier_sorting', '1.0'],
  ['fml:handshake', 'FML3'],
  ['numen_api:main', '1'],
  ['minecraft:unregister', 'FML3'],
  ['fml:play', 'FML3'],
  ['minecraft:register', 'FML3'],
  ['forge:split', '1.1'],
];

/**
 * C2SModListReply —— 布局已通过枚举验证（体 = 195 字节，与服务端期望精确吻合）。
 *
 *   段 1: mods      —— 每个 mod 只发 **扁平 modId 字符串**，**没有元素个数前缀**
 *                      09"minecraft" 05"forge" 09"numen_api" 05"numen"   = 31 字节
 *   段 2: channels  —— VarInt 数量 + 每组 (名, 版本)
 *                      08 + 8 组                                     = 163 字节
 *   段 3: registries—— **不存在**（服务端不读这一段）
 *
 * 对照组：真客户端 body = 195 字节，与本编码长度一致。
 * （S2CModList 的 mods 段是三元组，但 C2SModListReply 是扁平 modId —— 方向不同，格式不同。）
 */
function encodeReply(m) {
  const parts = [];
  // 段 1：扁平 modId，无计数
  for (const x of m.mods) parts.push(writeString(x.id));
  // 段 2：channels
  parts.push(writeVarInt(FORGE_CLIENT_CHANNELS.length));
  for (const [k, v] of FORGE_CLIENT_CHANNELS) { parts.push(writeString(k)); parts.push(writeString(v)); }
  // 段 3：registries 数量 = 0（1 字节）。
  //   服务端在读完 channels 后仍要再读 1 字节；实测补上这个 0x00 后
  //   体长变为 195，与真客户端抓包精确一致。
  parts.push(writeVarInt(0));
  return Buffer.concat(parts);
}

/**
 * 同步安装 Forge 握手支持（不经过 bot.loadPlugin）。
 *
 * 为什么需要这个：
 *   bot.loadPlugin() 在 createBot() 返回时**尚未执行**（mineflayer 要等
 *   'inject_allowed' 事件，而那是 setTimeout(...,0) 才触发的）。
 *   握手包必须在此之前被改写，所以必须同步装上监听器。
 *
 * 配合用法（见 src/utils/mcdata.js）：
 *   options.wait_connect = true          // 让 setProtocol 等 connect_allowed
 *   const bot = createBot(options)
 *   installForgeHandshake(bot, opts)     // 同步装好
 *   bot._client.emit('connect_allowed')  // 现在放行，握手包会被改写
 */
export function installForgeHandshake(botOrClient, opts = {}) {
  // 兼容直接传 mineflayer client
  const client = botOrClient._client ? botOrClient._client : botOrClient;

  // 幂等保护：重复安装会把 client.write 包装两层，导致 ServerAddress 被注入两次而使
  // 服务端解析错位（症状：readerIndex(1) + length(1) exceeds writerIndex）。
  if (client.__dshForgeInstalled) return { uninstall() {} };
  client.__dshForgeInstalled = true;
  const FML_VERSION = opts.fmlVersion || 'FML3';
  const log = opts.verbose === false ? () => {} : (...a) => console.log('[forge]', ...a);
  const dbg = opts.debug === true;

  // 一、Handshake 注入（必须在 set_protocol 发出前挂上）
  const origWrite = client.write.bind(client);
  client.write = (name, packet) => {
    if (name === 'set_protocol' && packet && typeof packet.serverHost === 'string' && !packet.serverHost.includes('\0FML')) {
      const o = packet.serverHost;
      packet = { ...packet, serverHost: `${o}\0${FML_VERSION}\0${o}` };
      log(`注入 FML 标记 -> "${packet.serverHost}"`);
    }
    return origWrite(name, packet);
  };

  // 二、握手应答
  function send(messageId, disc, loginIndex, msgBody) {
    const inner = Buffer.concat([writeVarInt(disc), writeVarInt(loginIndex), msgBody]);
    const cb = Buffer.from(CH_HS, 'utf8');
    const wrapped = Buffer.concat([writeVarInt(cb.length), cb, writeVarInt(inner.length), inner]);
    if (dbg) log(`[tx] 判别符=${disc} 序号=${loginIndex} 内层=${inner.length}B 体=${msgBody.length}B`);
    if (client.state === 'play') client.write('custom_payload', { channel: CH_WRAPPER, data: wrapped });
    else client.write('login_plugin_response', { messageId, successful: true, data: wrapped });
  }

  function handle(packet) {
    const { messageId, channel, data } = packet;
    if (dbg) log(`[rx-raw] messageId=${messageId} channel="${channel}" dataLen=${data ? data.length : 0}`);
    if (channel !== CH_WRAPPER) {
      log(`通道不是 loginwrapper（"${channel}"），回 successful=false`);
      client.write('login_plugin_response', { messageId, successful: false });
      return;
    }
    let w;
    try { w = decodeWrapper(data); }
    catch (e) { log(`解包失败: ${e.message}`); client.write('login_plugin_response', { messageId, successful: false }); return; }
    if (dbg) log(`[rx] 判别符=${w.disc} 序号=${w.loginIndex} 体=${w.body.length}B`);

    if (w.disc === DISC.S2C_MOD_LIST) {
      const m = parseModList(w.body);
      log(`mods(${m.mods.length}): ${m.mods.map(x => x.id + '/' + x.ver).join(', ')}`);
      const reply = encodeReply(m);
      send(messageId, DISC.C2S_MOD_LIST_REPLY, w.loginIndex, reply);
      log(`-> C2SModListReply 体=${reply.length}B`);
      return;
    }
    send(messageId, DISC.C2S_ACKNOWLEDGE, w.loginIndex, Buffer.alloc(0));
    if (dbg) log(`-> C2SAcknowledge 判别符=${DISC.C2S_ACKNOWLEDGE} 序号=${w.loginIndex}`);
  }

  client.prependListener('login_plugin_request', (packet) => {
    try { handle(packet); } catch (e) { log(`[!] 握手处理异常: ${e.message}`); }
  });

  return { uninstall() { client.write = origWrite; } };
}

export function forgeHandshake(bot, opts = {}) {
  return installForgeHandshake(bot, opts);
}

export default forgeHandshake;
