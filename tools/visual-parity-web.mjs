// Render pinned, unmodified React pages from an isolated git archive. No live backend.
import fs from 'node:fs/promises';
import { existsSync, symlinkSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const reference = path.resolve(process.argv[2] ?? path.join(repository, '../Savely'));
const scopeIndex = process.argv.indexOf('--scope');
const scope = scopeIndex >= 0 ? process.argv[scopeIndex + 1] : 'finance-modules';
if (!['finance-modules', 'content-clean', 'account'].includes(scope)) throw new Error('Unknown capture scope');
const optionValue = option => { const index = process.argv.indexOf(option); return index >= 0 ? process.argv[index + 1] : null; };
const runLabel = optionValue('--run-label');
if (runLabel && !/^[a-z0-9-]+$/.test(runLabel)) throw new Error('Run label must be a safe directory component');
const nativePaired = process.argv.includes('--native-paired') || runLabel === 'native-paired';
if (nativePaired && !runLabel) throw new Error('Paired capture requires an isolated run label');
const baseOutput = path.join(repository, 'artifacts/visual-parity/web');
const scopeOutput = scope === 'finance-modules' ? baseOutput : path.join(baseOutput, scope);
const output = runLabel ? path.join(scopeOutput, runLabel) : scopeOutput;
const source = path.join(baseOutput, 'source');
// These selectors correspond only to MainLayout's Sidebar/BottomNav siblings.
// Screenshot style is transient; viewport/full captures retain the actual shell.
const contentCaptureStyle = 'main ~ nav.fixed.bottom-0, main ~ aside, main ~ div.fixed, #root .min-h-screen.bg-background > nav.fixed.bottom-0, #root .min-h-screen.bg-background > aside, #root .min-h-screen.bg-background > div.fixed.top-4.left-4.z-50 { visibility: hidden !important; }';
const baseline = '1cfcf6297e85d56bfafe3522362ceecef6f6b281';
const revision = execFileSync('git', ['-C', reference, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
if (revision !== baseline) throw new Error('Reference revision differs from pinned baseline');
const datasetPath = path.join(repository, 'parity/fixtures/static-common-dataset.json');
const datasetBytes = await fs.readFile(datasetPath);
const dataset = JSON.parse(datasetBytes);
const digest = value => createHash('sha256').update(value).digest('hex');
await fs.mkdir(output, { recursive: true });
const reuseSource = process.argv.includes('--reuse-source');
if (existsSync(source) && !reuseSource) throw new Error('Isolated source directory already exists; use --reuse-source only to validate and reuse its manifest');
const archivePaths = ['src', 'public', 'index.html', 'package.json', 'package-lock.json', 'vite.config.ts', 'postcss.config.js', 'tsconfig.json', 'tsconfig.app.json', 'tsconfig.node.json'];
const archive = execFileSync('git', ['-C', reference, 'archive', 'HEAD', ...archivePaths], { maxBuffer: 100 * 1024 * 1024 });
const archivePath = path.join(baseOutput, 'pinned-source.tar');
if (!existsSync(source)) {
  await fs.mkdir(source); await fs.writeFile(archivePath, archive);
  execFileSync('tar', ['-xf', archivePath, '-C', source]);
} else {
  const previous = JSON.parse(await fs.readFile(path.join(baseOutput, 'source-manifest.json')));
  if (previous.sourceRevision !== revision || previous.sourceArchiveSha256 !== digest(archive)) throw new Error('Source manifest differs');
  for (const item of previous.files) if (digest(await fs.readFile(path.join(source, item.path))) !== item.sha256) throw new Error(`Isolated source changed: ${item.path}`);
}
const files = [];
async function inventory(directory) {
  for (const item of await fs.readdir(directory, { withFileTypes: true })) {
    const absolute = path.join(directory, item.name);
    if (item.name === 'node_modules') continue;
    if (item.isDirectory()) await inventory(absolute);
    else files.push({ path: path.relative(source, absolute).replaceAll('\\', '/'), sha256: digest(await fs.readFile(absolute)) });
  }
}
await inventory(source);
// Vite's cache is explicitly outside this junction, so the reference dependencies stay readonly.
if (!existsSync(path.join(source, 'node_modules'))) symlinkSync(path.join(reference, 'node_modules'), path.join(source, 'node_modules'), 'junction');
const requireReference = createRequire(path.join(reference, 'package.json'));
const { chromium } = requireReference('@playwright/test');
const playwrightVersion = requireReference('@playwright/test/package.json').version;
const dependencyLock = JSON.parse(await fs.readFile(path.join(source, 'package-lock.json')));
const dependencies = await Promise.all(['react', 'react-dom', 'vite', '@tailwindcss/postcss', 'tailwindcss', '@vitejs/plugin-react-swc', '@tailwindcss/typography', 'tailwindcss-animate', '@playwright/test'].map(async name => {
  const actual = JSON.parse(await fs.readFile(path.join(reference, 'node_modules', name, 'package.json'))).version;
  const locked = dependencyLock.packages[`node_modules/${name}`]?.version ?? null;
  return { name, actual, locked, matchesPinnedLock: actual === locked };
}));
if (dependencies.some(dependency => !dependency.matchesPinnedLock)) throw new Error('Installed dependencies differ from pinned lock');
const browserExecutable = path.join(process.env.LOCALAPPDATA, 'ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-win64/chrome-headless-shell.exe');
if (!existsSync(browserExecutable)) throw new Error('Existing local Chromium executable not found');
const { createServer } = await import(pathToFileURL(requireReference.resolve('vite')));
const { default: reactPlugin } = await import(pathToFileURL(requireReference.resolve('@vitejs/plugin-react-swc')));
const { default: tailwindPostcss } = await import(pathToFileURL(requireReference.resolve('@tailwindcss/postcss')));
const port = 4177;
const origin = `http://127.0.0.1:${port}`;
process.env.VITE_SUPABASE_URL = `${origin}/fixture-supabase`;
process.env.VITE_SUPABASE_ANON_KEY = 'synthetic-public-key-visual-fixture-only';
process.env.VITE_SITE_URL = origin;
const fonts = {
  'inter.ttf': path.join(repository, 'app/src/main/res/font/inter.ttf'),
  'space_grotesk.ttf': path.join(repository, 'app/src/main/res/font/space_grotesk.ttf'),
};
const manifest = {
  schemaVersion: 1, scope, nativePairedInputs: nativePaired, sourceDirectory: path.relative(repository, source), configLoader: 'none: configFile=false, equivalent pinned config supplied in memory',
  configurationAdaptation: 'Pinned vite.config.ts uses __dirname and fails under runner. Supply identical react-swc plugin, @ alias and Tailwind PostCSS in memory; host/port/cache override only. No bundled .vite-temp config created.',
  contentCaptureStyle: scope === 'finance-modules' ? null : contentCaptureStyle,
  contentCaptureStyleScope: 'Only contentFile; no spacing/font/content changes; viewportFile and fullFile retain original shell',
  sourceRevision: revision, sourceArchiveSha256: digest(archive), files, playwrightVersion, browserExecutable, dependencies,
  datasetPath: path.relative(repository, datasetPath), datasetSha256: digest(datasetBytes), clock: dataset.clock,
  sourceEdits: [], sourceEnvironmentFilesCopied: false, dependencySource: 'existing reference node_modules junction',
  transport: 'Playwright interception, every external request aborted; all Supabase responses synthetic',
  fonts: await Promise.all(Object.entries(fonts).map(async ([name, location]) => ({ name, source: path.relative(repository, location), sha256: digest(await fs.readFile(location)) }))),
  adaptations: ['Tailwind4 discovery forced with in-memory @source pointing to pinned src, since artifacts are gitignored; stylesheet on disk unchanged', 'Decimal input strings converted to JSON numbers for PostgREST wire shape', 'Missing record names/created_at/updated_at supplied as explicit synthetic fixture metadata', 'Category joins added from dataset category_id', 'Visual fixture permissions allow all displayed modules, admin=false; this is not an auth qualification', 'Cookie notice acknowledged locally to leave navigation visible', 'Font2 profile uses html font-size:200%, not browser zoom or Android fontScale equivalence', 'Google Fonts stylesheet fulfilled using the existing licensed local font files, no font download', 'No unsupported endpoint silently returns an empty collection', 'Finance routes use finance.tcgCards (empty), /tcg uses the separate mixed-currency case', 'Manual price update logs and ignored-recurring/goal/couple auxiliary tables explicitly empty because absent from dataset', 'Barista loads its separate baseline case; Standard loads Standard baseline'],
};
await fs.writeFile(path.join(output, 'source-manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
const owner = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const permissions = { admin: false, poker: true, fumo: true, fire: true, tcg: true, libreria: true, couple_expenses: false };
const clock = new Date(dataset.clock).getTime();
const caseInput = id => {
  const result = dataset.modules.cases.find(value => value.id === id);
  if (!result) throw new Error(`Missing dataset case ${id}`);
  return result.input;
};
const numericKeys = new Set(['amount', 'exchange_rate_eur', 'quantity', 'purchase_price', 'current_price', 'sold_price', 'net_worth', 'price', 'hours_played', 'profit_loss', 'net_won_ev', 'hourly_rate', 'hourly_rate_ev', 'rake_generated', 'rakeback_received', 'costo', 'millilitri', 'giorni_durata', 'millilitri_al_giorno', 'euro_al_giorno', 'costo_mensile', 'reselling_value']);
function wire(row) {
  return Object.fromEntries(Object.entries(row).map(([key, value]) => [key, numericKeys.has(key) && value !== null ? Number(value) : value]));
}
const own = (rows, label) => rows.map((row, index) => wire({ id: `${label}-${index}`, user_id: owner, created_at: dataset.clock, updated_at: dataset.clock, ...row }));
const categories = own(dataset.finance.categories, 'category');
const joinCategory = rows => own(rows, 'row').map(row => ({ ...row, category: categories.find(category => category.id === row.category_id) ?? null }));
const tcgRows = caseInput('tcg-mixed-currency').rows;
const libraryRows = caseInput('library-null-prices').rows;
const hourlyRows = caseInput('poker-hourly-weighted').rows.map((row, index) => ({
  id: `hourly-${index}`, date: nativePaired && index === 0 ? '2026-06-01' : '2026-05-01', hours_played: row.minutes / 60,
  profit_loss: row.netWon, net_won_ev: row.netWonEv,
  hourly_rate: row.netWon / (row.minutes / 60), hourly_rate_ev: row.netWonEv / (row.minutes / 60),
}));
const fumoInput = caseInput(nativePaired ? 'fumo-legacy-liquid' : 'fumo-liquid-cycle');
const legacyLiquid = { ...fumoInput, id: 'liquid-legacy', user_id: dataset.account.actors.B.id };
const tables = {
  categories, transactions: joinCategory(dataset.finance.transactions), budgets: joinCategory(dataset.finance.budgets),
  portfolio_assets: own(dataset.finance.assets, 'asset'), asset_price_history: own(dataset.finance.priceHistory, 'price'),
  net_worth_snapshots: own(dataset.finance.snapshots, 'snapshot'), recurring_expenses: own(dataset.finance.recurring, 'recurring'),
  savings_goals: [], ignored_recurring_suggestions: [], couple_connections: [], shared_expenses: [], couple_budgets: [], manual_price_updates: [], price_update_logs: [],
  poker_hourly_earnings: own(hourlyRows, 'hourly'), poker_rakeback: own(caseInput('poker-rakeback-years').rows, 'rakeback'),
  poker_next_cut: own([{ id: 'cut', deal: 0.55, amount: nativePaired ? 0 : 2000, profit_loss: nativePaired ? 0 : 1500 }], 'cut'), poker_manual_expenses: [],
  liquido_sigaretta: own(nativePaired ? [legacyLiquid] : [{ id: 'liquid', costo: fumoInput.costo, millilitri: fumoInput.millilitri, data_arrivo: fumoInput.data_arrivo,
    data_finito: fumoInput.finishSequence[0], giorni_durata: 10, millilitri_al_giorno: 2.5, euro_al_giorno: 1.25, costo_mensile: 37.5 }], 'liquid'),
  cbd: [], thc: [],
  tgc_cards: own(tcgRows.map(row => ({ name: `${nativePaired ? 'Dataset' : 'Fixture'} ${row.category}`, condition: 'near_mint', language: 'EN', set_code: 'Fixture set', card_id: null, image_url: null, purchase_date: nativePaired ? '2026-06-15' : '2026-06-01', ...row })), 'tcg'),
  library_items: own(libraryRows.map(row => ({ title: `${nativePaired ? 'Dataset' : 'Fixture'} ${row.category}`,
    ...(nativePaired ? { user_id: dataset.account.actors.B.id, author: null, publisher: null, year: null } : { author: 'Autore fixture', publisher: 'Editore fixture', year: 2026 }),
    api_id: null, cover_image: null, notes: null, ...row })), 'library'),
};
const actors = dataset.account.actors;
function nameUuid(name) {
  const bytes = createHash('md5').update(name, 'utf8').digest();
  bytes[6] = (bytes[6] & 0x0f) | 0x30; bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
const accountId = ref => nameUuid(`common-account-${ref}`);
const pairedSecurity = {
  aal: 'aal2', sessionIds: Object.fromEntries(Object.keys(actors).map(alias => [alias, nameUuid(`common-visual-${alias}`)])),
  factors: [{ id: accountId('verified-factor'), factor_type: 'totp', status: 'verified' },
    { id: accountId('pending-factor'), factor_type: 'totp', status: 'unverified' }],
  sessions: [{ id: accountId('activity-current'), session_id: nameUuid('common-visual-A'), user_agent: 'Synthetic Android A', created_at: dataset.clock },
    { id: accountId('activity-other'), session_id: accountId('other-session'), user_agent: 'Synthetic Browser A', created_at: '2026-06-14T12:00:00+02:00' }],
};
const accountProfiles = Object.entries(actors).map(([key, actor]) => ({
  id: `profile-${key}`, user_id: actor.id, full_name: actor.name, couple_code: actor.coupleCode ?? null,
  email: nativePaired ? `visual-${key.toLowerCase()}@dataset.invalid` : `${key.toLowerCase()}-visual@example.invalid`, default_currency: 'EUR', permissions: actor.permissions,
  created_at: dataset.clock, updated_at: dataset.clock,
}));
const resources = dataset.account.resources;
const connectionId = 'CONNECTION-A-B';
const sharedRows = ['SHARE-EQUAL', 'SHARE-CUSTOM'].map(id => {
  const resource = resources[id]; const tx = resources[resource.transaction];
  const equal = resource.mode === 'equal';
  // Explicit SQL-view fixture, not an execution of deployed SQL: equal gives 5.01 to both actors.
  const creatorShare = equal ? 5.01 : 4.99; const partnerShare = equal ? 5.01 : 2.01;
  return { id, connection_id: connectionId, original_tx_id: resource.transaction, created_by: actors[resource.creator].id,
    couple_category_name: tx.category, split_percentage: 50, split_mode: resource.mode,
    partner_amount: equal ? null : Number(resource.partnerAmount), creator_share_amount: creatorShare,
    partner_share_amount: partnerShare, total_amount: Number(tx.amount), my_share_amount: creatorShare,
    currency: tx.currency, exchange_rate_eur: 1, description: `Dataset ${id}`, date: tx.date,
    tx_deleted_at: null, created_at: dataset.clock, updated_at: dataset.clock };
});
const accountTables = {
  ...tables, couple_connection_requests: [],
  couple_connections: [{ id: connectionId, user_a: actors.A.id, user_b: actors.B.id, revoked_at: null, created_at: dataset.clock, updated_at: dataset.clock }],
  shared_expenses_view: sharedRows,
  couple_budgets: [{ id: 'BUDGET-EUR', connection_id: connectionId, couple_category_name: resources['BUDGET-EUR'].category,
    currency: resources['BUDGET-EUR'].currency, amount: Number(resources['BUDGET-EUR'].amount), month: 1, year: 2000,
    created_by: actors.A.id, created_at: dataset.clock, updated_at: dataset.clock }],
};
if (nativePaired) manifest.adaptations.push('Paired source: CommonModuleVisualTest fumo-legacy-liquid raw saved derived fields, ownerB; Poker dates June1/May1; TCG purchaseJune15 and Dataset names',
  'Paired Security source: CommonAccountVisualTest and CommonVisualFixture CommonVisualBackend; exact verified/unverified factor UUIDs, aal2, canonical session UUIDs, raw synthetic user agents and dates',
  'Paired Admin get_registrations_enabled=false; Auth public registration fixture remains true and is not a paired native signup-state qualification');
if (nativePaired) manifest.nativeFixtureSources = await Promise.all([
  'app/src/androidTest/java/cc/savely/android/modules/CommonModuleVisualTest.kt',
  'app/src/androidTest/java/cc/savely/android/ui/CommonAccountVisualTest.kt',
  'app/src/androidTest/java/cc/savely/android/visual/CommonVisualFixture.kt'
].map(async location => ({ path: location, sha256: digest(await fs.readFile(path.join(repository, location))) })));
if (scope === 'account') {
  manifest.adaptations.push('Account scope uses dataset.account actors A/B/Admin and exact permission objects; /admin uses Admin, /auth has no session',
    nativePaired ? 'Settings uses paired two factors and AAL2, same canonical session fixture; no MFA setup or revoke requested' : 'Settings security factors are empty, AAL1; two synthetic session rows (current and other), no MFA setup or revoke requested',
    'Couple shared_expenses_view is an explicit SQL-view wire fixture: equal EUR10.01 gives5.01/5.01, custom USD7 gives4.99/2.01; FX1 is fixture metadata, not a lookup',
    'Only read-only POST get_registrations_enabled/get_active_sessions and /api/admin action=get-users are fulfilled; every other mutation denied');
  await fs.writeFile(path.join(output, 'source-manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
}
await fs.writeFile(path.join(output, 'source-manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
await fs.writeFile(path.join(output, 'wire-fixture.json'), JSON.stringify({ owner, permissions, tables }, null, 2) + '\n');
if (scope === 'account') await fs.writeFile(path.join(output, 'wire-fixture.json'), JSON.stringify({ actors, profiles: accountProfiles, tables: accountTables,
  ...(nativePaired ? { security: pairedSecurity, adminRegistrationsEnabled: false } : {}) }, null, 2) + '\n');
const results = [];
const network = [];
const errors = [];
const profiles = [
  { id: 'it-light-360', language: 'it', theme: 'light', width: 360, height: 800, font: 1 },
  { id: 'en-dark-320-font2', language: 'en', theme: 'dark', width: 320, height: 800, font: 2 },
];
const routes = [
  ['dashboard', '/'], ['transactions', '/transactions'], ['budget', '/budget'], ['portfolio', '/portfolio'],
  ['net-worth', '/net-worth'], ['insights', '/insights'], ['fire-standard', '/fire/standard'], ['fire-barista', '/fire/barista'],
  ['poker-hourly', '/poker/hourly-earnings'], ['poker-rakeback', '/poker/rakeback'], ['fumo-liquid', '/fumo/liquido-sigaretta'],
  ['tcg', '/tcg'], ['library', '/libreria'],
  ...(nativePaired && optionValue('--scenario') === 'charts-income-expense' ? [['charts-income-expense', '/charts/income-expense', 'between-common-dates']] : []),
];
const accountRoutes = [['auth-login', '/auth'], ['auth-signup', '/auth', 'signup'], ['settings', '/settings'],
  ['security', '/settings', 'security'], ['couple-b', '/couple-budget', 'actor-B'],
  ['admin-permissions', '/admin'], ['admin-options', '/admin', 'options']];
const server = await createServer({ root: source, configFile: false, cacheDir: path.join(output, 'vite-cache'),
  resolve: { alias: { '@': path.join(source, 'src') } }, css: { postcss: { plugins: [tailwindPostcss()] } },
  plugins: [reactPlugin(), { name: 'visual-fixture-tailwind-discovery', enforce: 'pre', transform(code, id) {
    if (id.split('?')[0] === path.join(source, 'src/index.css').replaceAll('\\', '/'))
      return code + `\n@source "${path.join(source, 'src').replaceAll('\\', '/')}";\n`;
  } }], server: { host: '127.0.0.1', port, strictPort: true }, clearScreen: false });
let browser;
try {
  await server.listen();
  browser = await chromium.launch({ headless: true, executablePath: browserExecutable });
  for (const profile of profiles) {
    if (optionValue('--profile') && optionValue('--profile') !== profile.id) continue;
    let activeRoute = '/';
    let activeActor = actors.A;
    let activeVariant = '';
    const context = await browser.newContext({ viewport: { width: profile.width, height: profile.height }, locale: profile.language === 'it' ? 'it-IT' : 'en-GB', timezoneId: dataset.timezone, deviceScaleFactor: 1, serviceWorkers: 'block', reducedMotion: 'reduce' });
    await context.routeWebSocket('**', socket => socket.close());
    await context.route('**/*', async route => {
      const request = route.request();
      const url = new URL(request.url());
      const record = { profile: profile.id, method: request.method(), origin: url.origin, path: url.pathname, status: null };
      if (url.hostname === 'fonts.googleapis.com') {
        record.status = 'fulfilled-local-font-css'; network.push(record);
        await route.fulfill({ contentType: 'text/css', body: `@font-face{font-family:Inter;font-style:normal;font-weight:100 900;font-display:swap;src:url('${origin}/fixture-font/inter.ttf') format('truetype')}@font-face{font-family:'Space Grotesk';font-style:normal;font-weight:100 900;font-display:swap;src:url('${origin}/fixture-font/space_grotesk.ttf') format('truetype')}` }); return;
      }
      if (url.origin !== origin) { record.status = 'aborted-external'; network.push(record); await route.abort('blockedbyclient'); return; }
      if (url.pathname.startsWith('/fixture-font/')) {
        const file = fonts[path.basename(url.pathname)];
        if (!file) throw new Error('Unknown local font');
        await route.fulfill({ contentType: 'font/ttf', body: await fs.readFile(file) }); return;
      }
      if (url.pathname === '/api/admin') {
        network.push(record);
        const body = request.postDataJSON();
        const allowed = scope === 'account' && activeActor.id === actors.Admin.id && request.method() === 'POST' &&
          body?.action === 'get-users' && Object.keys(body).length === 1;
        record.status = allowed ? 'fulfilled-read-only-post' : 'denied-mutation';
        await route.fulfill({ status: allowed ? 200 : 403, contentType: 'application/json', body: JSON.stringify(allowed ?
          { users: accountProfiles.map(value => ({ ...value, last_sign_in_at: dataset.clock })) } : { message: 'Forbidden fixture mutation' }) }); return;
      }
      if (!url.pathname.startsWith('/fixture-supabase/')) { await route.continue(); return; }
      network.push(record);
      const respond = async (body, status = 200) => { record.status = status; await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body), headers: { 'content-range': `0-${Array.isArray(body) ? body.length - 1 : 0}/*` } }); };
      if (scope === 'account' && request.method() === 'POST' && url.pathname.endsWith('/rpc/get_registrations_enabled') && request.postData() === '{}') {
        await respond(!(nativePaired && activeActor.id === actors.Admin.id)); record.status = 'fulfilled-read-only-post'; return;
      }
      if (scope === 'account' && request.method() === 'POST' && url.pathname.endsWith('/rpc/get_active_sessions') && request.postData() === '{}') {
        await respond(nativePaired ? pairedSecurity.sessions : [{ id: 'fixture-current-session-row', session_id: '11111111-1111-4111-8111-111111111111', created_at: dataset.clock,
          user_agent: request.headers()['user-agent'], ip: null }, { id: 'fixture-other-session-row', session_id: '22222222-2222-4222-8222-222222222222',
          created_at: dataset.clock, user_agent: 'Mozilla/5.0 (Android 15; Fixture phone) Chrome/130.0.0.0 Mobile Safari/537.36', ip: null }]);
        record.status = 'fulfilled-read-only-post'; return;
      }
      if (url.pathname.endsWith('/auth/v1/user') && request.method() === 'GET') { await respond({ id: scope === 'account' || nativePaired ? activeActor.id : owner,
        email: nativePaired ? `visual-${Object.keys(actors).find(key => actors[key] === activeActor).toLowerCase()}@dataset.invalid` : scope === 'account' ? `${Object.keys(actors).find(key => actors[key] === activeActor).toLowerCase()}-visual@example.invalid` : 'visual-fixture@example.invalid',
        aud: 'authenticated', role: 'authenticated', app_metadata: {}, user_metadata: { full_name: activeActor.name }, factors: nativePaired ? pairedSecurity.factors : [], created_at: dataset.clock }); return; }
      if (request.method() !== 'GET' && request.method() !== 'HEAD') { record.status = 'denied-mutation'; await respond({ message: 'Visual harness forbids writes' }, 403); return; }
      const table = url.pathname.split('/').at(-1);
      if (table === 'profiles') {
        if (scope === 'account') {
          const rows = accountProfiles.map(value => ({ ...value, language: profile.language }));
          const ownProfile = rows.find(value => value.user_id === activeActor.id);
          await respond(url.searchParams.has('user_id') ? ownProfile : rows); return;
        }
        await respond({ id: 'visual-profile', user_id: nativePaired ? activeActor.id : owner, full_name: nativePaired ? activeActor.name : 'Fixture', default_currency: 'EUR', language: profile.language, permissions }); return;
      }
      const fixtureTables = scope === 'account' ? accountTables : tables;
      if (!(table in fixtureTables)) { errors.push({ profile: profile.id, kind: 'unsupported-endpoint', path: url.pathname }); await respond({ message: 'Unsupported fixture endpoint' }, 501); return; }
      let rows = [...(table === 'tgc_cards' && !activeRoute.startsWith('/tcg') ? own(dataset.finance.tcgCards, 'finance-tcg') : fixtureTables[table])];
      if (scope === 'account' && table === 'shared_expenses_view' && activeActor.id === actors.B.id)
        rows = rows.map(row => ({ ...row, my_share_amount: row.partner_share_amount }));
      for (const [key, filter] of url.searchParams) {
        if (filter.startsWith('eq.')) rows = rows.filter(row => String(row[key]) === filter.slice(3));
        if (filter === 'is.null') rows = rows.filter(row => row[key] == null);
      }
      const order = url.searchParams.get('order');
      if (order) { const [key, direction] = order.split('.'); rows.sort((a, b) => String(a[key] ?? '').localeCompare(String(b[key] ?? '')) * (direction === 'desc' ? -1 : 1)); }
      const offset = Number(url.searchParams.get('offset') ?? 0);
      rows = rows.slice(offset, url.searchParams.has('limit') ? offset + Number(url.searchParams.get('limit')) : undefined);
      await respond(request.headers().accept?.includes('application/vnd.pgrst.object+json') ? rows[0] ?? null : rows);
    });
    await context.addInitScript(({ origin, owner, permissions, profile, clock, fireStandard, fireBarista, scope, actors, nativePaired, pairedSecurity }) => {
      if (scope === 'account') {
        const actor = location.pathname === '/admin' ? actors.Admin : new URL(location.href).searchParams.get('fixtureActor') === 'B' ? actors.B : actors.A;
        owner = actor.id; permissions = actor.permissions;
      }
      if (nativePaired && scope !== 'account') owner = location.pathname.startsWith('/fumo') || location.pathname.startsWith('/libreria') ? actors.B.id : actors.A.id;
      const NativeDate = Date;
      globalThis.Date = class extends NativeDate { constructor(...args) { super(...(args.length ? args : [clock])); } static now() { return clock; } };
      const base64 = value => btoa(JSON.stringify(value)).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
      const actorAlias = Object.keys(actors).find(key => actors[key].id === owner);
      const token = `${base64({ alg: 'HS256', typ: 'JWT' })}.${base64({ sub: owner, aud: 'authenticated', role: 'authenticated', exp: 4102444800, iat: clock / 1000,
        aal: nativePaired ? pairedSecurity.aal : 'aal1', session_id: nativePaired ? pairedSecurity.sessionIds[actorAlias] : '11111111-1111-4111-8111-111111111111' })}.synthetic-signature`;
      localStorage.setItem('sb-127-auth-token', JSON.stringify({ access_token: token, refresh_token: 'synthetic-local-only', token_type: 'bearer', expires_at: 4102444800, expires_in: 3600, user: { id: owner, email: 'visual-fixture@example.invalid', aud: 'authenticated', role: 'authenticated', app_metadata: {}, user_metadata: {} } }));
      if (scope === 'account' || nativePaired) {
        if (location.pathname === '/auth') localStorage.removeItem('sb-127-auth-token');
        else {
          const session = JSON.parse(localStorage.getItem('sb-127-auth-token'));
          const [actorKey, actor] = Object.entries(actors).find(([, value]) => value.id === owner);
          session.user.email = nativePaired ? `visual-${actorKey.toLowerCase()}@dataset.invalid` : `${actorKey.toLowerCase()}-visual@example.invalid`;
          session.user.user_metadata = { full_name: actor.name };
          session.user.factors = nativePaired ? pairedSecurity.factors : [];
          localStorage.setItem('sb-127-auth-token', JSON.stringify(session));
        }
      }
      localStorage.setItem('savely-language', profile.language); localStorage.setItem('savely-theme', profile.theme);
      localStorage.setItem('savely_permissions', JSON.stringify({ userId: owner, permissions }));
      localStorage.setItem('savely_cookie_consent', JSON.stringify({ value: 'essential', ts: clock }));
      localStorage.setItem('fire-calc-params', JSON.stringify(location.pathname === '/fire/barista' ? fireBarista : fireStandard));
    }, { origin, owner, permissions, profile, clock, scope, actors, nativePaired, pairedSecurity,
      fireStandard: { ...caseInput('fire-standard-baseline'), partTimeIncome: 12000 }, fireBarista: caseInput('fire-barista-baseline') });
    const page = await context.newPage();
    page.on('pageerror', error => errors.push({ profile: profile.id, route: activeRoute, kind: 'pageerror', message: error.message, stack: error.stack }));
    const subset = scope === 'account' ? accountRoutes : profile.font === 1 ? routes : routes.filter(([name]) =>
      ['dashboard', 'transactions', 'budget', 'fire-standard', 'library', ...(scope === 'content-clean' ? ['portfolio', 'net-worth', 'insights'] : []), ...(nativePaired ? ['fumo-liquid', 'tcg'] : [])].includes(name));
    for (const [name, pathname, variant = ''] of subset) {
      if (optionValue('--scenario') && optionValue('--scenario') !== name) continue;
      const screenshotDirectory = path.join(output, 'screenshots', profile.id);
      await fs.mkdir(screenshotDirectory, { recursive: true });
      activeRoute = pathname;
      activeVariant = variant;
      activeActor = pathname === '/admin' ? actors.Admin : variant === 'actor-B' || (nativePaired && (pathname.startsWith('/fumo') || pathname.startsWith('/libreria'))) ? actors.B : actors.A;
      await page.goto(origin + pathname + (variant === 'actor-B' ? '?fixtureActor=B' : ''), { waitUntil: 'networkidle', timeout: 45000 });
      if (new URL(page.url()).pathname !== pathname) throw new Error(`Route unexpectedly changed: ${pathname}`);
      await page.waitForFunction(() => !!document.querySelector(location.pathname === '/auth' ? '#root h1' : 'main h1'), undefined, { timeout: 15000 });
      if (profile.font !== 1) await page.addStyleTag({ content: `html{font-size:${profile.font * 100}% !important}` });
      await page.evaluate(() => document.fonts.ready);
      let filterInteraction = null;
      if (variant === 'between-common-dates') {
        const defaultFilterText = await page.getByRole('combobox').first().innerText();
        await page.getByRole('combobox').first().click();
        await page.getByRole('option', { name: profile.language === 'it' ? 'Tra date' : 'Between dates', exact: true }).click();
        await page.locator('main input[type=date]').nth(0).fill('2026-06-01');
        await page.locator('main input[type=date]').nth(1).fill('2026-06-15');
        filterInteraction = { defaultFilterText, selectedFilterText: await page.getByRole('combobox').first().innerText(),
          from: await page.locator('main input[type=date]').nth(0).inputValue(), to: await page.locator('main input[type=date]').nth(1).inputValue(),
          method: 'Production Select option click and native date input fill; no hook or markup replacement' };
      }
      if (variant === 'signup' || variant === 'options') {
        await page.getByRole('tab').nth(1).click();
        await page.waitForLoadState('networkidle');
      }
      await page.waitForTimeout(700);
      const viewportFile = path.join(screenshotDirectory, `${name}-viewport.png`);
      const fullFile = path.join(screenshotDirectory, `${name}-full.png`);
      const contentFile = path.join(screenshotDirectory, `${name}-content.png`);
      await page.screenshot({ path: viewportFile, animations: 'disabled' });
      await page.screenshot({ path: fullFile, fullPage: true, animations: 'disabled' });
      const contentSelector = pathname === '/auth' ? '#root > div.min-h-screen.flex.dark' : variant === 'security' ? 'main h3:has(svg.lucide-shield)' : 'main';
      const contentLocator = variant === 'security' ? page.locator(contentSelector).locator('..') : page.locator(contentSelector);
      const contentBounds = await contentLocator.boundingBox();
      const contentDocumentBounds = await contentLocator.evaluate(node => { const b = node.getBoundingClientRect(); return { x: b.x + scrollX, y: b.y + scrollY, width: b.width, height: b.height }; });
      await contentLocator.screenshot({ path: contentFile, animations: 'disabled', ...(scope === 'finance-modules' ? {} : { style: contentCaptureStyle }) });
      const observations = await page.evaluate(captureStyle => ({ heading: [...document.querySelectorAll(location.pathname === '/auth' ? '#root h1' : 'main h1')].find(node => node.getBoundingClientRect().width)?.textContent, documentLanguage: document.documentElement.lang, dark: document.documentElement.classList.contains('dark'), rootFontSize: getComputedStyle(document.documentElement).fontSize, viewportWidth: innerWidth, documentWidth: document.documentElement.scrollWidth, mainPadding: document.querySelector('main') ? getComputedStyle(document.querySelector('main')).padding : null, bodyText: document.body.innerText, fonts: [...document.fonts].map(font => ({ family: font.family, status: font.status })),
        captureStyleMatches: [...document.querySelectorAll(captureStyle.split('{')[0])].map(node => ({ tag: node.tagName, className: node.className, containsPageContent: !!node.querySelector('main') || !!node.closest('main') })),
        gradientText: [...document.querySelectorAll('.text-gradient')].filter(node => node.getBoundingClientRect().width).map(node => ({ text: node.textContent,
          backgroundClip: getComputedStyle(node).backgroundClip, webkitBackgroundClip: getComputedStyle(node).webkitBackgroundClip,
          backgroundImage: getComputedStyle(node).backgroundImage, color: getComputedStyle(node).color }))
      }), contentCaptureStyle);
      const result = { profile: profile.id, route: pathname, scenario: name, variant, actor: scope === 'account' || nativePaired ? pathname === '/auth' ? 'unauthenticated' : activeActor.id : owner,
        filterInteraction,
        viewportFile: path.relative(output, viewportFile), fullFile: path.relative(output, fullFile), contentFile: path.relative(output, contentFile),
        contentSelector: contentSelector + (variant === 'security' ? ' parent' : ''), contentBounds, contentDocumentBounds,
        captureOnlyStyle: scope === 'finance-modules' ? null : contentCaptureStyle, ...observations };
      results.push(result);
      await fs.writeFile(path.join(output, 'capture-results.json'), JSON.stringify({ sourceRevision: revision, clock: dataset.clock, results, errors, network }, null, 2) + '\n');
      console.log(`Captured ${profile.id}/${name}: ${observations.heading}`);
    }
    await context.close();
  }
} finally {
  if (browser) await browser.close();
  await server.close();
  await fs.writeFile(path.join(output, 'capture-results.json'), JSON.stringify({ sourceRevision: revision, clock: dataset.clock, results, errors, network }, null, 2) + '\n');
}
