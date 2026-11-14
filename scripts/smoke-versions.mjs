// Sprint B 端到端自检：版本存档→回滚、归档只读、回收站、复制。
// 用法: node scripts/smoke-versions.mjs [http://127.0.0.1:8080]
// 退出码 0 = 全通过。

const BASE = (process.argv[2] ?? 'http://127.0.0.1:8080').replace(/\/$/, '');
const WS_BASE = BASE.replace(/^http/, 'ws') + '/ws';

const results = [];
const pass = (n) => {
  results.push([true, n]);
  console.log(`  PASS  ${n}`);
};
const fail = (n, d) => {
  results.push([false, n, d]);
  console.log(`  FAIL  ${n}${d ? ` :: ${d}` : ''}`);
};
const expect = (c, n, d) => (c ? pass(n) : fail(n, d));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function call(method, path, { token, body } = {}) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* ignore */
  }
  return { status: res.status, json };
}

/** 极简 WS 客户端：收集消息并按条件等待。 */
async function connect(token) {
  const ws = new WebSocket(`${WS_BASE}?token=${encodeURIComponent(token)}`);
  const seen = [];
  const waiters = [];
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('ws connect timeout')), 5000);
    ws.addEventListener('open', () => {
      clearTimeout(t);
      resolve();
    });
  });
  ws.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data);
    seen.push(m);
    for (const w of [...waiters]) {
      if (w.pred(m)) {
        w.resolve(m);
        waiters.splice(waiters.indexOf(w), 1);
      }
    }
  });
  return {
    ws,
    seen,
    send: (o) => ws.send(JSON.stringify(o)),
    waitFor(pred, timeout = 6000, label = 'message') {
      const hit = seen.find(pred);
      if (hit) return Promise.resolve(hit);
      return new Promise((resolve, reject) => {
        const t = setTimeout(() => reject(new Error(`timeout: ${label}`)), timeout);
        waiters.push({
          pred,
          resolve: (m) => {
            clearTimeout(t);
            resolve(m);
          },
        });
      });
    },
    close: () => ws.close(),
  };
}

function strokeAppend(pageId, id, pts) {
  return { t: 'stroke.append', pageId, strokeId: id, tool: 'pen', color: '#111111', width: 4, pts };
}

async function main() {
  console.log(`smoke-versions: ${BASE}`);
  const username = `vb_${Date.now().toString(36)}`;
  const reg = await call('POST', '/api/auth/register', {
    body: { username, password: 'Abc12345', nickname: '版本测试' },
  });
  const token = reg.json?.data?.token;
  expect(!!token, '注册成功');

  const created = await call('POST', '/api/boards', { token, body: { name: '版本验证板' } });
  const boardId = created.json?.data?.id;
  expect(!!boardId, `创建白板 ${boardId}`);

  // 进房间，画两笔
  const c = await connect(token);
  c.send({ t: 'join', boardId, lastSeq: 0 });
  const init = await c.waitFor((m) => m.t === 'init', 6000, 'init');
  const pageId = init.doc?.pages?.[0]?.id ?? 'p_1';
  expect(init.me?.color != null, `init.me 带光标颜色 ${init.me?.color}`);

  for (const id of ['s_a', 's_b']) {
    c.send(strokeAppend(pageId, id, [10, 10, 20, 20, 30, 30]));
    c.send({ t: 'stroke.end', cid: `c-${id}`, strokeId: id });
    await c.waitFor((m) => m.t === 'op' && m.cmd?.stroke?.id === id, 6000, `op ${id}`);
  }
  pass('画了两笔并定序');

  // 手动存档
  const saved = await call('POST', `/api/boards/${boardId}/versions`, {
    token,
    body: { label: '存档点' },
  });
  expect(saved.status === 200 && saved.json?.data?.versionNo >= 1, `手动存档 v${saved.json?.data?.versionNo}`);

  // 再画一笔
  c.send(strokeAppend(pageId, 's_c', [40, 40, 50, 50]));
  c.send({ t: 'stroke.end', cid: 'c-s_c', strokeId: 's_c' });
  await c.waitFor((m) => m.t === 'op' && m.cmd?.stroke?.id === 's_c', 6000, 'op s_c');
  pass('第三笔已定序');

  // 回滚到存档点
  const versions = await call('GET', `/api/boards/${boardId}/versions?page=1&size=20`, { token });
  const manual = (versions.json?.data?.items ?? []).find((v) => v.kind === 'MANUAL');
  expect(!!manual, '版本列表含 MANUAL');

  const restore = await call('POST', `/api/boards/${boardId}/versions/${manual.id}/restore`, { token });
  expect(restore.status === 200, `回滚成功 seq=${restore.json?.data?.seq}`);

  const reload = await c.waitFor((m) => m.t === 'board.reload', 8000, 'board.reload');
  expect(reload.reason === 'restore', `收到 board.reload reason=${reload.reason}`);
  const reinited = await c.waitFor((m) => m.t === 'init' && m.seq >= reload.seq, 8000, '回滚后 init');
  const elementCount = reinited.doc?.pages?.flatMap((p) => p.elements ?? []).length;
  expect(elementCount === 2, `回滚后元素回到 2 个（实际 ${elementCount}）`);

  const afterRestore = await call('GET', `/api/boards/${boardId}/versions?page=1&size=20`, { token });
  expect((afterRestore.json?.data?.items ?? []).some((v) => v.kind === 'PRE_RESTORE'), '回滚前自动生成 PRE_RESTORE');

  // 归档 -> 只读
  const archive = await call('PATCH', `/api/boards/${boardId}`, { token, body: { status: 'archived' } });
  expect(archive.status === 200, '归档成功');

  c.send(strokeAppend(pageId, 's_d', [60, 60, 70, 70]));
  const err = await c.waitFor((m) => m.t === 'error', 6000, 'READ_ONLY');
  expect(err.code === 'READ_ONLY', `归档板写入被拒 ${err.code}`);

  const listed = await call('GET', '/api/boards?status=archived&scope=mine', { token });
  expect((listed.json?.data?.items ?? []).some((b) => b.id === boardId), '归档列表可见');

  // 取消归档
  await call('PATCH', `/api/boards/${boardId}`, { token, body: { status: 'active' } });
  pass('取消归档');

  // 回收站
  const del = await call('DELETE', `/api/boards/${boardId}`, { token });
  expect(del.status === 200 || del.status === 204, '删除进回收站');
  const def = await call('GET', '/api/boards?scope=mine', { token });
  expect(!(def.json?.data?.items ?? []).some((b) => b.id === boardId), '默认列表不含回收站');
  const trash = await call('GET', '/api/boards?status=trashed&scope=mine', { token });
  expect((trash.json?.data?.items ?? []).some((b) => b.id === boardId), '回收站列表可见');

  const back = await call('POST', `/api/boards/${boardId}/trash/restore`, { token });
  expect(back.status === 200, '从回收站恢复');

  // 复制
  const dup = await call('POST', `/api/boards/${boardId}/duplicate`, { token });
  const dupId = dup.json?.data?.id;
  expect(dup.status === 200 && dupId && dupId !== boardId, `复制成新板 ${dupId}`);
  const dupDetail = await call('GET', `/api/boards/${dupId}`, { token });
  expect(dupDetail.json?.data?.role === 'owner', '复制板 owner 是自己');

  c.close();
  await sleep(150);

  const failed = results.filter((r) => !r[0]);
  console.log(`\n${results.length - failed.length}/${results.length} passed`);
  process.exitCode = failed.length === 0 ? 0 : 1;
}

main().catch((e) => {
  console.error('SMOKE-VERSIONS ERROR:', e.message);
  process.exitCode = 1;
});
