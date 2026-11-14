// REST 契约自检：按 docs/05-接口示例.md 断言身份与作品库接口。
// 用法: node scripts/smoke-api.mjs [http://127.0.0.1:8080]
// 退出码 0 = 全通过。

const BASE = (process.argv[2] ?? 'http://127.0.0.1:8080').replace(/\/$/, '');
const results = [];
const pass = (n) => {
  results.push([true, n]);
  console.log(`  PASS  ${n}`);
};
const fail = (n, d) => {
  results.push([false, n, d]);
  console.log(`  FAIL  ${n}${d ? ` :: ${d}` : ''}`);
};
const expect = (cond, name, detail) => (cond ? pass(name) : fail(name, detail));

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
    /* 非 JSON 响应保持 null */
  }
  return { status: res.status, json, text };
}

const stamp = Date.now().toString(36);
const userA = `alpha_${stamp}`;
const userB = `beta_${stamp}`;
const pwd = 'Abc12345';

async function main() {
  console.log(`smoke-api: ${BASE}`);

  // 1. 游客身份
  const guest = await call('POST', '/api/auth/guest', { body: { nickname: '路人甲' } });
  expect(guest.status === 200 && guest.json?.data?.token, '游客会话创建成功');
  const guestToken = guest.json?.data?.token;
  expect(guest.json?.data?.user?.guest === true, '游客标记 guest=true');

  // 2. 游客不能建板
  const guestCreate = await call('POST', '/api/boards', { token: guestToken, body: { name: '游客的板' } });
  expect(guestCreate.status === 403, '游客建板被拒 (403)', `got ${guestCreate.status}`);

  // 3. 注册 A / 重复用户名 / 错误密码登录
  const regA = await call('POST', '/api/auth/register', {
    body: { username: userA, password: pwd, nickname: '甲' },
  });
  expect(regA.status === 200 && regA.json?.data?.token, '注册 A 成功');
  const tokenA = regA.json?.data?.token;

  const dup = await call('POST', '/api/auth/register', {
    body: { username: userA, password: pwd, nickname: '甲2' },
  });
  expect(dup.status === 409, '重复用户名 409', `got ${dup.status}`);

  const badLogin = await call('POST', '/api/auth/login', { body: { username: userA, password: 'wrong123' } });
  expect(badLogin.status === 401, '错误密码 401', `got ${badLogin.status}`);

  const login = await call('POST', '/api/auth/login', { body: { username: userA, password: pwd } });
  expect(login.status === 200 && login.json?.data?.token, '登录 A 成功');

  const me = await call('GET', '/api/auth/me', { token: tokenA });
  expect(me.status === 200 && me.json?.data?.username === userA, 'GET /me 返回正确用户');

  // 4. A 建板
  const create = await call('POST', '/api/boards', { token: tokenA, body: { name: '动物速写' } });
  expect(create.status === 200 && create.json?.data?.id, 'A 创建白板成功');
  const boardId = create.json?.data?.id;
  expect(create.json?.data?.role === 'owner', '创建者角色 owner');

  // 5. A 的列表能看到
  const listA = await call('GET', '/api/boards?scope=mine', { token: tokenA });
  const itemsA = listA.json?.data?.items ?? [];
  expect(itemsA.some((b) => b.id === boardId), 'A 的列表包含新板');

  // 6. B 注册后看不到
  const regB = await call('POST', '/api/auth/register', {
    body: { username: userB, password: pwd, nickname: '乙' },
  });
  const tokenB = regB.json?.data?.token;
  expect(regB.status === 200 && tokenB, '注册 B 成功');

  const listB = await call('GET', '/api/boards?scope=mine', { token: tokenB });
  const itemsB = listB.json?.data?.items ?? [];
  expect(!itemsB.some((b) => b.id === boardId), 'B 的列表看不到 A 的板');

  // 7. B 不能改 A 的板
  const patchB = await call('PATCH', `/api/boards/${boardId}`, { token: tokenB, body: { name: '改名试试' } });
  expect(patchB.status === 403, 'B 改 A 的板被拒 (403)', `got ${patchB.status}`);

  // 8. A 可以改名
  const patchA = await call('PATCH', `/api/boards/${boardId}`, { token: tokenA, body: { name: '动物速写 2' } });
  expect(patchA.status === 200 || patchA.status === 204, 'A 改名成功', `got ${patchA.status}`);

  // 9. 缩略图
  const png =
    'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
  const thumb = await call('PUT', `/api/boards/${boardId}/thumbnail`, { token: tokenA, body: { dataUrl: png } });
  expect(thumb.status === 200 || thumb.status === 204, '上传缩略图成功', `got ${thumb.status}`);

  // 10. 详情
  const detail = await call('GET', `/api/boards/${boardId}`, { token: tokenA });
  expect(detail.status === 200 && detail.json?.data?.name === '动物速写 2', '详情返回改名后的名称');
  expect(detail.json?.data?.role === 'owner', '详情里角色为 owner');

  const failed = results.filter((r) => !r[0]);
  console.log(`\n${results.length - failed.length}/${results.length} passed`);
  process.exitCode = failed.length === 0 ? 0 : 1;
}

main().catch((e) => {
  console.error('SMOKE-API ERROR:', e.message);
  process.exitCode = 1;
});
