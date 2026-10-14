// @ts-ignore - Supabase Edge Functions don't have complete type definitions
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';
import { assetCurrency, fetchStockQuote, fetchCryptoQuote, fetchCashRate, priceForAsset, roundedAssetPrice, QuoteFailure, QUOTE_CONTRACT_VERSION } from './quote-contract.ts';

// @ts-ignore - Deno is available in Supabase Edge Functions
const ALLOWED_ORIGINS = (Deno.env.get('ALLOWED_ORIGIN') || '')
  .split(',')
  .map((o) => o.trim())
  .filter(Boolean);

function getCorsHeaders(req: any): Record<string, string> {
  const requestOrigin: string | null = req.headers.get('origin');
  const isLocalhost =
    requestOrigin?.startsWith('http://localhost') ||
    requestOrigin?.startsWith('http://127.0.0.1');
  const isAllowed = requestOrigin != null && ALLOWED_ORIGINS.includes(requestOrigin);
  const origin = isLocalhost || isAllowed ? requestOrigin! : (ALLOWED_ORIGINS[0] ?? '');
  return {
    'Access-Control-Allow-Origin': origin,
    'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  };
}

interface Asset {
  id: string;
  user_id: string;
  symbol: string;
  type: string;
  current_price: number | null;
  currency: string | null;
  updated_at: string;
}

// @ts-ignore - Deno is available in Edge Functions
Deno.serve(async (req: any) => {
  const corsHeaders = getCorsHeaders(req);
  // Handle CORS preflight
  if (req.method === 'OPTIONS') {
    return new Response('ok', {
      status: 200,
      headers: corsHeaders
    });
  }

  try {
    // @ts-ignore - Deno is available in Supabase Edge Functions
    const supabaseUrl = Deno.env.get('SUPABASE_URL')!;
    // @ts-ignore
    const supabaseServiceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!;
    // @ts-ignore
    const supabaseAnonKey = Deno.env.get('SUPABASE_ANON_KEY')!;
    const supabase = createClient(supabaseUrl, supabaseServiceKey);

    // Authenticate the caller.
    // - If Authorization header is present: validate as user JWT (restrict to that user's assets).
    // - If no Authorization header: require a valid x-cron-secret header (cron jobs).
    //   verify_jwt=false rende l'endpoint HTTP pubblico, quindi il secret è obbligatorio
    //   per impedire abuso anonimo (S-3).
    const authHeader = req.headers.get('Authorization');

    // Distinguish user JWT from cron/service call.
    // For user JWTs: restrict updates to caller's own assets (prevents IDOR).
    // For cron (no auth header): allow optional user_id filter from body, or update all.
    let userId: string | null = null;
    if (authHeader) {
      const userClient = createClient(supabaseUrl, supabaseAnonKey, {
        global: { headers: { Authorization: authHeader } },
      });
      const { data: { user } } = await userClient.auth.getUser();

      if (user) {
        // Authenticated user: only allow updating their own assets
        userId = user.id;
      } else {
        // Invalid JWT — reject
        return new Response(JSON.stringify({ error: 'Unauthorized' }), {
          status: 401,
          headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        });
      }
    } else {
      // No auth header → must be the cron via x-cron-secret (S-3).
      // In passato questo branch era aperto a chiunque (abuso/DoS possibile).
      const providedSecret = req.headers.get('x-cron-secret');
      const { data: secretRow } = await supabase
        .from('cron_config')
        .select('value')
        .eq('key', 'cron_secret')
        .maybeSingle();
      const expectedSecret = secretRow?.value;
      // Rifiuta il placeholder: forza l'operatore a impostare un secret reale
      const secretOk = !!expectedSecret
        && expectedSecret !== 'CHANGE_ME_GENERATE_A_RANDOM_SECRET'
        && providedSecret === expectedSecret;

      if (!secretOk) {
        return new Response(JSON.stringify({ error: 'Unauthorized' }), {
          status: 401,
          headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        });
      }

      // Authorized cron call: allow optional user_id filter from body
      try {
        const body = await req.json();
        userId = body?.user_id || null;
      } catch {
        // No body — update all assets
      }
    }

    // Each security is quoted in its stored unit. Cash FX remains a separate EUR conversion.
    let query = supabase
      .from('portfolio_assets')
      .select('id, user_id, symbol, type, current_price, currency, updated_at')
      .in('type', ['stock', 'etf', 'crypto'])
      .not('symbol', 'is', null)
      .neq('symbol', '');
    if (userId) query = query.eq('user_id', userId);
    const { data: assets, error: fetchError } = await query;
    if (fetchError) throw new Error('ASSET_READ_FAILED');

    // Complete both database reads before provider calls or any possible price commit.
    let cashQuery = supabase.from('portfolio_assets').select('id,user_id,currency,updated_at')
      .eq('type', 'cash').not('currency', 'is', null).neq('currency', 'EUR').is('sold_at', null);
    if (userId) cashQuery = cashQuery.eq('user_id', userId);
    const { data: cashAssets, error: cashReadError } = await cashQuery;
    if (cashReadError) throw new Error('CASH_READ_FAILED');

    const updates: { asset: Asset; price: string }[] = [];
    const errors: { asset_id?: string; symbol: string; error: string }[] = [];
    for (const asset of (assets ?? []) as Asset[]) {
      try {
        // In particular, a USD crypto holding must not receive the provider's EUR price.
        const currency = assetCurrency(asset.currency);
        if (asset.type === 'crypto' && currency !== 'EUR')
          throw new QuoteFailure('QUOTE_CURRENCY_MISMATCH');
        const quote = asset.type === 'crypto'
          ? await fetchCryptoQuote(asset.symbol) : await fetchStockQuote(asset.symbol);
        updates.push({ asset, price: priceForAsset(asset, quote) });
      } catch (error) {
        errors.push({ asset_id: asset.id, symbol: asset.symbol,
          error: error instanceof QuoteFailure ? error.code : 'PRICE_LOOKUP_FAILED' });
      }
      await new Promise(resolve => setTimeout(resolve, 250));
    }

    let updatedCount = 0;
    const today = new Date().toISOString().split('T')[0];
    for (const update of updates) {
      const asset = update.asset;
      // A concurrent edit of symbol, currency or row version must not change the unit after lookup.
      let write = supabase.from('portfolio_assets')
        .update({ current_price: update.price, updated_at: new Date().toISOString() })
        .eq('id', asset.id).eq('user_id', asset.user_id).eq('symbol', asset.symbol)
        .eq('type', asset.type).eq('updated_at', asset.updated_at);
      write = asset.currency === null ? write.is('currency', null) : write.eq('currency', asset.currency);
      const { data: written, error: updateError } = await write
        .select('id,user_id,current_price,currency,symbol,type').maybeSingle();
      let confirmed = false;
      try {
        confirmed = !updateError && written?.id === asset.id && written?.user_id === asset.user_id &&
          written?.symbol === asset.symbol && written?.type === asset.type &&
          assetCurrency(written?.currency) === assetCurrency(asset.currency) &&
          typeof written?.current_price === 'number' && roundedAssetPrice(written.current_price) === update.price;
      } catch { confirmed = false; }
      if (!confirmed) {
        errors.push({ asset_id: asset.id, symbol: asset.symbol, error: 'ASSET_WRITE_NOT_CONFIRMED' });
        continue;
      }
      updatedCount++;
      // NUMERIC(15,2) and history receive the same pre-rounded decimal string, not raw provider units.
      const { error: historyError } = await supabase.from('asset_price_history').upsert({
        asset_id: asset.id, user_id: asset.user_id, price: update.price, recorded_at: today,
      }, { onConflict: 'asset_id,recorded_at' });
      if (historyError) errors.push({ asset_id: asset.id, symbol: asset.symbol, error: 'PRICE_HISTORY_WRITE_FAILED' });
    }

    // Cash is included even if there are no stock/ETF/crypto symbols. No security currency is rewritten.
    const rates = new Map<string, string>();
    const failedRates = new Map<string, string>();
    for (const cash of cashAssets ?? []) {
      const currency = cash.currency as string;
      if (!rates.has(currency) && !failedRates.has(currency)) {
        try { rates.set(currency, await fetchCashRate(currency)); }
        catch (error) { failedRates.set(currency, error instanceof QuoteFailure ? error.code : 'FX_LOOKUP_FAILED'); }
      }
      const rate = rates.get(currency);
      if (rate === undefined) {
        errors.push({ asset_id: cash.id, symbol: `${currency}/EUR`, error: failedRates.get(currency) ?? 'FX_LOOKUP_FAILED' });
        continue;
      }
      const { data: written, error: fxError } = await supabase.from('portfolio_assets')
        .update({ current_price: rate, updated_at: new Date().toISOString() })
        .eq('id', cash.id).eq('user_id', cash.user_id).eq('type', 'cash').eq('currency', currency)
        .eq('updated_at', cash.updated_at).is('sold_at', null).select('id,user_id,current_price').maybeSingle();
      let confirmed = false;
      try {
        confirmed = !fxError && written?.id === cash.id && written?.user_id === cash.user_id &&
          typeof written?.current_price === 'number' && roundedAssetPrice(written.current_price) === rate;
      } catch { confirmed = false; }
      if (confirmed) updatedCount++;
      else errors.push({ asset_id: cash.id, symbol: `${currency}/EUR`, error: 'FX_WRITE_NOT_CONFIRMED' });
    }

    const checked = (assets ?? []).length;
    const { error: logError } = await supabase.from('price_update_logs').insert({
      user_id: userId, assets_checked: checked, assets_updated: updatedCount, errors,
    });
    if (logError) throw new Error('PRICE_LOG_WRITE_FAILED');
    const noAssets = checked === 0 && (cashAssets ?? []).length === 0;
    return new Response(JSON.stringify({
      message: noAssets ? 'No assets to update' : 'Price update complete',
      checked, updated: updatedCount, errors, quote_contract_version: QUOTE_CONTRACT_VERSION,
    }), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });

  } catch (error) {
    console.error('Error in update-prices function');
    return new Response(
      JSON.stringify({ error: 'Internal error' }),
      { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  }
});
