import { blueprintId, priceRequest, priceDto, fetchPriceDto } from './quote-contract.ts';

const product = (cents = 1250, currency = 'EUR', blueprint = 42) => ({ blueprint_id: blueprint,
  price_cents: cents, price_currency: currency, user: { can_sell_via_hub: true },
  properties_hash: { condition: 'Near Mint' } });
const need = (value: unknown) => { if (!value) throw new Error('ASSERTION_FAILED'); };
function rejects(run: () => unknown, code?: string) {
  try { run(); } catch (error) { need(!code || (error as Error).message === code); return; }
  throw new Error('EXPECTED_REJECTION');
}
const dto = (products: unknown[]) => priceDto('42', 'near_mint', { 42: products });
Deno.test('only canonical positive numeric blueprint identifiers are accepted', () => {
  need(blueprintId('42') === '42');
  for (const id of ['042', '0', '-1', '42.5', 'legacy-uuid', '1000000000000000', 42, null])
    rejects(() => blueprintId(id), 'BLUEPRINT_ID_INVALID');
});
Deno.test('condition boundary rejects unknown values instead of assuming Near Mint', () => {
  need(priceRequest('42', null) === '42');
  rejects(() => priceRequest('42', 'unknown'), 'ROW_CONDITION_INVALID');
});
Deno.test('DTO v2 binds price unit and exact blueprint and preserves legacy consumer keys', () => {
  need(JSON.stringify(dto([product()])) === JSON.stringify({ quoteContractVersion: 2, blueprintId: '42',
    currency: 'EUR', minorUnit: 2, ctZeroLowPrice: '€12.50', ctZeroLowPriceCents: 1250, totalListings: 1 }));
});
Deno.test('zero one cent and SQL maximum are exact integer cents with no rounding', () => {
  for (const [cents, formatted] of [[0, '€0.00'], [1, '€0.01'], [9_999_999_999, '€99999999.99']] as const) {
    const result = dto([product(cents)]); need(result.ctZeroLowPriceCents === cents && result.ctZeroLowPrice === formatted);
  }
});
Deno.test('fractional negative string nonfinite and out-of-range cents are refused', () => {
  for (const cents of [-1, .5, '1250', NaN, Infinity, 10_000_000_000])
    rejects(() => dto([{ ...product(), price_cents: cents }]), 'QUOTE_CENTS_INVALID');
});
Deno.test('foreign blueprint under the requested response key is refused', () => {
  rejects(() => dto([product(1, 'EUR', 43)]), 'QUOTE_BLUEPRINT_MISMATCH');
});
Deno.test('raw EUR paired amount ignores formatted text from nested USD', () => {
  const result = dto([{ ...product(), price: { cents: 9999, currency: 'USD', formatted: '$99.99' } }]);
  need(result.ctZeroLowPriceCents === 1250 && result.ctZeroLowPrice === '€12.50');
});
Deno.test('raw USD is not relabeled EUR using a nested EUR declaration', () => {
  rejects(() => dto([{ ...product(50, 'USD'), price: { cents: 40, currency: 'EUR' } }]), 'QUOTE_CURRENCY_UNSUPPORTED');
});
Deno.test('incomplete raw price pair cannot borrow a nested currency', () => {
  rejects(() => dto([{ ...product(), price_currency: undefined, price: { cents: 1250, currency: 'EUR' } }]), 'QUOTE_CURRENCY_UNSUPPORTED');
});
Deno.test('nested EUR pair is supported only when both raw fields are absent', () => {
  need(dto([{ blueprint_id: 42, price: { cents: 305, currency: 'EUR' } }]).ctZeroLowPriceCents === 305);
  rejects(() => dto([{ blueprint_id: 42, price: { cents: 305, currency: 'USD' } }]), 'QUOTE_CURRENCY_UNSUPPORTED');
});
Deno.test('mixed currencies cannot silently omit the cheapest foreign listing', () => {
  rejects(() => dto([product(100), product(1, 'USD')]), 'QUOTE_CURRENCY_UNSUPPORTED');
});
Deno.test('CT Zero exact condition and fallback order remains unchanged', () => {
  const products = [{ ...product(1), user: { can_sell_via_hub: false } },
    { ...product(3), properties_hash: { condition: 'Poor' } }, product(5)];
  need(dto(products).ctZeroLowPriceCents === 5);
  need(priceDto('42', 'lightly_played', { 42: products }).ctZeroLowPriceCents === 3);
  need(dto([{ ...product(2), user: { can_sell_via_hub: false } }]).ctZeroLowPriceCents === 2);
});
Deno.test('explicit empty listings return null with identity and EUR unit intact', () => {
  const result = dto([]);
  need(result.ctZeroLowPriceCents === null && result.ctZeroLowPrice === null && result.totalListings === 0 &&
    result.currency === 'EUR' && result.blueprintId === '42' && result.quoteContractVersion === 2);
});
Deno.test('missing response key malformed listing and malformed envelope are errors not null', () => {
  for (const payload of [{}, [], null, { 42: null }, { 42: [null] }])
    rejects(() => priceDto('42', null, payload));
});
Deno.test('provider request is exact bounded and carries only the server CardTrader credential', async () => {
  let calls = 0;
  const result = await fetchPriceDto('42', null, 'synthetic-server-key', async (url, options) => {
    calls++; need(url === 'https://api.cardtrader.com/api/v2/marketplace/products?blueprint_id=42');
    need(options?.redirect === 'error' && options.signal instanceof AbortSignal);
    need(JSON.stringify(options?.headers) === JSON.stringify({ Authorization: 'Bearer synthetic-server-key' }));
    return Response.json({ 42: [product()] });
  });
  need(calls === 1 && result.ctZeroLowPriceCents === 1250 && !JSON.stringify(result).includes('synthetic-server-key'));
});
Deno.test('provider 503 invalid JSON oversized JSON and bad input cannot trigger automatic retry', async () => {
  for (const mode of ['503', 'invalid', 'oversized', 'bad-id']) {
    let calls = 0, failed = false;
    try {
      await fetchPriceDto(mode === 'bad-id' ? 'legacy' : '42', null, 'synthetic', async () => {
        calls++;
        return mode === '503' ? new Response('{}', { status: 503 }) :
          new Response(mode === 'invalid' ? '{broken' : ' '.repeat(1_048_577));
      });
    } catch { failed = true; }
    need(failed && calls === (mode === 'bad-id' ? 0 : 1));
  }
});
