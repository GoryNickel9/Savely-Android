import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const reportPath = path.join(repository, 'artifacts', 'web-read-smoke-result.json');
const PROJECT = 'https://crqnfbahytzenisospcx.supabase.co';
const SITE = 'https://www.savely.cc';
const QA_ID = '9daffd41-39bf-4b1e-be68-abf12549c4a7';
const QA_EMAIL = 'e2e-savely@example.com';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const STORAGE_KEY = 'sb-crqnfbahytzenisospcx-auth-token';

class SmokeError extends Error {
  constructor(code) { super(code); this.code = code; }
}
const check = (condition, code) => { if (!condition) throw new SmokeError(code); };

function rowQuery(expected) {
  // Reconcile by identity, never by mutable description or payload: changed rows must remain visible.
  const query = new URLSearchParams({ select: 'id,user_id,description,amount,currency,date,type,category_id,exchange_rate_eur',
    id: `eq.${expected.id}`, user_id: `eq.${expected.user_id}` });
  return `rest/v1/transactions?${query}`;
}

function ownRow(row, expected) {
  return row?.id === expected.id && row.user_id === expected.user_id && row.description === expected.description &&
    /^0\.010*$/.test(String(row.amount)) && row.currency === 'EUR' && row.date === expected.date &&
    row.type === 'expense' && row.category_id === null && /^1(?:\.0+)?$/.test(String(row.exchange_rate_eur));
}

function nativeFixture(checkpoint, runId, sessionId, cleanup = false) {
  check(checkpoint && checkpoint.run_id === runId && checkpoint.session_id === sessionId &&
    checkpoint.user_id === QA_ID && checkpoint.email === QA_EMAIL && checkpoint.project_host === new URL(PROJECT).host &&
    checkpoint.transaction_id === runId && checkpoint.marker === `Android-Web-QA-${runId}` &&
    /^\d{4}-\d{2}-\d{2}$/.test(checkpoint.date ?? '') &&
    checkpoint.amount === '0.01' && checkpoint.currency === 'EUR' && checkpoint.type === 'expense' &&
    checkpoint.category_id === null && checkpoint.exchange_rate_eur === '1' && checkpoint.creation_attempted === true &&
    (cleanup ? ['creating', 'native_prepared'].includes(checkpoint.stage) :
      checkpoint.stage === 'native_prepared' && checkpoint.native_created === true && checkpoint.native_ui_read === true),
  'NATIVE_CHECKPOINT_MISMATCH');
  return { id: runId, user_id: QA_ID, description: checkpoint.marker, amount: '0.01', currency: 'EUR',
    date: checkpoint.date, type: 'expense', category_id: null, exchange_rate_eur: '1' };
}

function nativeApiMethod(mode, url, method) {
  if (mode === '--real-native-web-read') check(method === 'GET', 'NATIVE_BROWSER_API_WRITE_REJECTED');
  if (mode === '--cleanup-native-marker') check(method === 'GET' || (method === 'DELETE' && url.pathname === '/rest/v1/transactions') ||
    (method === 'POST' && url.pathname === '/auth/v1/logout' && url.search === '?scope=local'), 'NATIVE_CLEANUP_API_WRITE_REJECTED');
}

async function createMarker(api, expected) {
  let responseFailure;
  try { await api('rest/v1/transactions', { method: 'POST', prefer: 'return=representation', body: expected }); }
  catch (failure) { responseFailure = failure; }
  // A lost response may follow a committed POST. Read the explicit UUID once; never repeat the POST.
  const rows = await api(rowQuery(expected));
  if (responseFailure && Array.isArray(rows) && rows.length === 0) throw responseFailure;
  check(Array.isArray(rows) && rows.length === 1 && ownRow(rows[0], expected), 'CREATED_ROW_MISMATCH');
  return responseFailure instanceof SmokeError ? responseFailure.code : responseFailure ? 'POST_RESPONSE_UNKNOWN' : null;
}

async function cleanupMarker(api, expected) {
  const rows = await api(rowQuery(expected));
  check(Array.isArray(rows) && rows.length <= 1 && rows.every(row => ownRow(row, expected)), 'CLEANUP_ROW_MISMATCH');
  let responseError;
  if (rows.length === 1) {
    const query = new URLSearchParams({ id: `eq.${expected.id}`, user_id: `eq.${expected.user_id}`,
      description: `eq.${expected.description}`, amount: 'eq.0.01', currency: 'eq.EUR', date: `eq.${expected.date}`,
      type: 'eq.expense', category_id: 'is.null', exchange_rate_eur: 'eq.1' });
    try { await api(`rest/v1/transactions?${query}`, { method: 'DELETE', prefer: 'return=minimal' }); }
    catch (failure) { responseError = failure instanceof SmokeError ? failure.code : 'DELETE_RESPONSE_UNKNOWN'; }
  }
  // Payload changes between GET and DELETE cannot masquerade as successful deletion.
  const remaining = await api(rowQuery(expected));
  check(Array.isArray(remaining) && remaining.length === 0, 'MARKER_CLEANUP_PENDING');
  return responseError;
}

async function input() {
  const chunks = [];
  let length = 0;
  for await (const chunk of process.stdin) {
    length += chunk.length;
    check(length <= 32_768, 'INPUT_TOO_LARGE');
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw new SmokeError('INVALID_INPUT_JSON'); }
}

function jwtPayload(token) {
  check(typeof token === 'string' && token.length <= 16_384, 'INVALID_JWT');
  const parts = token.split('.');
  check(parts.length === 3, 'INVALID_JWT');
  try { return JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8')); }
  catch { throw new SmokeError('INVALID_JWT'); }
}

async function boundedJson(response) {
  const chunks = [];
  let length = 0;
  for await (const chunk of response.body ?? []) {
    length += chunk.length;
    check(length <= 65_536, 'RESPONSE_TOO_LARGE');
    chunks.push(chunk);
  }
  const text = Buffer.concat(chunks).toString('utf8');
  if (!text.trim()) return null;
  try { return JSON.parse(text); }
  catch { throw new SmokeError('INVALID_API_RESPONSE'); }
}

async function run() {
  const mode = process.argv[2];
  check(process.argv.length === 3 && ['--real-web-read', '--real-native-web-read', '--cleanup-native-marker'].includes(mode), 'EXPLICIT_OPT_IN_REQUIRED');
  const native = mode !== '--real-web-read';
  const cleanupOnly = mode === '--cleanup-native-marker';
  // Protocol/debug logs may contain arguments or headers; disable them only in this child process.
  delete process.env.DEBUG;
  delete process.env.PWDEBUG;
  const payload = await input();
  check(payload.project_url === PROJECT && payload.site_origin === SITE, 'UNEXPECTED_ORIGIN');
  check(typeof payload.run_id === 'string' && UUID.test(payload.run_id), 'INVALID_RUN_ID');
  const session = payload.session;
  check(session?.user?.id === QA_ID && session?.user?.email === QA_EMAIL, 'UNEXPECTED_QA_METADATA');
  check(typeof session.refresh_token === 'string' && session.refresh_token.length > 0, 'INVALID_REFRESH_TOKEN');
  check(Number.isSafeInteger(session.expires_at) && session.expires_at > Math.floor(Date.now() / 1000) + 600, 'SESSION_VALIDITY_TOO_SHORT');
  const claims = jwtPayload(session.access_token);
  check(claims.sub === QA_ID && UUID.test(claims.session_id ?? '') && claims.iss === `${PROJECT}/auth/v1`, 'UNEXPECTED_JWT_METADATA');
  const publicKey = payload.public_key;
  check(typeof publicKey === 'string' && !publicKey.startsWith('sb_secret_'), 'INVALID_PUBLIC_KEY');
  check(publicKey.startsWith('sb_publishable_') || jwtPayload(publicKey).role === 'anon', 'PRIVILEGED_KEY_REJECTED');
  const marker = `Android-Web-QA-${payload.run_id}`;
  const transactionId = payload.run_id;
  const today = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Rome' }).format(new Date());
  const expected = native ? nativeFixture(payload.native_checkpoint, payload.run_id, claims.session_id, cleanupOnly) :
    { id: transactionId, user_id: QA_ID, type: 'expense', amount: '0.01', currency: 'EUR',
      exchange_rate_eur: '1', category_id: null, description: marker, date: today };
  const outputPath = native ? path.join(repository, 'artifacts', cleanupOnly ? 'android-web-fallback-result.json' : 'android-web-browser-result.json') : reportPath;
  const result = {
    stage: 'validating_identity', scope: 'host-created-row/web-read', run_id: payload.run_id,
    user_id: QA_ID, email: QA_EMAIL, project_host: new URL(PROJECT).host,
    expected_origin: SITE, effective_origin: null, transaction_id: transactionId, marker,
    amount: '0.01', currency: 'EUR', date: today, type: 'expense', category_id: null, exchange_rate_eur: '1', identity_verified: false,
    browser_read_verified: false, browser_write_requests_allowed: 0,
    blocked_audit_requests: 0, blocked_external_requests: 0, blocked_websocket_requests: 0,
    blocked_unexpected_writes: 0, jwt_forwarding_blocked: 0,
    transaction_removed: false, logout_server: false, cleanup_pending: false,
    password_login_verified: false, deployed_commit_verified: false,
  };
  if (native) Object.assign(result, { scope: 'native-data-created/native-ui-read/web-read', date: expected.date,
    session_id: claims.session_id, cleanup_owner: cleanupOnly ? 'host_fallback' : 'native_phase_two', cleanup_pending: true });
  async function record() {
    await fs.mkdir(path.dirname(outputPath), { recursive: true });
    const temporary = `${outputPath}.${payload.run_id}.tmp`;
    await fs.writeFile(temporary, JSON.stringify(result, null, 2), 'utf8');
    await fs.rename(temporary, outputPath);
  }
  async function api(resource, { method = 'GET', body, prefer } = {}) {
    const url = new URL(resource, `${PROJECT}/`);
    check(url.origin === PROJECT && (url.pathname.startsWith('/rest/v1/') || url.pathname.startsWith('/auth/v1/')), 'API_ORIGIN_REJECTED');
    // Native browser mode cannot create, delete or revoke; phase two owns those operations.
    nativeApiMethod(mode, url, method);
    const headers = { apikey: publicKey, Authorization: `Bearer ${session.access_token}`, Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (prefer) headers.Prefer = prefer;
    let response;
    try {
      response = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body),
        redirect: 'error', signal: AbortSignal.timeout(30_000) });
    } catch { throw new SmokeError(`API_${method}_NETWORK_FAILURE`); }
    if (!response.ok) {
      await response.body?.cancel();
      throw new SmokeError(`API_${method}_HTTP_${response.status}`);
    }
    return boundedJson(response);
  }
  let browser;
  let context;
  let creationAttempted = false;
  let primaryFailure;
  try {
    const identity = await api('auth/v1/user');
    check(identity?.id === QA_ID && identity.email === QA_EMAIL && identity.email_confirmed_at, 'SERVER_IDENTITY_MISMATCH');
    check(Array.isArray(identity.factors ?? []) && !(identity.factors ?? []).some(factor => factor.status === 'verified'), 'QA_MFA_REQUIRED');
    result.identity_verified = true;
    if (!cleanupOnly) {
    const existing = await api(rowQuery(expected));
    if (native) {
      check(Array.isArray(existing) && existing.length === 1 && ownRow(existing[0], expected), 'NATIVE_ROW_MISMATCH');
      result.native_marker_verified = true;
    } else {
      check(Array.isArray(existing) && existing.length === 0, 'PREEXISTING_UUID_MUST_BE_PRESERVED');
      result.stage = 'creating_marker';
      result.cleanup_pending = true;
      await record(); // A process interruption still leaves an exact, non-secret cleanup identifier.
      creationAttempted = true;
      result.post_response_error = await createMarker(api, expected);
    }
    result.stage = 'launching_isolated_browser';
    await record();
    const requireWeb = createRequire(path.join(repository, '..', 'Savely', 'package.json'));
    const { chromium } = requireWeb('playwright');
    try { browser = await chromium.launch({ channel: 'msedge', headless: true }); }
    catch { throw new SmokeError('EDGE_LAUNCH_FAILED'); }
    context = await browser.newContext({ locale: 'it-IT', timezoneId: 'Europe/Rome', serviceWorkers: 'block',
      acceptDownloads: false, viewport: { width: 1440, height: 1000 }, storageState: { cookies: [], origins: [{
        origin: SITE, localStorage: [{ name: STORAGE_KEY, value: JSON.stringify(session) }, { name: 'i18nextLng', value: 'it' }],
      }] } });
    await context.route('**/*', async route => {
      const request = route.request();
      const url = new URL(request.url());
      const method = request.method();
      const authorization = request.headers().authorization ?? '';
      const contents = [request.url(), request.headers().cookie ?? '', request.postData() ?? ''];
      const carriesSession = contents.some(value => value.includes(session.access_token) || value.includes(session.refresh_token));
      if ((authorization || carriesSession) && url.origin !== PROJECT) {
        result.jwt_forwarding_blocked++;
        return route.abort('blockedbyclient');
      }
      if (url.origin !== SITE && url.origin !== PROJECT) {
        result.blocked_external_requests++;
        return route.abort('blockedbyclient');
      }
      if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
        if (url.origin === PROJECT && url.pathname === '/rest/v1/login_activity' && method === 'POST') result.blocked_audit_requests++;
        else result.blocked_unexpected_writes++;
        return route.abort('blockedbyclient');
      }
      if (url.origin === PROJECT && !url.pathname.startsWith('/rest/v1/') && url.pathname !== '/auth/v1/user') {
        result.blocked_external_requests++;
        return route.abort('blockedbyclient');
      }
      return route.continue();
    });
    await context.routeWebSocket('**/*', socket => { result.blocked_websocket_requests++; socket.close(); });
    const page = await context.newPage();
    result.stage = 'opening_real_website';
    await record();
    try {
      const response = await page.goto(`${SITE}/transactions`, { waitUntil: 'domcontentloaded', timeout: 45_000 });
      result.effective_origin = new URL(page.url()).origin;
      result.navigation_http_status = response?.status() ?? null;
      check(result.effective_origin === SITE && response?.ok(), 'REAL_SITE_NAVIGATION_REJECTED');
    } catch (failure) {
      try { result.effective_origin = new URL(page.url()).origin; } catch { /* No completed navigation. */ }
      if (failure instanceof SmokeError) throw failure;
      throw new SmokeError('REAL_SITE_NAVIGATION_FAILED');
    }
    try {
      await page.getByRole('heading', { name: 'Transazioni', exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
      await page.getByPlaceholder('Cerca...', { exact: true }).fill(marker);
      const description = page.getByText(marker, { exact: true });
      await description.waitFor({ state: 'visible', timeout: 20_000 });
      check(await description.count() === 1, 'MARKER_NOT_UNIQUE_IN_WEB_UI');
      const row = page.locator('div.flex.items-center.justify-between.p-4').filter({ has: description });
      check(await row.count() === 1, 'MARKER_ROW_NOT_UNIQUE');
      check(/-€\s*0,01(?:\s*EUR)?/.test(await row.innerText()), 'WEB_AMOUNT_MISMATCH');
      check(result.blocked_unexpected_writes === 0 && result.jwt_forwarding_blocked === 0, 'UNEXPECTED_WEB_REQUEST_REJECTED');
      // Record only this run's filtered row; screenshots contain no auth storage or protocol trace.
      result.row_screenshot = `web-read-smoke-row-${payload.run_id}.png`;
      await row.screenshot({ path: path.join(repository, 'artifacts', result.row_screenshot) });
    } catch (failure) {
      if (failure instanceof SmokeError) throw failure;
      throw new SmokeError('WEB_MARKER_READ_FAILED');
    }
    result.browser_read_verified = true;
    result.stage = 'web_read_verified';
    await record();
    }
  } catch (failure) {
    primaryFailure = failure instanceof SmokeError ? failure.code : 'UNEXPECTED_FAILURE';
    result.failure_code = primaryFailure;
  } finally {
    // Stop refresh and page activity before deleting the owned marker and revoking this session.
    try { await context?.close(); } catch { result.browser_close_failed = true; }
    try { await browser?.close(); } catch { result.browser_close_failed = true; }
    if ((creationAttempted || cleanupOnly) && result.identity_verified) {
      try {
        result.delete_response_error = await cleanupMarker(api, expected);
        result.transaction_removed = true;
      } catch (failure) { result.cleanup_failure_code = failure instanceof SmokeError ? failure.code : 'CLEANUP_FAILURE'; }
    } else if (!creationAttempted && !native) result.transaction_removed = true;
    if (result.identity_verified && (!native || cleanupOnly)) {
      try { await api('auth/v1/logout?scope=local', { method: 'POST', body: {} }); result.logout_server = true; }
      catch (failure) { result.logout_failure_code = failure instanceof SmokeError ? failure.code : 'LOGOUT_FAILURE'; }
    }
    result.cleanup_pending = !result.transaction_removed || !result.logout_server;
    result.stage = !primaryFailure && result.browser_read_verified && !result.cleanup_pending && !result.browser_close_failed ? 'passed' : 'failed';
    if (native && !cleanupOnly) result.stage = !primaryFailure && result.browser_read_verified && !result.browser_close_failed ? 'browser_read_passed' : 'failed';
    if (cleanupOnly) result.stage = !primaryFailure && !result.cleanup_pending ? 'cleanup_passed' : 'failed';
    result.audit_policy = 'Automatic web login_activity writes blocked; no browser writes allowed.';
    await record();
    // Auth is never serialized to disk or printed; these references end with this process.
    session.access_token = ''; session.refresh_token = ''; payload.public_key = '';
  }
  console.log(JSON.stringify({ stage: result.stage, scope: result.scope, run_id: result.run_id,
    effective_origin: result.effective_origin, transaction_removed: result.transaction_removed,
    logout_server: result.logout_server, cleanup_pending: result.cleanup_pending,
    failure_code: result.failure_code ?? result.cleanup_failure_code ?? result.logout_failure_code ?? null }));
  if (!['passed', 'browser_read_passed', 'cleanup_passed'].includes(result.stage)) process.exitCode = 1;
}

async function selfTest() {
  const { default: assert } = await import('node:assert/strict');
  // Fixtures only: this branch never reads credentials, imports Playwright or calls fetch.
  const expected = { id: '00000000-0000-4000-8000-000000000001', user_id: QA_ID,
    description: 'Android-Web-QA-00000000-0000-4000-8000-000000000001', amount: '0.01', currency: 'EUR',
    date: '2026-10-11', type: 'expense', category_id: null, exchange_rate_eur: '1' };
  const checkpoint = { run_id: expected.id, transaction_id: expected.id, marker: expected.description,
    session_id: '00000000-0000-4000-8000-000000000002', user_id: QA_ID, email: QA_EMAIL,
    project_host: new URL(PROJECT).host, amount: '0.01', currency: 'EUR', date: expected.date, type: 'expense',
    category_id: null, exchange_rate_eur: '1', creation_attempted: true, stage: 'native_prepared', native_created: true, native_ui_read: true };
  assert.deepEqual(nativeFixture(checkpoint, expected.id, checkpoint.session_id), expected);
  assert.deepEqual(nativeFixture({ ...checkpoint, stage: 'creating' }, expected.id, checkpoint.session_id, true), expected);
  for (const [field, value] of Object.entries({ run_id: 'another', transaction_id: 'another', marker: 'another', session_id: 'another',
    user_id: 'another', email: 'another', project_host: 'another', amount: '0.02', currency: 'USD', date: 'invalid', type: 'income',
    category_id: 'another', exchange_rate_eur: '2', creation_attempted: false, stage: 'creating', native_created: false, native_ui_read: false })) {
    assert.throws(() => nativeFixture({ ...checkpoint, [field]: value }, expected.id, checkpoint.session_id), { code: 'NATIVE_CHECKPOINT_MISMATCH' }, field);
  }
  for (const method of ['POST', 'PATCH', 'DELETE']) {
    assert.throws(() => nativeApiMethod('--real-native-web-read', new URL(`${PROJECT}/rest/v1/transactions`), method), { code: 'NATIVE_BROWSER_API_WRITE_REJECTED' });
  }
  nativeApiMethod('--real-native-web-read', new URL(`${PROJECT}/auth/v1/user`), 'GET');
  nativeApiMethod('--cleanup-native-marker', new URL(`${PROJECT}/auth/v1/logout?scope=local`), 'POST');
  assert.throws(() => nativeApiMethod('--cleanup-native-marker', new URL(`${PROJECT}/auth/v1/logout`), 'POST'), { code: 'NATIVE_CLEANUP_API_WRITE_REJECTED' });
  assert.throws(() => nativeApiMethod('--cleanup-native-marker', new URL(`${PROJECT}/rest/v1/transactions`), 'POST'), { code: 'NATIVE_CLEANUP_API_WRITE_REJECTED' });
  assert.equal(ownRow(expected, expected), true);
  assert.equal(ownRow({ ...expected, amount: 0.01, exchange_rate_eur: '1.000' }, expected), true);
  const mutations = { id: 'another', user_id: 'another', description: 'changed', amount: '0.02', currency: 'USD',
    date: '2026-10-12', type: 'income', category_id: 'another', exchange_rate_eur: '2' };
  for (const [field, value] of Object.entries(mutations)) {
    const changed = { ...expected, [field]: value };
    assert.equal(ownRow(changed, expected), false, field);
    let deletes = 0;
    await assert.rejects(cleanupMarker(async (_resource, options) => {
      if (options?.method === 'DELETE') deletes++;
      return [changed];
    }, expected), { code: 'CLEANUP_ROW_MISMATCH' });
    assert.equal(deletes, 0, `preserve changed ${field}`);
  }
  assert.equal(new URL(rowQuery(expected), PROJECT).searchParams.has('description'), false);
  assert.equal(new URL(rowQuery(expected), PROJECT).searchParams.get('user_id'), `eq.${QA_ID}`);
  let posts = 0; let reads = 0;
  const ambiguousPost = async (resource, options) => {
    if (options?.method === 'POST') { posts++; throw new SmokeError('API_POST_NETWORK_FAILURE'); }
    assert.equal(resource, rowQuery(expected)); reads++; return [{ ...expected }];
  };
  assert.equal(await createMarker(ambiguousPost, expected), 'API_POST_NETWORK_FAILURE');
  assert.equal(posts, 1); assert.equal(reads, 1);
  posts = 0;
  await assert.rejects(createMarker(async (_resource, options) => {
    if (options?.method === 'POST') { posts++; throw new SmokeError('API_POST_NETWORK_FAILURE'); }
    return [];
  }, expected), { code: 'API_POST_NETWORK_FAILURE' });
  assert.equal(posts, 1);
  let state = [{ ...expected }]; let deletes = 0;
  const deleteCommittedButResponseLost = async (resource, options) => {
    if (options?.method !== 'DELETE') { assert.equal(resource, rowQuery(expected)); return state; }
    deletes++;
    const query = new URL(resource, PROJECT).searchParams;
    for (const [field, value] of Object.entries({ id: `eq.${expected.id}`, user_id: `eq.${QA_ID}`,
      description: `eq.${expected.description}`, amount: 'eq.0.01', currency: 'eq.EUR', date: `eq.${expected.date}`,
      type: 'eq.expense', category_id: 'is.null', exchange_rate_eur: 'eq.1' })) assert.equal(query.get(field), value);
    state = [];
    throw new SmokeError('API_DELETE_NETWORK_FAILURE');
  };
  assert.equal(await cleanupMarker(deleteCommittedButResponseLost, expected), 'API_DELETE_NETWORK_FAILURE');
  assert.equal(deletes, 1);
  state = [{ ...expected }]; deletes = 0;
  await assert.rejects(cleanupMarker(async (resource, options) => {
    if (options?.method === 'DELETE') { deletes++; state = [{ ...expected, description: 'concurrently changed' }]; return null; }
    assert.equal(resource, rowQuery(expected)); return state;
  }, expected), { code: 'MARKER_CLEANUP_PENDING' });
  assert.equal(deletes, 1);
  deletes = 0;
  await cleanupMarker(async (_resource, options) => { if (options?.method === 'DELETE') deletes++; return []; }, expected);
  assert.equal(deletes, 0);
  console.log('Fixture self-test PASS: full payload ownership, ambiguous POST/DELETE, changed-row preservation, identity-based absence and native checkpoint/read-only API guards.');
}

const execution = process.argv.length === 3 && process.argv[2] === '--self-test' ? selfTest : run;
execution().catch(failure => {
  console.error(JSON.stringify({ stage: 'failed', failure_code: failure instanceof SmokeError ? failure.code : 'SAFE_UNEXPECTED_FAILURE', cleanup_pending: true }));
  process.exitCode = 1;
});
