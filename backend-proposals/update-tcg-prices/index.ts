// @ts-ignore - Supabase Edge Functions don't have complete type definitions
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';
import { eligibleCard, euroPrice, compareAndSwap, PriceFailure, type TcgCard } from './price-contract.ts';

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

const CT_API_URL = 'https://api.cardtrader.com/api/v2';

/**
 * S-3: verifica l'header x-cron-secret contro il valore in cron_config.
 * Le edge function "cron" non sono internal-only: sono HTTP pubblici, quindi
 * richiedono questo shared secret per impedire abuso anonimo.
 * Ritorna true solo se header presente e corrispondente.
 */
async function isCronAuthorized(supabase: any, req: any): Promise<boolean> {
  const provided = req.headers.get('x-cron-secret');
  if (!provided) return false;
  const { data } = await supabase
    .from('cron_config')
    .select('value')
    .eq('key', 'cron_secret')
    .maybeSingle();
  const expected = data?.value;
  // Rifiuta il placeholder: forza l'operatore a impostare un secret reale
  return !!expected
    && expected !== 'CHANGE_ME_GENERATE_A_RANDOM_SECRET'
    && provided === expected;
}

/** Provider JSON is bounded; credentials never follow a redirect. No retries. */
async function fetchCardTraderCtZeroPrice(card: TcgCard, apiKey: string): Promise<string | null> {
  const response = await fetch(
    `${CT_API_URL}/marketplace/products?blueprint_id=${encodeURIComponent(card.card_id!)}`,
    { headers: { Authorization: `Bearer ${apiKey}` }, redirect: 'error', signal: AbortSignal.timeout(10_000) },
  );
  if (!response.ok) { await response.body?.cancel(); throw new PriceFailure('PROVIDER_HTTP_ERROR'); }
  if (!response.body) throw new PriceFailure('PROVIDER_RESPONSE_INVALID');
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
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
    let data: unknown;
    try { data = JSON.parse(text); } catch { throw new PriceFailure('PROVIDER_RESPONSE_INVALID'); }
    return euroPrice(card.card_id!, card.condition, data);
  } finally { reader.releaseLock(); }
}

// @ts-ignore - Deno is available in Edge Functions
Deno.serve(async (req: any) => {
  const corsHeaders = getCorsHeaders(req);
  // Handle CORS preflight
  if (req.method === 'OPTIONS') {
    return new Response('ok', { status: 200, headers: corsHeaders });
  }

  try {
    // @ts-ignore
    const supabaseUrl = Deno.env.get('SUPABASE_URL')!;
    // @ts-ignore
    const supabaseServiceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!;
    // @ts-ignore
    const apiKey = Deno.env.get('CARDTRADER_API_KEY');

    if (!apiKey) {
      return new Response(
        JSON.stringify({ error: 'CARDTRADER_API_KEY not configured' }),
        { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } },
      );
    }

    const supabase = createClient(supabaseUrl, supabaseServiceKey);

    // S-3: richiede il secret cron (endpoint non più richiamabile anonimamente)
    if (!(await isCronAuthorized(supabase, req))) {
      return new Response(
        JSON.stringify({ error: 'Unauthorized' }),
        { status: 401, headers: { ...corsHeaders, 'Content-Type': 'application/json' } },
      );
    }

    // Fetch all cards that have a card_id (blueprint_id) to look up
    const { data: cards, error: fetchError } = await supabase
      .from('tgc_cards')
      .select('id, user_id, category, card_id, current_price, condition, currency, updated_at')
      .not('card_id', 'is', null);

    if (fetchError) {
      console.error('TCG_READ_FAILED');
      return new Response(
        JSON.stringify({ error: 'Internal error' }),
        { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } },
      );
    }

    if (!cards || cards.length === 0) {
      return new Response(
        JSON.stringify({ updated: 0, skipped: 0, failed: 0, message: 'No cards to update' }),
        { status: 200, headers: { ...corsHeaders, 'Content-Type': 'application/json' } },
      );
    }

    let updated = 0;
    let skipped = 0;
    let failed = 0;
    let skippedCurrency = 0;
    let skippedInvalid = 0;
    let skippedConcurrent = 0;

    for (const value of cards) {
      let card: TcgCard;
      try { card = eligibleCard(value); }
      catch (error) {
        skipped++;
        if (error instanceof PriceFailure && error.code === 'ROW_CURRENCY_UNSUPPORTED') skippedCurrency++;
        else skippedInvalid++;
        continue;
      }
      try {
        const price = await fetchCardTraderCtZeroPrice(card, apiKey);
        if (price === null) { failed++; continue; }
        const { data: changed, error: updateError } = await compareAndSwap(supabase
          .from('tgc_cards')
          .update({ current_price: price, updated_at: new Date().toISOString() }), card)
          .select('id').maybeSingle();
        if (updateError) { failed++; console.error('TCG_UPDATE_FAILED'); }
        else if (changed?.id === card.id) updated++;
        else { skipped++; skippedConcurrent++; }
      } catch (error) {
        failed++;
        console.error(error instanceof PriceFailure ? error.code : 'TCG_PROVIDER_OR_UPDATE_FAILED');
      } finally {
        // Preserve existing pacing, including provider failures; no mutation retry.
        await new Promise((resolve) => setTimeout(resolve, 200));
      }
    }

    console.log(`TCG price update complete: ${updated} updated, ${skipped} skipped, ${failed} failed`);

    return new Response(
      JSON.stringify({ updated, skipped, failed, total: cards.length, skippedCurrency, skippedInvalid, skippedConcurrent, quoteContractVersion: 1 }),
      { status: 200, headers: { ...corsHeaders, 'Content-Type': 'application/json' } },
    );
  } catch (error) {
    console.error('TCG_INTERNAL_ERROR');
    return new Response(
      JSON.stringify({ error: 'Internal server error' }),
      { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } },
    );
  }
});
