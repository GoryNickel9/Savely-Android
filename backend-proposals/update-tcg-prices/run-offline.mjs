// Synthetic-only handler and contract fixtures; this runner never imports a remote module.
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
  test: (name, run) => tests.push({ name, run }), serve: callback => { handler = callback; },
  env: { get: name => ({ SUPABASE_URL: 'https://fixture.invalid', SUPABASE_SERVICE_ROLE_KEY: 'synthetic-service',
    CARDTRADER_API_KEY: 'synthetic-cardtrader', ALLOWED_ORIGIN: 'https://fixture.invalid' })[name] },
};
function moduleUrl(source) {
  return 'data:text/javascript;base64,' + Buffer.from(ts.transpileModule(source,
    { compilerOptions: { module: ts.ModuleKind.ES2022, target: ts.ScriptTarget.ES2022 } }).outputText).toString('base64');
}
const contract = moduleUrl(readFileSync(path.join(here, 'price-contract.ts'), 'utf8'));
await import(moduleUrl(readFileSync(path.join(here, 'price-contract_test.ts'), 'utf8')
  .replace("'./price-contract.ts'", JSON.stringify(contract))));
let index = readFileSync(path.join(here, 'index.ts'), 'utf8');
const remote = "import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';";
assert.equal(index.split(remote).length, 2);
index = index.replace(remote, 'const createClient = (...args) => globalThis.__tcgFixtureClient(...args);')
  .replace("'./price-contract.ts'", JSON.stringify(contract));
await import(moduleUrl(index));
assert.equal(typeof handler, 'function');
const A = '00000000-0000-4000-8000-000000000001';
const B = '00000000-0000-4000-8000-000000000002';
const card = (currency = 'EUR', owner = A) => ({ id: owner, user_id: owner, category: 'magic',
  card_id: '42', currency, condition: 'near_mint', current_price: null, updated_at: '2026-10-14T00:00:00Z' });
const product = (cents = 1250, currency = 'EUR', blueprint = 42) => ({ blueprint_id: blueprint,
  price_cents: cents, price_currency: currency, user: { can_sell_via_hub: true },
  properties_hash: { condition: 'Near Mint' } });
async function invoke({ rows = [card()], products = { 42: [product()] }, providerStatus = 200,
  readFailure = false, updateFailure = false, beforeUpdate = null, secret = 'synthetic-cron',
  cronReadFailure = false, oversized = false } = {}) {
  const db = structuredClone(rows), operations = [], providers = [];
  class Query {
    constructor(table) { this.table = table; this.filters = []; this.mode = 'read'; }
    select() { return this; }
    eq(key, value) {
      this.filters.push(row => key === 'current_price' ? row[key] !== null && Number(row[key]) === Number(value) : row[key] === value); return this;
    }
    is(key, value) { this.filters.push(row => row[key] === value); return this; }
    not(key, operator, value) { assert.equal(operator, 'is'); this.filters.push(row => row[key] !== value); return this; }
    update(payload) { this.mode = 'update'; this.payload = payload; return this; }
    maybeSingle() { return this.execute(true); }
    then(resolve, reject) { return this.execute(false).then(resolve, reject); }
    async execute(single) {
      operations.push({ table: this.table, mode: this.mode });
      if (this.table === 'cron_config') return { data: cronReadFailure ? null : { value: 'synthetic-cron' }, error: cronReadFailure ? {} : null };
      assert.equal(this.table, 'tgc_cards');
      if (this.mode === 'read' && readFailure || this.mode === 'update' && updateFailure) return { data: null, error: {} };
      if (this.mode === 'update' && beforeUpdate) { beforeUpdate(db); beforeUpdate = null; }
      const selected = db.filter(row => this.filters.every(filter => filter(row)));
      if (this.mode === 'update') for (const row of selected) Object.assign(row, this.payload);
      return { data: structuredClone(single ? selected[0] ?? null : selected), error: null };
    }
  }
  globalThis.__tcgFixtureClient = () => ({ from: table => new Query(table) });
  globalThis.fetch = async (url, options) => {
    providers.push(url);
    assert.equal(new URL(url).origin, 'https://api.cardtrader.com');
    assert.equal(new URL(url).searchParams.get('blueprint_id'), '42');
    assert.equal(options.redirect, 'error');
    assert.equal(options.headers.Authorization, 'Bearer synthetic-cardtrader');
    if (oversized) return new Response(' '.repeat(1_048_577));
    return Response.json(products, { status: providerStatus });
  };
  // Do not wait real 200ms pacing in a synthetic handler fixture; all business branches run unchanged.
  const timer = globalThis.setTimeout;
  globalThis.setTimeout = (callback, milliseconds, ...args) => timer(callback, milliseconds === 200 ? 0 : milliseconds, ...args);
  try {
    const headers = secret === null ? {} : { 'x-cron-secret': secret };
    const response = await handler(new Request('https://fixture.invalid/functions/v1/update-tcg-prices', { method: 'POST', headers }));
    return { status: response.status, body: await response.json(), db, operations, providers };
  } finally {
    globalThis.fetch = async () => { throw new Error('OFFLINE_NETWORK_FORBIDDEN'); };
    globalThis.setTimeout = timer;
    delete globalThis.__tcgFixtureClient;
  }
}
const test = (name, run) => tests.push({ name, run });
test('unauthorized request never reads cards or contacts provider', async () => {
  const result = await invoke({ secret: null }); assert.equal(result.status, 401);
  assert.equal(result.operations.length, 0); assert.equal(result.providers.length, 0);
});
test('wrong cron secret cannot update any owner', async () => {
  const result = await invoke({ secret: 'wrong' }); assert.equal(result.status, 401);
  assert.equal(result.operations.length, 1); assert.equal(result.providers.length, 0);
});
test('cron secret read failure refuses authorization', async () => {
  const result = await invoke({ cronReadFailure: true }); assert.equal(result.status, 401);
  assert.equal(result.providers.length, 0);
});
test('card SELECT failure occurs before provider or UPDATE', async () => {
  const result = await invoke({ readFailure: true }); assert.equal(result.status, 500);
  assert.equal(result.providers.length, 0); assert.equal(result.operations.filter(op => op.mode === 'update').length, 0);
});
test('USD old current price is preserved and never contacts provider', async () => {
  const original = { ...card('USD'), current_price: 99.99 };
  const result = await invoke({ rows: [original] }); assert.deepEqual(result.db, [original]);
  assert.equal(result.body.skippedCurrency, 1); assert.equal(result.providers.length, 0);
});
test('legacy NULL EUR writes exact decimal while preserving NULL currency', async () => {
  const result = await invoke({ rows: [card(null)] }); assert.equal(result.body.updated, 1);
  assert.equal(result.db[0].current_price, '12.50'); assert.equal(result.db[0].currency, null);
});
test('legacy nonnumeric card ID skips without contacting provider', async () => {
  const original = { ...card(), card_id: 'scryfall-legacy-uuid' };
  const result = await invoke({ rows: [original] }); assert.equal(result.body.skippedInvalid, 1);
  assert.deepEqual(result.db, [original]); assert.equal(result.providers.length, 0);
});
test('foreign quote ID or currency never reaches UPDATE', async () => {
  for (const quoted of [product(1, 'USD'), product(1, 'EUR', 43)]) {
    const result = await invoke({ products: { 42: [quoted] } }); assert.equal(result.body.failed, 1);
    assert.equal(result.operations.filter(op => op.mode === 'update').length, 0); assert.equal(result.db[0].current_price, null);
  }
});
test('concurrent owner blueprint condition currency price and timestamp edits cause CAS skip', async () => {
  for (const [key, value] of [['user_id', B], ['card_id', '43'], ['condition', 'damaged'],
    ['currency', 'USD'], ['current_price', 2], ['updated_at', '2026-10-14T00:00:01Z']]) {
    const result = await invoke({ beforeUpdate: db => { db[0][key] = value; } });
    assert.equal(result.body.updated, 0); assert.equal(result.body.skippedConcurrent, 1);
    assert.equal(result.db[0][key], value);
    if (key !== 'current_price') assert.equal(result.db[0].current_price, null);
  }
});
test('UPDATE error is counted once without retry', async () => {
  const result = await invoke({ updateFailure: true }); assert.equal(result.body.failed, 1);
  assert.equal(result.operations.filter(op => op.mode === 'update').length, 1); assert.equal(result.db[0].current_price, null);
});
test('provider 503 and oversized JSON never update and never retry', async () => {
  for (const options of [{ providerStatus: 503 }, { oversized: true }]) {
    const result = await invoke(options); assert.equal(result.body.failed, 1); assert.equal(result.providers.length, 1);
    assert.equal(result.operations.filter(op => op.mode === 'update').length, 0);
  }
});
test('global cron reports EUR update and unsupported currency separately', async () => {
  const result = await invoke({ rows: [card(), { ...card('USD', B), current_price: 77 }] });
  assert.equal(result.body.total, 2); assert.equal(result.body.updated, 1); assert.equal(result.body.skippedCurrency, 1);
  assert.equal(result.db[1].current_price, 77); assert.equal(result.providers.length, 1);
});
let failed = 0;
for (const { name, run } of tests) {
  try { await run(); console.log('PASS ' + name); }
  catch (error) { failed++; console.error('FAIL ' + name + ': ' + error.message); }
}
console.log(JSON.stringify({ tests: tests.length, failed, passed: tests.length - failed, scope: 'offline-synthetic-only' }));
if (failed) process.exitCode = 1;
