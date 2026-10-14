/** Direct CardTrader quote v2: each amount stays bound to its declared currency and blueprint. */
export class PriceFailure extends Error {
  constructor(readonly code: string) { super(code); this.name = 'PriceFailure'; }
}
const CONDITIONS: Record<string, string> = {
  near_mint: 'Near Mint', lightly_played: 'Slightly Played',
  moderately_played: 'Moderately Played', heavily_played: 'Heavily Played', damaged: 'Poor',
};
function need(ok: unknown, code: string): asserts ok {
  if (!ok) throw new PriceFailure(code);
}
function record(value: unknown): Record<string, any> {
  need(value !== null && typeof value === 'object' && !Array.isArray(value), 'PRODUCT_INVALID');
  return value as Record<string, any>;
}
export function blueprintId(value: unknown): string {
  need(typeof value === 'string' && /^[1-9][0-9]{0,14}$/.test(value) &&
    Number.isSafeInteger(Number(value)), 'BLUEPRINT_ID_INVALID');
  return value;
}
function centsForProduct(value: unknown, requested: string): number {
  const product = record(value);
  need(Number.isSafeInteger(product.blueprint_id) && String(product.blueprint_id) === requested,
    'QUOTE_BLUEPRINT_MISMATCH');
  // Keep each amount with its own declared currency; these two pairs can differ in the API.
  const topPair = Object.hasOwn(product, 'price_cents') || Object.hasOwn(product, 'price_currency');
  const price = topPair ? { cents: product.price_cents, currency: product.price_currency } : record(product.price);
  need(price.currency === 'EUR', 'QUOTE_CURRENCY_UNSUPPORTED');
  need(typeof price.cents === 'number' && Number.isSafeInteger(price.cents) &&
    price.cents >= 0 && price.cents <= 9_999_999_999, 'QUOTE_CENTS_INVALID');
  return price.cents;
}
export function euroPrice(blueprint: string, condition: string | null, payload: unknown): string | null {
  const requested = blueprintId(blueprint);
  need(condition === null || Object.hasOwn(CONDITIONS, condition), 'ROW_CONDITION_INVALID');
  const data = record(payload);
  const products = data[requested];
  need(Array.isArray(products), 'QUOTE_RESPONSE_INVALID');
  if (products.length === 0) return null;
  // A malformed/mixed-currency response fails this card instead of silently omitting a cheap listing.
  const validated = products.map(product => ({ product: record(product), cents: centsForProduct(product, requested) }));
  const zero = validated.filter(({ product }) => product.user?.can_sell_via_hub === true);
  let candidates = zero.length > 0 ? zero : validated;
  if (condition !== null) {
    const matching = candidates.filter(({ product }) => product.properties_hash?.condition === CONDITIONS[condition]);
    if (matching.length > 0) candidates = matching;
  }
  // Preserve the existing CT Zero/exact-condition fallback; cents need no monetary rounding.
  const cents = BigInt(candidates.reduce((minimum, item) => item.cents < minimum ? item.cents : minimum, candidates[0].cents));
  return `${cents / 100n}.${(cents % 100n).toString().padStart(2, '0')}`;
}

export interface PriceDto {
  quoteContractVersion: 2;
  blueprintId: string;
  currency: 'EUR';
  minorUnit: 2;
  ctZeroLowPrice: string | null;
  ctZeroLowPriceCents: number | null;
  totalListings: number;
}
export function priceRequest(blueprint: unknown, condition: string | null): string {
  const id = blueprintId(blueprint);
  need(condition === null || Object.hasOwn(CONDITIONS, condition), 'ROW_CONDITION_INVALID');
  return id;
}
export function priceDto(blueprint: string, condition: string | null, payload: unknown): PriceDto {
  const id = priceRequest(blueprint, condition);
  const price = euroPrice(id, condition, payload);
  const products = record(payload)[id] as unknown[];
  return {
    quoteContractVersion: 2, blueprintId: id, currency: 'EUR', minorUnit: 2,
    // Never reuse provider formatted text: its currency can belong to the other price pair.
    ctZeroLowPrice: price === null ? null : `€${price}`,
    ctZeroLowPriceCents: price === null ? null : Number(BigInt(price.replace('.', ''))),
    totalListings: products.length,
  };
}
type Fetcher = (url: string, init?: RequestInit) => Promise<Response>;
export async function fetchPriceDto(blueprint: string, condition: string | null, apiKey: string,
  fetcher: Fetcher = fetch): Promise<PriceDto> {
  const id = priceRequest(blueprint, condition);
  const response = await fetcher(`https://api.cardtrader.com/api/v2/marketplace/products?blueprint_id=${encodeURIComponent(id)}`,
    { headers: { Authorization: `Bearer ${apiKey}` }, redirect: 'error', signal: AbortSignal.timeout(10_000) });
  if (!response.ok) { await response.body?.cancel(); throw new PriceFailure('PROVIDER_HTTP_ERROR'); }
  if (!response.body) throw new PriceFailure('PROVIDER_RESPONSE_INVALID');
  const reader = response.body.getReader(), decoder = new TextDecoder();
  let size = 0, text = '';
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      size += chunk.value.byteLength;
      if (size > 1_048_576) { await reader.cancel(); throw new PriceFailure('PROVIDER_RESPONSE_INVALID'); }
      text += decoder.decode(chunk.value, { stream: true });
    }
    text += decoder.decode();
    let payload: unknown;
    try { payload = JSON.parse(text); } catch { throw new PriceFailure('PROVIDER_RESPONSE_INVALID'); }
    return priceDto(id, condition, payload);
  } finally { reader.releaseLock(); }
}
