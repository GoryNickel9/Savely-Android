/** Pure quote contract plus one exact provider request. No credentials, database client or retries. */
export const QUOTE_CONTRACT_VERSION = 2;
const CURRENCIES = new Set(['EUR', 'USD', 'GBP', 'CHF', 'JPY', 'CAD', 'AUD', 'CNY', 'IDR']);
const CRYPTO_IDS: Record<string, string> = {
  BTC: 'bitcoin', ETH: 'ethereum', SOL: 'solana', ADA: 'cardano', DOT: 'polkadot',
  AVAX: 'avalanche-2', MATIC: 'polygon', LINK: 'chainlink', UNI: 'uniswap', XRP: 'ripple',
  DOGE: 'dogecoin', LTC: 'litecoin', BNB: 'binancecoin', USDT: 'tether', USDC: 'usd-coin',
  ATOM: 'cosmos', NEAR: 'near', APT: 'aptos', ARB: 'arbitrum', OP: 'optimism',
  INJ: 'injective-protocol', SUI: 'sui', SEI: 'sei-network', TIA: 'celestia', PEPE: 'pepe',
  SHIB: 'shiba-inu', XLM: 'stellar', ALGO: 'algorand', VET: 'vechain', FTM: 'fantom',
  SAND: 'the-sandbox', MANA: 'decentraland', AXS: 'axie-infinity', AAVE: 'aave', MKR: 'maker',
  CRV: 'curve-dao-token', LDO: 'lido-dao', RETH: 'rocket-pool-eth', STETH: 'staked-ether',
};
export type QuoteCode = 'SYMBOL_INVALID' | 'CRYPTO_SYMBOL_UNSUPPORTED' | 'CURRENCY_INVALID' |
  'QUOTE_CURRENCY_MISSING' | 'QUOTE_CURRENCY_UNSUPPORTED' | 'QUOTE_MINOR_UNIT_UNSUPPORTED' |
  'QUOTE_CURRENCY_MISMATCH' | 'QUOTE_SYMBOL_MISMATCH' | 'QUOTE_PRICE_INVALID' |
  'QUOTE_PRICE_UNREPRESENTABLE' | 'PROVIDER_HTTP_ERROR' | 'PROVIDER_UNAVAILABLE' |
  'PROVIDER_RESPONSE_INVALID' | 'ASSET_TYPE_UNSUPPORTED';
export class QuoteFailure extends Error {
  constructor(readonly code: QuoteCode) { super(code); this.name = 'QuoteFailure'; }
}
export interface Quote {
  price: number;
  providerCurrency: string;
  resolvedSymbol: string;
  provider: 'yahoo' | 'coingecko';
}
export interface QuotedAsset { symbol: string; type: string; currency: string | null; }
type Fetcher = (url: string, init?: RequestInit) => Promise<Response>;
function record(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
}
function positive(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0;
}
export function assetCurrency(value: unknown): string {
  // Schema and both clients treat legacy NULL currency as EUR; missing/malformed fields are not NULL.
  if (value === null) return 'EUR';
  if (typeof value !== 'string' || !CURRENCIES.has(value)) throw new QuoteFailure('CURRENCY_INVALID');
  return value;
}
export function stockSymbol(value: string): string {
  const symbol = value.trim().toUpperCase();
  if (!/^[A-Z0-9][A-Z0-9.-]{0,31}$/.test(symbol)) throw new QuoteFailure('SYMBOL_INVALID');
  return symbol;
}
export function cryptoId(symbol: string): string {
  // A known ticker mapping is explicit; arbitrary search/first-result resolution is forbidden.
  const clean = symbol.trim();
  const mapped = CRYPTO_IDS[clean.toUpperCase()];
  if (mapped) return mapped;
  if (clean === clean.toLowerCase() && /^[a-z0-9][a-z0-9-]{0,63}$/.test(clean)) return clean;
  throw new QuoteFailure('CRYPTO_SYMBOL_UNSUPPORTED');
}
function providerCurrency(value: unknown): string {
  if (typeof value !== 'string' || !value) throw new QuoteFailure('QUOTE_CURRENCY_MISSING');
  // GBp is pence, not GBP. Uppercasing first would change the monetary unit by 100.
  if (value === 'GBp' || value === 'GBX') throw new QuoteFailure('QUOTE_MINOR_UNIT_UNSUPPORTED');
  if (!CURRENCIES.has(value)) throw new QuoteFailure('QUOTE_CURRENCY_UNSUPPORTED');
  return value;
}
export function yahooQuote(requestedSymbol: string, payload: unknown): Quote {
  const chart = record(record(payload).chart);
  const results = chart.result;
  if (chart.error != null || !Array.isArray(results) || results.length !== 1)
    throw new QuoteFailure('PROVIDER_RESPONSE_INVALID');
  const result = record(results[0]);
  const meta = record(result.meta);
  const requested = stockSymbol(requestedSymbol);
  if (typeof meta.symbol !== 'string' || meta.symbol !== requested) throw new QuoteFailure('QUOTE_SYMBOL_MISMATCH');
  const currency = providerCurrency(meta.currency);
  const quotes = record(result.indicators).quote;
  const closes = Array.isArray(quotes) ? record(quotes[0]).close : undefined;
  const lastClose = Array.isArray(closes) ? closes[closes.length - 1] : undefined;
  const price = [meta.regularMarketPrice, lastClose, meta.previousClose].find(positive);
  if (!positive(price)) throw new QuoteFailure('QUOTE_PRICE_INVALID');
  return { price, providerCurrency: currency, resolvedSymbol: requested, provider: 'yahoo' };
}
export function coinGeckoQuote(requestedSymbol: string, payload: unknown): Quote {
  const id = cryptoId(requestedSymbol);
  const price = record(record(payload)[id]).eur;
  if (!positive(price)) throw new QuoteFailure('QUOTE_PRICE_INVALID');
  return { price, providerCurrency: 'EUR', resolvedSymbol: id, provider: 'coingecko' };
}
/** Decimal HALF_UP into NUMERIC(15,2), using the provider Number's shortest decimal representation. */
export function roundedAssetPrice(price: number): string {
  if (!positive(price)) throw new QuoteFailure('QUOTE_PRICE_INVALID');
  const [mantissa, exponent = '0'] = price.toString().split(/[eE]/);
  const [whole, fraction = ''] = mantissa.split('.');
  const scale = fraction.length - Number(exponent);
  const digits = BigInt(whole + fraction);
  const divisor = scale > 2 ? 10n ** BigInt(scale - 2) : 1n;
  const cents = scale > 2 ? (digits + divisor / 2n) / divisor : digits * 10n ** BigInt(2 - scale);
  if (cents <= 0n || cents > 999999999999999n) throw new QuoteFailure('QUOTE_PRICE_UNREPRESENTABLE');
  return `${cents / 100n}.${(cents % 100n).toString().padStart(2, '0')}`;
}
export function priceForAsset(asset: QuotedAsset, quote: Quote): string {
  const currency = assetCurrency(asset.currency);
  if (asset.type === 'stock' || asset.type === 'etf') {
    if (quote.provider !== 'yahoo' || quote.resolvedSymbol !== stockSymbol(asset.symbol))
      throw new QuoteFailure('QUOTE_SYMBOL_MISMATCH');
  } else if (asset.type === 'crypto') {
    if (quote.provider !== 'coingecko' || quote.resolvedSymbol !== cryptoId(asset.symbol))
      throw new QuoteFailure('QUOTE_SYMBOL_MISMATCH');
  } else throw new QuoteFailure('ASSET_TYPE_UNSUPPORTED');
  const quotedCurrency = providerCurrency(quote.providerCurrency);
  if (currency !== quotedCurrency) throw new QuoteFailure('QUOTE_CURRENCY_MISMATCH');
  return roundedAssetPrice(quote.price);
}
export function cashRate(currency: string, payload: unknown): string {
  if (assetCurrency(currency) === 'EUR') throw new QuoteFailure('CURRENCY_INVALID');
  const data = record(payload);
  if (data.base !== currency) throw new QuoteFailure('QUOTE_CURRENCY_MISMATCH');
  const rate = record(data.rates).EUR;
  if (!positive(rate)) throw new QuoteFailure('QUOTE_PRICE_INVALID');
  return roundedAssetPrice(rate);
}
async function providerJson(url: string, fetcher: Fetcher): Promise<unknown> {
  try {
    const response = await fetcher(url, { headers: { Accept: 'application/json', 'User-Agent': 'Savely quote contract/2' },
      redirect: 'error', signal: AbortSignal.timeout(10_000) });
    if (!response.ok) { await response.body?.cancel(); throw new QuoteFailure('PROVIDER_HTTP_ERROR'); }
    if (!response.body) throw new QuoteFailure('PROVIDER_RESPONSE_INVALID');
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let size = 0, text = '';
    try {
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) break;
        size += chunk.value.byteLength;
        if (size > 1_048_576) { await reader.cancel(); throw new QuoteFailure('PROVIDER_RESPONSE_INVALID'); }
        text += decoder.decode(chunk.value, { stream: true });
      }
      text += decoder.decode();
      try { return JSON.parse(text); }
      catch { throw new QuoteFailure('PROVIDER_RESPONSE_INVALID'); }
    } finally { reader.releaseLock(); }
  } catch (error) {
    if (error instanceof QuoteFailure) throw error;
    throw new QuoteFailure('PROVIDER_UNAVAILABLE');
  }
}
export async function fetchStockQuote(symbol: string, fetcher: Fetcher = fetch): Promise<Quote> {
  const requested = stockSymbol(symbol);
  const url = `https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(requested)}?interval=1d&range=1d`;
  return yahooQuote(requested, await providerJson(url, fetcher));
}
export async function fetchCryptoQuote(symbol: string, fetcher: Fetcher = fetch): Promise<Quote> {
  const id = cryptoId(symbol);
  const url = `https://api.coingecko.com/api/v3/simple/price?ids=${encodeURIComponent(id)}&vs_currencies=eur`;
  return coinGeckoQuote(symbol, await providerJson(url, fetcher));
}
export async function fetchCashRate(currency: string, fetcher: Fetcher = fetch): Promise<string> {
  if (assetCurrency(currency) === 'EUR') throw new QuoteFailure('CURRENCY_INVALID');
  const url = `https://api.frankfurter.app/latest?from=${encodeURIComponent(currency)}&to=EUR`;
  return cashRate(currency, await providerJson(url, fetcher));
}
