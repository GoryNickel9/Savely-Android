/** CardTrader quote identity/unit boundary. No database, credentials or network. */
export class PriceFailure extends Error {
  constructor(readonly code: string) { super(code); this.name = 'PriceFailure'; }
}
const CONDITIONS: Record<string, string> = {
  near_mint: 'Near Mint', lightly_played: 'Slightly Played',
  moderately_played: 'Moderately Played', heavily_played: 'Heavily Played', damaged: 'Poor',
};
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export interface TcgCard {
  id: string; user_id: string; category: string; card_id: string | null;
  currency: string | null; condition: string | null; current_price: number | string | null;
  updated_at: string | null;
}
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
export function eligibleCard(value: unknown): TcgCard {
  const row = record(value);
  need(typeof row.id === 'string' && UUID.test(row.id) &&
    typeof row.user_id === 'string' && UUID.test(row.user_id), 'ROW_IDENTITY_INVALID');
  // Both clients interpret legacy SQL NULL as EUR. Missing fields are not SQL NULL.
  need(row.currency === 'EUR' || row.currency === null, 'ROW_CURRENCY_UNSUPPORTED');
  blueprintId(row.card_id);
  need(['magic', 'pokemon', 'yugioh', 'lorcana'].includes(row.category), 'ROW_CATEGORY_INVALID');
  need(row.condition === null || typeof row.condition === 'string' &&
    Object.hasOwn(CONDITIONS, row.condition), 'ROW_CONDITION_INVALID');
  need(row.updated_at === null || typeof row.updated_at === 'string' &&
    row.updated_at.length <= 64 && Number.isFinite(Date.parse(row.updated_at)), 'ROW_TIME_INVALID');
  need(row.current_price === null ||
    (typeof row.current_price === 'number' || typeof row.current_price === 'string') &&
    /^(?:0|[1-9][0-9]{0,7})(?:\.[0-9]{1,2})?$/.test(String(row.current_price)), 'ROW_PRICE_INVALID');
  return row as TcgCard;
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
export function compareAndSwap(query: any, card: TcgCard): any {
  eligibleCard(card);
  let guarded = query.eq('id', card.id).eq('user_id', card.user_id)
    .eq('category', card.category).eq('card_id', card.card_id);
  for (const key of ['currency', 'condition', 'current_price', 'updated_at'] as const)
    guarded = card[key] === null ? guarded.is(key, null) : guarded.eq(key, card[key]);
  return guarded;
}
