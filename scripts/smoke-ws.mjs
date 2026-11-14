// 端到端联调脚本：两个 WebSocket 客户端 + 断线补发校验。
// 用法: node scripts/smoke-ws.mjs [ws://localhost:8080/ws] [boardId]
// 退出码 0 = 全通过；1 = 有断言失败。

const URL_ = process.argv[2] ?? 'ws://localhost:8080/ws';
const BOARD = process.argv[3] ?? 'b_smoke';
const HTTP_BASE = URL_.replace(/^ws/, 'http').replace(/\/ws.*$/, '');

/** 取一个游客 token（WS 需要鉴权，见 docs/05 §3）。 */
async function guestToken() {
  const res = await fetch(`${HTTP_BASE}/api/auth/guest`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ nickname: 'smoke' }),
  });
  if (!res.ok) throw new Error(`guest login failed: HTTP ${res.status}`);
  const json = await res.json();
  const token = json?.data?.token;
  if (!token) throw new Error('guest login returned no token');
  return token;
}

const results = [];
const pass = (name) => {
  results.push([true, name]);
  console.log(`  PASS  ${name}`);
};
const fail = (name, detail) => {
  results.push([false, name, detail]);
  console.log(`  FAIL  ${name}${detail ? ` :: ${detail}` : ''}`);
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function open(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    const timer = setTimeout(() => reject(new Error('connect timeout')), 5000);
    ws.addEventListener('open', () => {
      clearTimeout(timer);
      resolve(ws);
    });
    ws.addEventListener('error', (e) => {
      clearTimeout(timer);
      reject(new Error(`ws error: ${e.message ?? 'unknown'}`));
    });
  });
}

/** 收集消息，支持按条件等待。 */
function tracker(ws) {
  const seen = [];
  const waiters = [];
  ws.addEventListener('message', (ev) => {
    const msg = JSON.parse(ev.data);
    seen.push(msg);
    for (const w of [...waiters]) {
      if (w.pred(msg)) {
        w.resolve(msg);
        waiters.splice(waiters.indexOf(w), 1);
      }
    }
  });
  return {
    seen,
    waitFor(pred, timeout = 4000, label = 'message') {
      const hit = seen.find(pred);
      if (hit) return Promise.resolve(hit);
      return new Promise((resolve, reject) => {
        const t = setTimeout(() => {
          waiters.splice(
            waiters.findIndex((w) => w.resolve === resolve),
            1,
          );
          reject(new Error(`timeout waiting ${label}`));
        }, timeout);
        waiters.push({
          pred,
          resolve: (m) => {
            clearTimeout(t);
            resolve(m);
          },
        });
      });
    },
  };
}

function stroke(id, pts = [10, 10, 20, 20, 30, 35]) {
  return { t: 'stroke.append', pageId: 'p_1', strokeId: id, tool: 'pen', color: '#111111', width: 4, pts };
}

async function main() {
  console.log(`smoke: ${URL_} board=${BOARD}`);

  // 1. 两个客户端加入
  const tokenA = await guestToken();
  const tokenB = await guestToken();
  const wsUrlA = `${URL_}?token=${encodeURIComponent(tokenA)}`
  const wsUrlB = `${URL_}?token=${encodeURIComponent(tokenB)}`
  const a = await open(wsUrlA);
  const at = tracker(a);
  a.send(JSON.stringify({ t: 'join', boardId: BOARD, lastSeq: 0, nickname: 'A' }));
  const initA = await at.waitFor((m) => m.t === 'init', 5000, 'init A');
  pass(`A 加入成功 seq=${initA.seq} pages=${initA.doc?.pages?.length}`);

  const b = await open(wsUrlB);
  const bt = tracker(b);
  b.send(JSON.stringify({ t: 'join', boardId: BOARD, lastSeq: initA.seq, nickname: 'B' }));
  await bt.waitFor((m) => m.t === 'init', 5000, 'init B');
  pass('B 加入成功');

  // 两人光标颜色必须不同（同房间内服务端会避让撞色）
  const presence = await at.waitFor(
    (m) => m.t === 'presence' && (m.members ?? []).length >= 2,
    5000,
    'presence 2 人',
  );
  const colors = new Set((presence.members ?? []).map((m) => m.color));
  colors.size >= 2
    ? pass(`两人光标颜色不同: ${[...colors].join(', ')}`)
    : fail('两人光标颜色不同', `撞色 ${[...colors].join(', ')}`);

  // 2. A 画一笔 -> B 应收到增量 + 定序 op，A 应收到 ack
  a.send(JSON.stringify(stroke('s_1')));
  await bt.waitFor((m) => m.t === 'stroke.append' && m.strokeId === 's_1', 3000, 'B 收到笔划增量');
  pass('B 收到实时笔划增量');

  a.send(JSON.stringify({ t: 'stroke.end', cid: 'c-1', strokeId: 's_1' }));
  const op1 = await bt.waitFor((m) => m.t === 'op' && m.cmd?.t === 'stroke.add', 4000, 'B 收到 stroke.add');
  pass(`B 收到定序 op seq=${op1.seq}`);

  const ack = await at.waitFor((m) => m.t === 'ack' && m.cid === 'c-1', 4000, 'A 收到 ack');
  pass(`A 收到 ack seq=${ack.seq}`);

  if (op1.seq !== ack.seq) fail('ack 与 op 的 seq 一致', `${ack.seq} != ${op1.seq}`);
  else pass('ack 与 op 的 seq 一致');

  // 3. seq 连续
  const ops = at.seen.filter((m) => m.t === 'op');
  const seqs = ops.map((m) => m.seq);
  const contiguous = seqs.every((s, i) => i === 0 || s === seqs[i - 1] + 1);
  contiguous ? pass(`seq 连续: ${seqs.join(',')}`) : fail('seq 连续', seqs.join(','));

  // 4. B 断线，A 再画一笔，B 带 lastSeq 重连应补发
  const lastSeq = op1.seq;
  b.close();
  await sleep(300);

  a.send(JSON.stringify(stroke('s_2')));
  a.send(JSON.stringify({ t: 'stroke.end', cid: 'c-2', strokeId: 's_2' }));
  await at.waitFor((m) => m.t === 'op' && m.seq === lastSeq + 1, 4000, 'A 收到第二笔 op');
  pass(`A 的第二笔已定序 seq=${lastSeq + 1}`);

  const b2 = await open(wsUrlB);
  const b2t = tracker(b2);
  b2.send(JSON.stringify({ t: 'join', boardId: BOARD, lastSeq, nickname: 'B' }));
  const replay = await b2t.waitFor((m) => m.t === 'op' && m.seq === lastSeq + 1, 5000, 'B 补发缺口');
  pass(`B 重连补发成功 seq=${replay.seq}`);

  // 5. 未 join 就发消息 -> UNAUTHORIZED
  const c = await open(wsUrlB);
  const ct = tracker(c);
  c.send(JSON.stringify(stroke('s_3')));
  const err = await ct.waitFor((m) => m.t === 'error', 3000, '未认证错误');
  err.code === 'UNAUTHORIZED'
    ? pass('未 join 发消息被拒 (UNAUTHORIZED)')
    : fail('未 join 发消息被拒', `code=${err.code}`);

  for (const ws of [a, b2, c]) {
    try {
      ws.close();
    } catch {
      /* ignore */
    }
  }
  // 等 socket 真正关闭再退出，避免 libuv 在进程退出时抛 UV_HANDLE_CLOSING 断言。
  await sleep(150);

  const failed = results.filter((r) => !r[0]);
  console.log(`\n${results.length - failed.length}/${results.length} passed`);
  process.exitCode = failed.length === 0 ? 0 : 1;
}

main().catch((e) => {
  console.error('SMOKE ERROR:', e.message);
  process.exitCode = 1;
});

