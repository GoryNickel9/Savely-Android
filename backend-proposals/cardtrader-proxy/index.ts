// @ts-ignore - Deno is available in Supabase Edge Functions
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';
import { fetchPriceDto, priceRequest, PriceFailure } from './quote-contract.ts';

const CT_API_URL = 'https://api.cardtrader.com/api/v2';

// Module-level cache for expansions (persists across warm invocations).
// Con TTL: senza, i set rilasciati dopo il boot dell'isolate resterebbero
// "Unknown Set" fino al riciclo dell'isolate.
let expansionsCache: Record<number, { id: number; name: string }> = {};
let expansionsCacheTime = 0;
let isFetchingExpansions = false;
const EXPANSIONS_TTL_MS = 6 * 60 * 60 * 1000; // 6 ore

// @ts-ignore
const ALLOWED_ORIGINS = (Deno.env.get('ALLOWED_ORIGIN') || '')
  .split(',')
  .map((o) => o.trim())
  .filter(Boolean);

function getCorsHeaders(requestOrigin: string | null): Record<string, string> {
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

async function getExpansions(apiKey: string): Promise<Record<number, { id: number; name: string }>> {
  if (Object.keys(expansionsCache).length > 0 && Date.now() - expansionsCacheTime < EXPANSIONS_TTL_MS) {
    return expansionsCache;
  }
  if (isFetchingExpansions) {
    while (isFetchingExpansions) {
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    return expansionsCache;
  }

  isFetchingExpansions = true;
  try {
    const res = await fetch(`${CT_API_URL}/expansions`, {
      headers: { Authorization: `Bearer ${apiKey}` },
    });
    if (res.ok) {
      const expansions: any[] = await res.json();
      if (expansions.length > 0) {
        const fresh: Record<number, { id: number; name: string }> = {};
        expansions.forEach((exp) => {
          fresh[exp.id] = { id: exp.id, name: exp.name };
        });
        expansionsCache = fresh;
        expansionsCacheTime = Date.now();
      }
    }
    return expansionsCache;
  } catch (err) {
    console.error('Error fetching expansions:', err);
    return {};
  } finally {
    isFetchingExpansions = false;
  }
}

// CardTrader /blueprints è paginato (50 per pagina, ordinate per id crescente):
// senza seguire le pagine le carte dei set più nuovi restano fuori dai risultati.
const BLUEPRINTS_PAGE_SIZE = 50;
const BLUEPRINTS_MAX_PAGES = 10; // max ~500 blueprint per ricerca

async function searchBlueprints(name: string, apiKey: string): Promise<any[]> {
  const all: any[] = [];
  for (let page = 1; page <= BLUEPRINTS_MAX_PAGES; page++) {
    const res = await fetch(
      `${CT_API_URL}/blueprints?name=${encodeURIComponent(name)}&page=${page}`,
      { headers: { Authorization: `Bearer ${apiKey}` } },
    );
    if (!res.ok) {
      if (all.length > 0) break; // meglio restituire le pagine già ricevute
      const text = await res.text();
      console.error(`CardTrader blueprints error ${res.status}: ${text}`);
      throw new Error(`CardTrader error: ${res.statusText}`);
    }
    const batch: any[] = await res.json();
    all.push(...batch);
    if (batch.length < BLUEPRINTS_PAGE_SIZE) break;
  }
  return all;
}

// @ts-ignore
Deno.serve(async (req: Request) => {
  const corsHeaders = getCorsHeaders(req.headers.get('origin'));

  if (req.method === 'OPTIONS') {
    return new Response('ok', { status: 200, headers: corsHeaders });
  }

  // SEC-2: richiede un utente autenticato (evita abuso come proxy aperto)
  // @ts-ignore
  const supabaseUrl = Deno.env.get('SUPABASE_URL');
  // @ts-ignore
  const supabaseAnonKey = Deno.env.get('SUPABASE_ANON_KEY');
  const authHeader = req.headers.get('Authorization');
  if (!supabaseUrl || !supabaseAnonKey || !authHeader) {
    return new Response(
      JSON.stringify({ error: 'Unauthorized' }),
      { status: 401, headers: { ...corsHeaders, 'Content-Type': 'application/json' } },
    );
  }
  try {
    const userClient = createClient(supabaseUrl, supabaseAnonKey, {
      global: { headers: { Authorization: authHeader } },
    });
    const { data: { user } } = await userClient.auth.getUser();
    if (!user) {
      return new Response(
        JSON.stringify({ error: 'Unauthorized' }),
        { status: 401, headers: { ...corsHeaders, 'Content-Type': 'application/json' } },
      );
    }
  } catch (err) {
    console.error('Auth check failed:', err);
    return new Response(
      JSON.stringify({ error: 'Unauthorized' }),
      { status: 401, headers: { ...corsHeaders, 'Content-Type': 'application/json' } },
    );
  }

  // @ts-ignore
  const apiKey = Deno.env.get('CARDTRADER_API_KEY');
  if (!apiKey) {
    return new Response(
      JSON.stringify({ error: 'CARDTRADER_API_KEY not configured' }),
      { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } },
    );
  }

  const { searchParams } = new URL(req.url);
  const action = searchParams.get('action');

  try {
    // ── SEARCH ──────────────────────────────────────────────────────────────
    if (action === 'search') {
      const name = searchParams.get('name');
      if (!name) {
        return new Response(
          JSON.stringify({ error: 'Missing name parameter' }),
          { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } },
        );
      }

      await getExpansions(apiKey);

      const blueprints: any[] = await searchBlueprints(name, apiKey);

      const cards = blueprints.map((bp) => {
        const expansion = expansionsCache[bp.expansion_id] ?? { name: 'Unknown Set' };
        return {
          id: bp.id.toString(),
          name: bp.name,
          image: bp.image_url ?? (bp.image?.url ? `https://api.cardtrader.com${bp.image.url}` : undefined),
          set: {
            id: expansion.id?.toString(),
            name: expansion.name,
          },
        };
      });

      return new Response(JSON.stringify(cards), {
        status: 200,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      });
    }

    // ── PRICES ──────────────────────────────────────────────────────────────
    if (action === 'prices') {
      const id = searchParams.get('id');
      const conditionParam = searchParams.get('condition'); // optional: app CardCondition value
      if (!id) {
        return new Response(
          JSON.stringify({ error: 'Missing id parameter' }),
          { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } },
        );
      }

      try {
        priceRequest(id, conditionParam);
      } catch {
        return new Response(JSON.stringify({ error: 'Invalid blueprint or condition' }),
          { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
      }
      try {
        const dto = await fetchPriceDto(id, conditionParam, apiKey);
        return new Response(JSON.stringify(dto),
          { status: 200, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
      } catch (error) {
        // Fixed public codes only: never return provider bodies, formatted amounts or credentials.
        const code = error instanceof PriceFailure ? error.code : 'PROVIDER_UNAVAILABLE';
        console.error(`cardtrader-proxy prices: ${code}`);
        return new Response(JSON.stringify({ error: code }),
          { status: 502, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
      }
    }

    return new Response(
      JSON.stringify({ error: 'Invalid action. Use action=search or action=prices' }),
      { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } },
    );
  } catch (err: any) {
    console.error('cardtrader-proxy error:', err);
    return new Response(
      JSON.stringify({ error: err.message ?? 'Internal server error' }),
      { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } },
    );
  }
});
