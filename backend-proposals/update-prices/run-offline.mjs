// Run only synthetic provider/database fixtures; transpile with the already installed web TypeScript.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const ts = require(path.resolve(here, '../../../Savely/node_modules/typescript/lib/typescript.js'));
const tests = [];
let handler;
globalThis.fetch = async () => { throw new Error('OFFLINE_NETWORK_FORBIDDEN'); };
globalThis.Deno = {
  test: (name, run) => tests.push({ name, run }),
  serve: callback => { handler = callback; },
  env: { get: name => ({ SUPABASE_URL: 'https://fixture.invalid', SUPABASE_SERVICE_ROLE_KEY: 'synthetic-service',
    SUPABASE_ANON_KEY: 'synthetic-anon', ALLOWED_ORIGIN: 'https://fixture.invalid' })[name] },
};
function moduleUrl(source) {
  const result = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ES2022, target: ts.ScriptTarget.ES2022 } });
  return 'data:text/javascript;base64,' + Buffer.from(result.outputText).toString('base64');
}
const quoteUrl = moduleUrl(readFileSync(path.join(here, 'quote-contract.ts'), 'utf8'));
await import(moduleUrl(readFileSync(path.join(here, 'quote-contract_test.ts'), 'utf8').replace("'./quote-contract.ts'", JSON.stringify(quoteUrl))));
let index = readFileSync(path.join(here, 'index.ts'), 'utf8');
const remoteImport = "import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';";
assert.equal(index.split(remoteImport).length, 2);
index = index.replace(remoteImport, 'const createClient = (...args) => globalThis.__quoteFixtureClient(...args);')
  .replace("'./quote-contract.ts'", JSON.stringify(quoteUrl));
await import(moduleUrl(index));
assert.equal(typeof handler, 'function');

const A = '00000000-0000-4000-8000-000000000001';
const B = '00000000-0000-4000-8000-000000000002';
const security = (currency = 'USD', owner = A) => ({ id: owner === A ? 'fixture-stock-a' : 'fixture-stock-b',
  user_id: owner, symbol: 'AAPL', type: 'stock', currency, current_price: 1.23, updated_at: '2026-10-14T00:00:00Z' });

/** A small Supabase-shaped fake for this one handler, not a transport or production implementation. */
async function invoke({ rows = [], quoteCurrency = 'USD', providerStatus = 200, historyFailure = false,
  logFailure = false, cashReadFailure = false, beforeUpdate = null, validUser = true, cronSecret = null, body = { user_id: B } } = {}) {
  const db = { portfolio_assets: structuredClone(rows), asset_price_history: [], price_update_logs: [],
    cron_config: [{ key: 'cron_secret', value: 'synthetic-cron-secret' }] };
  const operations = [], providerCalls = [];
  class Query {
    constructor(table) { this.table = table; this.mode = 'read'; this.filters = []; }
    select() { return this; }
    in(key, values) { this.filters.push(row => values.includes(row[key])); return this; }
    eq(key, value) {
      if (key === 'type' && value === 'cash') this.cashQuery = true;
      this.filters.push(row => row[key] === value); return this;
    }
    neq(key, value) { this.filters.push(row => row[key] !== value); return this; }
    not(key, operation, value) { assert.equal(operation, 'is'); assert.equal(value, null); this.filters.push(row => row[key] != null); return this; }
    is(key, value) { this.filters.push(row => (row[key] ?? null) === value); return this; }
    update(payload) { this.mode = 'update'; this.payload = payload; return this; }
    insert(payload) { this.mode = 'insert'; this.payload = payload; return this; }
    upsert(payload) { this.mode = 'upsert'; this.payload = payload; return this; }
    maybeSingle() { return this.execute(true); }
    then(resolve, reject) { return this.execute(false).then(resolve, reject); }
    async execute(single) {
      operations.push({ table: this.table, mode: this.mode, payload: structuredClone(this.payload) });
      assert.ok(Object.hasOwn(db, this.table));
      if (this.mode === 'read') {
        if (cashReadFailure && this.table === 'portfolio_assets' && this.cashQuery)
          return { data: null, error: { code: 'SYNTHETIC_CASH_READ_FAILURE' } };
        const selected = db[this.table].filter(row => this.filters.every(filter => filter(row)));
        return { data: structuredClone(single ? selected[0] ?? null : selected), error: null };
      }
      if (this.mode === 'update') {
        if (beforeUpdate) { beforeUpdate(db); beforeUpdate = null; }
        const selected = db[this.table].filter(row => this.filters.every(filter => filter(row)));
        for (const row of selected) Object.assign(row, this.payload, { current_price: Number(this.payload.current_price) });
        return { data: structuredClone(single ? selected[0] ?? null : selected), error: null };
      }
      if (this.table === 'asset_price_history' && historyFailure || this.table === 'price_update_logs' && logFailure)
        return { data: null, error: { code: 'SYNTHETIC_DATABASE_FAILURE' } };
      db[this.table].push({ id: `synthetic-${db[this.table].length}`, ...structuredClone(this.payload) });
      return { data: null, error: null };
    }
  }
  globalThis.__quoteFixtureClient = (_url, key) => key === 'synthetic-anon'
    ? { auth: { getUser: async () => ({ data: { user: validUser ? { id: A } : null } }) } }
    : { from: table => new Query(table) };
  globalThis.fetch = async (url, options) => {
    providerCalls.push({ url, options });
    assert.equal(new Headers(options?.headers).get('Authorization'), null);
    assert.equal(new Headers(options?.headers).get('apikey'), null);
    if (providerStatus !== 200) return new Response('{}', { status: providerStatus });
    if (url.startsWith('https://query1.finance.yahoo.com/v8/finance/chart/')) {
      const symbol = decodeURIComponent(new URL(url).pathname.split('/').at(-1));
      return Response.json({ chart: { result: [{ meta: { symbol, currency: quoteCurrency, regularMarketPrice: 10.005 } }] } });
    }
    if (url === 'https://api.frankfurter.app/latest?from=USD&to=EUR')
      return Response.json({ base: 'USD', rates: { EUR: .9234 } });
    throw new Error('UNEXPECTED_PROVIDER_REQUEST');
  };
  try {
    const headers = { 'Content-Type': 'application/json' };
    if (cronSecret === null) headers.Authorization = 'Bearer synthetic-user';
    else headers['x-cron-secret'] = cronSecret;
    const response = await handler(new Request('https://fixture.invalid/functions/v1/update-prices',
      { method: 'POST', headers, body: JSON.stringify(body) }));
    return { status: response.status, body: await response.json(), db, operations, providerCalls };
  } finally {
    globalThis.fetch = async () => { throw new Error('OFFLINE_NETWORK_FORBIDDEN'); };
    delete globalThis.__quoteFixtureClient;
  }
}
const test = (name, run) => tests.push({ name, run });
test('handler refuses AAPL USD into EUR and never mutates a different owner from the body', async () => {
  const other = security('USD', B);
  const result = await invoke({ rows: [security('EUR'), other] });
  assert.equal(result.status, 200);
  assert.equal(result.body.checked, 1); assert.equal(result.body.updated, 0);
  assert.equal(result.body.quote_contract_version, 2);
  assert.equal(result.body.errors[0].error, 'QUOTE_CURRENCY_MISMATCH');
  assert.equal(result.operations.filter(op => op.mode === 'update').length, 0);
  assert.deepEqual(result.db.portfolio_assets[1], other);
  assert.equal(result.db.asset_price_history.length, 0);
});
test('handler stores the same rounded decimal in its own security and its history', async () => {
  const result = await invoke({ rows: [security()] });
  assert.equal(result.status, 200); assert.equal(result.body.updated, 1); assert.deepEqual(result.body.errors, []);
  assert.equal(result.db.portfolio_assets[0].current_price, 10.01);
  assert.equal(result.db.asset_price_history[0].price, '10.01');
  assert.equal(result.db.asset_price_history[0].user_id, A);
  assert.equal(result.db.price_update_logs[0].assets_updated, 1);
});
test('cash-only holdings still execute one explicit FX lookup with checked zero', async () => {
  const cash = { ...security(), id: 'fixture-cash', type: 'cash', symbol: null, sold_at: null };
  const result = await invoke({ rows: [cash] });
  assert.equal(result.status, 200); assert.equal(result.body.checked, 0); assert.equal(result.body.updated, 1);
  assert.equal(result.db.portfolio_assets[0].current_price, .92);
  assert.equal(result.db.asset_price_history.length, 0);
  assert.equal(result.providerCalls.length, 1);
});
test('cash SELECT failure precedes every provider request and asset or history mutation', async () => {
  const rows = [security(), { ...security(), id: 'fixture-cash', type: 'cash', symbol: null, sold_at: null }];
  const result = await invoke({ rows, cashReadFailure: true });
  assert.equal(result.status, 500); assert.deepEqual(result.body, { error: 'Internal error' });
  assert.equal(result.providerCalls.length, 0);
  assert.equal(result.operations.filter(op => op.table === 'portfolio_assets' && op.mode === 'read').length, 2);
  assert.equal(result.operations.filter(op => op.mode !== 'read').length, 0);
  assert.deepEqual(result.db.portfolio_assets, rows);
  assert.equal(result.db.asset_price_history.length, 0);
  assert.equal(result.db.price_update_logs.length, 0);
});
test('row-version and currency changes before write refuse CAS without a history write or replay', async () => {
  const result = await invoke({ rows: [security()], beforeUpdate: db => { db.portfolio_assets[0].currency = 'EUR'; } });
  assert.equal(result.body.updated, 0); assert.equal(result.body.errors[0].error, 'ASSET_WRITE_NOT_CONFIRMED');
  assert.equal(result.db.portfolio_assets[0].current_price, 1.23);
  assert.equal(result.db.asset_price_history.length, 0);
  assert.equal(result.operations.filter(op => op.mode === 'update').length, 1);
});
test('history failure reports the committed asset as partial and never retries it', async () => {
  const result = await invoke({ rows: [security()], historyFailure: true });
  assert.equal(result.body.updated, 1); assert.equal(result.body.errors[0].error, 'PRICE_HISTORY_WRITE_FAILED');
  assert.equal(result.db.asset_price_history.length, 0);
  assert.equal(result.operations.filter(op => op.mode === 'update').length, 1);
});
test('log failure is an HTTP failure after a possible write, never a clean success', async () => {
  const result = await invoke({ rows: [security()], logFailure: true });
  assert.equal(result.status, 500); assert.deepEqual(result.body, { error: 'Internal error' });
  assert.equal(result.operations.filter(op => op.mode === 'update').length, 1);
});
test('empty scope preserves the known no-assets message with explicit checked zero', async () => {
  const result = await invoke();
  assert.equal(result.status, 200); assert.equal(result.body.message, 'No assets to update');
  assert.equal(result.body.checked, 0); assert.equal(result.body.updated, 0); assert.deepEqual(result.body.errors, []);
});
test('invalid user JWT and wrong cron secret cannot perform provider lookups or asset writes', async () => {
  for (const scenario of [{ validUser: false }, { cronSecret: 'synthetic-wrong' }]) {
    const result = await invoke({ rows: [security()], ...scenario });
    assert.equal(result.status, 401); assert.equal(result.providerCalls.length, 0);
    assert.equal(result.operations.filter(op => op.mode !== 'read').length, 0);
  }
});
test('authorized cron retains its explicit owner filter', async () => {
  const other = security('USD', B);
  const result = await invoke({ rows: [security(), other], cronSecret: 'synthetic-cron-secret', body: { user_id: A } });
  assert.equal(result.status, 200); assert.equal(result.body.updated, 1);
  assert.deepEqual(result.db.portfolio_assets[1], other);
});

let failed = 0;
for (const { name, run } of tests) {
  try { await run(); console.log('PASS ' + name); }
  catch { failed++; console.error('FAIL ' + name); }
}
console.log(JSON.stringify({ scope: 'offline-synthetic-only', total: tests.length, passed: tests.length - failed, failed }));
if (failed) process.exitCode = 1;
