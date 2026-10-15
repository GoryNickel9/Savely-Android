// Synthetic auth/provider only. No database client, credentials vault or remote module evaluation.
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
globalThis.Deno = { test: (name, run) => tests.push({ name, run }), serve: callback => { handler = callback; },
  env: { get: name => ({ SUPABASE_URL: 'https://fixture.invalid', SUPABASE_ANON_KEY: 'synthetic-anon',
    CARDTRADER_API_KEY: 'synthetic-provider', ALLOWED_ORIGIN: 'https://fixture.invalid' })[name] } };
function moduleUrl(source) {
  return 'data:text/javascript;base64,' + Buffer.from(ts.transpileModule(source,
    { compilerOptions: { module: ts.ModuleKind.ES2022, target: ts.ScriptTarget.ES2022 } }).outputText).toString('base64');
}
const contract = moduleUrl(readFileSync(path.join(here, 'quote-contract.ts'), 'utf8'));
await import(moduleUrl(readFileSync(path.join(here, 'quote-contract_test.ts'), 'utf8')
  .replace("'./quote-contract.ts'", JSON.stringify(contract))));
let source = readFileSync(path.join(here, 'index.ts'), 'utf8');
const remote = "import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';";
assert.equal(source.split(remote).length, 2);
source = source.replace(remote, 'const createClient = (...args) => globalThis.__proxyFixtureClient(...args);')
  .replace("'./quote-contract.ts'", JSON.stringify(contract));
await import(moduleUrl(source));
assert.equal(typeof handler, 'function');
const product = (cents = 1250, currency = 'EUR', blueprint = 42) => ({ blueprint_id: blueprint,
  price_cents: cents, price_currency: currency, price: { cents: 9999, currency: 'USD', formatted: '$99.99' },
  user: { can_sell_via_hub: true }, properties_hash: { condition: 'Near Mint' } });
async function invoke({ query = 'action=prices&id=42&condition=near_mint', authorization = 'Bearer synthetic-user',
  validUser = true, authThrows = false, method = 'GET', products = { 42: [product()] },
  providerStatus = 200, providerThrows = false, providerRaw = null, searchPages = null } = {}) {
  const providers = [], auth = [], db = [];
  globalThis.__proxyFixtureClient = (url, key, options) => {
    assert.equal(url, 'https://fixture.invalid'); assert.equal(key, 'synthetic-anon');
    assert.equal(options.global.headers.Authorization, authorization);
    return { auth: { getUser: async () => {
      auth.push('getUser'); if (authThrows) throw new Error('SYNTHETIC_AUTH_FAILURE');
      return { data: { user: validUser ? { id: '00000000-0000-4000-8000-000000000001' } : null } };
    } }, from: table => { db.push(table); throw new Error('DATABASE_FORBIDDEN'); } };
  };
  globalThis.fetch = async (url, options) => {
    providers.push(url);
    assert.equal(new URL(url).origin, 'https://api.cardtrader.com');
    assert.equal(options.headers.Authorization, 'Bearer synthetic-provider');
    if (providerThrows) throw new Error('PROVIDER_SECRET_BODY_MUST_NOT_ESCAPE');
    if (new URL(url).pathname.endsWith('/expansions')) return Response.json([{ id: 7, name: 'Synthetic Set' }]);
    if (new URL(url).pathname.endsWith('/blueprints')) {
      const page = Number(new URL(url).searchParams.get('page'));
      return Response.json(searchPages?.[page - 1] ?? [{ id: 42, name: 'QA card', expansion_id: 7, image_url: 'https://fixture.invalid/card.png' }]);
    }
    assert.equal(new URL(url).searchParams.get('blueprint_id'), '42');
    assert.equal(options.redirect, 'error');
    return providerRaw === null ? Response.json(products, { status: providerStatus }) : new Response(providerRaw, { status: providerStatus });
  };
  try {
    const headers = authorization === null ? {} : { Authorization: authorization };
    const response = await handler(new Request('https://fixture.invalid/functions/v1/cardtrader-proxy?' + query, { method, headers }));
    const body = method === 'OPTIONS' ? await response.text() : await response.json();
    assert.deepEqual(db, []);
    return { status: response.status, body, providers, auth };
  } finally {
    globalThis.fetch = async () => { throw new Error('OFFLINE_NETWORK_FORBIDDEN'); };
    delete globalThis.__proxyFixtureClient;
  }
}
const test = (name, run) => tests.push({ name, run });
test('OPTIONS preserves CORS without JWT lookup or provider', async () => {
  const result = await invoke({ method: 'OPTIONS', authorization: null });
  assert.equal(result.status, 200); assert.equal(result.body, 'ok'); assert.equal(result.auth.length, 0); assert.equal(result.providers.length, 0);
});
test('missing JWT cannot call provider', async () => {
  const result = await invoke({ authorization: null }); assert.equal(result.status, 401); assert.equal(result.providers.length, 0);
});
test('invalid JWT and auth exception cannot call provider', async () => {
  for (const options of [{ validUser: false }, { authThrows: true }]) {
    const result = await invoke(options); assert.equal(result.status, 401); assert.equal(result.auth.length, 1); assert.equal(result.providers.length, 0);
  }
});
test('v2 direct quote binds EUR identity and integer cents without borrowing USD formatted text', async () => {
  const result = await invoke(); assert.equal(result.status, 200);
  assert.deepEqual(result.body, { quoteContractVersion: 2, blueprintId: '42', currency: 'EUR', minorUnit: 2,
    ctZeroLowPrice: '€12.50', ctZeroLowPriceCents: 1250, totalListings: 1 });
  assert.equal(result.auth.length, 1); assert.equal(result.providers.length, 1);
});
test('empty prices carry explicit EUR and blueprint but remain null for old consumers', async () => {
  const result = await invoke({ products: { 42: [] } }); assert.equal(result.status, 200);
  assert.equal(result.body.currency, 'EUR'); assert.equal(result.body.blueprintId, '42');
  assert.equal(result.body.ctZeroLowPriceCents, null); assert.equal(result.body.totalListings, 0);
});
test('missing malformed blueprint or condition is rejected before provider', async () => {
  for (const query of ['action=prices', 'action=prices&id=legacy', 'action=prices&id=042', 'action=prices&id=42&condition=unknown']) {
    const result = await invoke({ query }); assert.equal(result.status, 400); assert.equal(result.providers.length, 0);
  }
});
test('foreign quote blueprint returns a fixed error and no quote', async () => {
  const result = await invoke({ products: { 42: [product(1, 'EUR', 43)] } });
  assert.equal(result.status, 502); assert.deepEqual(result.body, { error: 'QUOTE_BLUEPRINT_MISMATCH' }); assert.equal(result.providers.length, 1);
});
test('USD quote cannot be relabeled EUR and has no legacy cents payload', async () => {
  const result = await invoke({ products: { 42: [product(50, 'USD')] } });
  assert.equal(result.status, 502); assert.equal(result.body.error, 'QUOTE_CURRENCY_UNSUPPORTED');
  assert.equal(Object.hasOwn(result.body, 'ctZeroLowPriceCents'), false);
});
test('fractional cents and overflow return fixed errors without retries', async () => {
  for (const cents of [.5, 10_000_000_000]) {
    const result = await invoke({ products: { 42: [product(cents)] } });
    assert.equal(result.status, 502); assert.equal(result.body.error, 'QUOTE_CENTS_INVALID'); assert.equal(result.providers.length, 1);
  }
});
test('provider failure and malformed or oversized JSON never expose provider text or retry', async () => {
  for (const options of [{ providerThrows: true }, { providerStatus: 503 }, { providerRaw: '{broken' }, { providerRaw: ' '.repeat(1_048_577) }]) {
    const result = await invoke(options); assert.equal(result.status, 502); assert.equal(result.providers.length, 1);
    assert.equal(JSON.stringify(result.body).includes('PROVIDER_SECRET_BODY'), false);
  }
});
test('search keeps original array fields and set image mapping', async () => {
  const result = await invoke({ query: 'action=search&name=QA%20card' }); assert.equal(result.status, 200);
  assert.deepEqual(result.body, [{ id: '42', name: 'QA card', image: 'https://fixture.invalid/card.png', set: { id: '7', name: 'Synthetic Set' } }]);
  assert.equal(result.auth.length, 1);
});
test('search still follows the original 50-row page boundary', async () => {
  const first = Array.from({ length: 50 }, (_, index) => ({ id: index + 1, name: 'QA ' + index, expansion_id: 7 }));
  const result = await invoke({ query: 'action=search&name=paged', searchPages: [first, [{ id: 51, name: 'QA last', expansion_id: 7 }]] });
  assert.equal(result.status, 200); assert.equal(result.body.length, 51); assert.equal(result.body[50].id, '51');
  assert.equal(result.providers.filter(url => new URL(url).pathname.endsWith('/blueprints')).length, 2);
});
let failed = 0;
for (const { name, run } of tests) {
  try { await run(); console.log('PASS ' + name); }
  catch (error) { failed++; console.error('FAIL ' + name + ': ' + error.message); }
}
console.log(JSON.stringify({ tests: tests.length, passed: tests.length - failed, failed, scope: 'proxy-offline-synthetic-only' }));
if (failed) process.exitCode = 1;
