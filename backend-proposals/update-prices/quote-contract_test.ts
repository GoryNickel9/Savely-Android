import { assetCurrency, stockSymbol, cryptoId, yahooQuote, coinGeckoQuote, priceForAsset,
  roundedAssetPrice, cashRate, fetchStockQuote, fetchCryptoQuote, fetchCashRate, QuoteFailure } from './quote-contract.ts';

function equal(actual: unknown, expected: unknown) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new Error('SYNTHETIC_EXPECTATION_MISMATCH');
}
function fails(code: string, action: () => unknown) {
  try { action(); } catch (error) {
    if (error instanceof QuoteFailure && error.code === code) return;
    throw new Error('SYNTHETIC_FAILURE_CODE_MISMATCH');
  }
  throw new Error('SYNTHETIC_REJECTION_MISSING');
}
async function rejects(code: string, action: () => Promise<unknown>) {
  try { await action(); } catch (error) {
    if (error instanceof QuoteFailure && error.code === code) return;
    throw new Error('SYNTHETIC_FAILURE_CODE_MISMATCH');
  }
  throw new Error('SYNTHETIC_REJECTION_MISSING');
}
function yahoo(symbol = 'AAPL', currency: unknown = 'USD', price: unknown = 173.325) {
  return { chart: { error: null, result: [{ meta: { symbol, currency, regularMarketPrice: price } }] } };
}
const asset = { symbol: 'AAPL', type: 'stock', currency: 'EUR' };

Deno.test('USD Yahoo quote must not be persisted as an EUR security price', () => {
  fails('QUOTE_CURRENCY_MISMATCH', () => priceForAsset(asset, yahooQuote('AAPL', yahoo())));
});
Deno.test('matching security currency preserves identity and yields one canonical decimal', () => {
  const quote = yahooQuote('AAPL', yahoo());
  equal([quote.resolvedSymbol, quote.providerCurrency, quote.provider], ['AAPL', 'USD', 'yahoo']);
  equal(priceForAsset({ ...asset, currency: 'USD' }, quote), '173.33');
});
Deno.test('an exchange suffix is part of identity, never a fallback', async () => {
  const calls: string[] = [];
  await rejects('PROVIDER_HTTP_ERROR', () => fetchStockQuote('AAPL.MI', async url => {
    calls.push(url); return new Response('{}', { status: 404 });
  }));
  equal(calls, ['https://query1.finance.yahoo.com/v8/finance/chart/AAPL.MI?interval=1d&range=1d']);
});
Deno.test('provider symbol changes are rejected even with a matching EUR currency', () => {
  fails('QUOTE_SYMBOL_MISMATCH', () => yahooQuote('AAPL', yahoo('AAPL.DE', 'EUR')));
});
Deno.test('GBp and GBX are explicitly rejected instead of becoming GBP', () => {
  for (const currency of ['GBp', 'GBX']) fails('QUOTE_MINOR_UNIT_UNSUPPORTED', () => yahooQuote('VOD.L', yahoo('VOD.L', currency)));
  equal(priceForAsset({ symbol: 'VOD.L', type: 'stock', currency: 'GBP' }, yahooQuote('VOD.L', yahoo('VOD.L', 'GBP', 1.25))), '1.25');
});
Deno.test('missing unsupported and malformed provider currencies fail closed', () => {
  for (const value of [undefined, null, '']) {
    const payload = yahoo(); payload.chart.result[0].meta.currency = value;
    fails('QUOTE_CURRENCY_MISSING', () => yahooQuote('AAPL', payload));
  }
  for (const value of ['usd', ' EUR ', 'GBx', 'UNKNOWN']) fails('QUOTE_CURRENCY_UNSUPPORTED', () => yahooQuote('AAPL', yahoo('AAPL', value)));
});
Deno.test('only an explicit legacy NULL uses EUR, not a missing or empty DB currency', () => {
  equal(assetCurrency(null), 'EUR');
  equal(priceForAsset({ ...asset, currency: null }, yahooQuote('AAPL', yahoo('AAPL', 'EUR'))), '173.33');
  fails('QUOTE_CURRENCY_MISMATCH', () => priceForAsset({ ...asset, currency: null }, yahooQuote('AAPL', yahoo())));
  for (const value of [undefined, '', 'usd', 'GBp']) fails('CURRENCY_INVALID', () => assetCurrency(value));
});
Deno.test('crypto EUR prices cannot be written into USD holdings', () => {
  const quote = coinGeckoQuote('BTC', { bitcoin: { eur: 12345.675 } });
  equal([quote.resolvedSymbol, quote.providerCurrency], ['bitcoin', 'EUR']);
  equal(priceForAsset({ symbol: 'BTC', type: 'crypto', currency: 'EUR' }, quote), '12345.68');
  fails('QUOTE_CURRENCY_MISMATCH', () => priceForAsset({ symbol: 'BTC', type: 'crypto', currency: 'USD' }, quote));
});
Deno.test('crypto provider failure makes one request and never searches a different coin', async () => {
  const calls: string[] = [];
  await rejects('PROVIDER_HTTP_ERROR', () => fetchCryptoQuote('BTC', async url => {
    calls.push(url); return new Response('{}', { status: 503 });
  }));
  equal(calls, ['https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=eur']);
  fails('QUOTE_PRICE_INVALID', () => coinGeckoQuote('BTC', { ethereum: { eur: 123 } }));
});
Deno.test('crypto IDs are explicit and pairs cannot silently discard their suffix', () => {
  equal(cryptoId('btc'), 'bitcoin');
  equal(cryptoId('rocket-pool-eth'), 'rocket-pool-eth');
  for (const symbol of ['BTC/USD', 'BTC-EUR', 'UNKNOWN', '../bitcoin'])
    fails('CRYPTO_SYMBOL_UNSUPPORTED', () => cryptoId(symbol));
});
Deno.test('same-instrument close sources may be used only with verified metadata', () => {
  const payload = { chart: { result: [{ meta: { symbol: 'AAPL', currency: 'USD', previousClose: 9.5 },
    indicators: { quote: [{ close: [8, 9.25] }] } }] } };
  equal(yahooQuote('AAPL', payload).price, 9.25);
  payload.chart.result[0].indicators.quote[0].close = [];
  equal(yahooQuote('AAPL', payload).price, 9.5);
});
Deno.test('prices must be finite positive JSON numbers, never numeric strings', () => {
  for (const price of [0, -1, null, '1.25', NaN, Infinity])
    fails('QUOTE_PRICE_INVALID', () => yahooQuote('AAPL', yahoo('AAPL', 'USD', price)));
});
Deno.test('HALF_UP cents are shared by current price and history, including decimal ties', () => {
  equal([roundedAssetPrice(1.005), roundedAssetPrice(2.675), roundedAssetPrice(.005)], ['1.01', '2.68', '0.01']);
  equal(roundedAssetPrice(1.234e2), '123.40');
  for (const price of [.0049, 1e-20, 1e13, 1e308]) fails('QUOTE_PRICE_UNREPRESENTABLE', () => roundedAssetPrice(price));
});
Deno.test('cash FX remains one foreign-currency unit in EUR with the schema rounding', () => {
  equal(cashRate('USD', { base: 'USD', rates: { EUR: .92345 } }), '0.92');
  equal(cashRate('JPY', { base: 'JPY', rates: { EUR: .006 } }), '0.01');
  fails('QUOTE_CURRENCY_MISMATCH', () => cashRate('USD', { base: 'EUR', rates: { EUR: 1 } }));
  fails('QUOTE_PRICE_UNREPRESENTABLE', () => cashRate('IDR', { base: 'IDR', rates: { EUR: .00006 } }));
});
Deno.test('cash lookup performs exactly one EUR conversion request', async () => {
  const calls: string[] = [];
  equal(await fetchCashRate('USD', async url => { calls.push(url); return Response.json({ base: 'USD', rates: { EUR: .92 } }); }), '0.92');
  equal(calls, ['https://api.frankfurter.app/latest?from=USD&to=EUR']);
});
Deno.test('provider redirects are not followed and no caller authorization is sent', async () => {
  let count = 0;
  await rejects('PROVIDER_HTTP_ERROR', () => fetchStockQuote('AAPL', async (_url, init) => {
    count++; equal(init?.redirect, 'error');
    equal(new Headers(init?.headers).get('Authorization'), null);
    equal(new Headers(init?.headers).get('apikey'), null);
    return new Response('', { status: 302, headers: { Location: 'https://other.invalid' } });
  }));
  equal(count, 1);
});
Deno.test('oversized malformed and transport-failed provider responses remain safe errors', async () => {
  await rejects('PROVIDER_RESPONSE_INVALID', () => fetchStockQuote('AAPL', async () => new Response('x'.repeat(1_048_577))));
  await rejects('PROVIDER_RESPONSE_INVALID', () => fetchStockQuote('AAPL', async () => new Response('not json')));
  await rejects('PROVIDER_UNAVAILABLE', () => fetchStockQuote('AAPL', async () => { throw new Error('synthetic private provider body'); }));
});
Deno.test('invalid symbols and non-security assets cannot produce a security update', () => {
  for (const symbol of ['', '../AAPL', 'AAPL?token=secret', 'AAPL/USD']) fails('SYMBOL_INVALID', () => stockSymbol(symbol));
  fails('ASSET_TYPE_UNSUPPORTED', () => priceForAsset({ ...asset, type: 'cash' }, yahooQuote('AAPL', yahoo())));
});
