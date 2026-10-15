import { eligibleCard, blueprintId, euroPrice, compareAndSwap } from './price-contract.ts';

const A = '00000000-0000-4000-8000-000000000001';
const row = (currency: string | null = 'EUR') => ({ id: A, user_id: A, category: 'magic',
  card_id: '42', currency, condition: 'near_mint', current_price: null, updated_at: null });
const product = (cents = 1250, currency = 'EUR', blueprint = 42) => ({ blueprint_id: blueprint,
  price_cents: cents, price_currency: currency, user: { can_sell_via_hub: true },
  properties_hash: { condition: 'Near Mint' } });
const need = (value: unknown) => { if (!value) throw new Error('ASSERTION_FAILED'); };
function rejects(run: () => unknown, code?: string) {
  try { run(); } catch (error) { need(!code || (error as Error).message === code); return; }
  throw new Error('EXPECTED_REJECTION');
}

Deno.test('explicit EUR and legacy NULL are eligible without rewriting currency', () => {
  need(eligibleCard(row()).currency === 'EUR'); need(eligibleCard(row(null)).currency === null);
});
Deno.test('USD missing and malformed currencies fail closed', () => {
  for (const currency of ['USD', '', 'eur', undefined]) rejects(() => eligibleCard({ ...row(), currency }), 'ROW_CURRENCY_UNSUPPORTED');
});
Deno.test('only canonical positive numeric blueprint identifiers are accepted', () => {
  need(blueprintId('42') === '42');
  for (const value of ['042', '0', '-1', '42.5', 'scryfall-id', '1000000000000000', 42, null])
    rejects(() => blueprintId(value), 'BLUEPRINT_ID_INVALID');
});
Deno.test('foreign product blueprint is rejected even under the requested response key', () => {
  rejects(() => euroPrice('42', null, { 42: [product(1, 'EUR', 43)] }), 'QUOTE_BLUEPRINT_MISMATCH');
});
Deno.test('integer cents serialize exactly at zero ordinary and SQL maximum values', () => {
  for (const [cents, expected] of [[0, '0.00'], [1, '0.01'], [1250, '12.50'], [9_999_999_999, '99999999.99']] as const)
    need(euroPrice('42', null, { 42: [product(cents)] }) === expected);
});
Deno.test('fractional negative nonfinite string and out-of-range cents are refused', () => {
  for (const cents of [-1, .5, NaN, Infinity, '1250', 10_000_000_000])
    rejects(() => euroPrice('42', null, { 42: [{ ...product(), price_cents: cents }] }), 'QUOTE_CENTS_INVALID');
});
Deno.test('amount and currency always come from the same pair', () => {
  const originalEUR = { ...product(), price: { cents: 9999, currency: 'USD' } };
  need(euroPrice('42', null, { 42: [originalEUR] }) === '12.50');
  rejects(() => euroPrice('42', null, { 42: [{ ...product(50, 'USD'), price: { cents: 40, currency: 'EUR' } }] }), 'QUOTE_CURRENCY_UNSUPPORTED');
  rejects(() => euroPrice('42', null, { 42: [{ ...product(), price_currency: undefined }] }), 'QUOTE_CURRENCY_UNSUPPORTED');
});
Deno.test('documented nested price pair is accepted only when the raw pair is absent', () => {
  need(euroPrice('42', null, { 42: [{ blueprint_id: 42, price: { cents: 305, currency: 'EUR' } }] }) === '3.05');
  rejects(() => euroPrice('42', null, { 42: [{ blueprint_id: 42, price: { cents: 305, currency: 'USD' } }] }));
});
Deno.test('CT Zero preference and exact condition match retain existing precedence', () => {
  need(euroPrice('42', 'near_mint', { 42: [{ ...product(1), user: { can_sell_via_hub: false } },
    { ...product(3), properties_hash: { condition: 'Poor' } }, product(5)] }) === '0.05');
});
Deno.test('absent CT Zero and absent exact condition preserve explicit existing fallback', () => {
  need(euroPrice('42', 'damaged', { 42: [{ ...product(125), user: { can_sell_via_hub: false } }] }) === '1.25');
  need(euroPrice('42', null, { 42: [] }) === null);
  rejects(() => euroPrice('42', null, {}), 'QUOTE_RESPONSE_INVALID');
});
Deno.test('mixed currency responses cannot silently choose an unvalidated listing', () => {
  rejects(() => euroPrice('42', null, { 42: [product(100), product(1, 'USD')] }), 'QUOTE_CURRENCY_UNSUPPORTED');
});
Deno.test('CAS includes owner blueprint category currency condition old price and timestamp including SQL NULL', () => {
  const filters: unknown[][] = [];
  const query = { eq: (key: string, value: unknown) => { filters.push(['eq', key, value]); return query; },
    is: (key: string, value: unknown) => { filters.push(['is', key, value]); return query; } };
  compareAndSwap(query, row(null));
  need(JSON.stringify(filters) === JSON.stringify([['eq', 'id', A], ['eq', 'user_id', A],
    ['eq', 'category', 'magic'], ['eq', 'card_id', '42'], ['is', 'currency', null],
    ['eq', 'condition', 'near_mint'], ['is', 'current_price', null], ['is', 'updated_at', null]]));
});
