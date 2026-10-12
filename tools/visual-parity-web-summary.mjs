import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const scopeIndex = process.argv.indexOf('--scope');
const scope = scopeIndex >= 0 ? process.argv[scopeIndex + 1] : 'finance-modules';
if (!['finance-modules', 'content-clean', 'account'].includes(scope)) throw new Error('Unknown capture scope');
const labelIndex = process.argv.indexOf('--run-label');
const label = labelIndex >= 0 ? process.argv[labelIndex + 1] : null;
if (label && !/^[a-z0-9-]+$/.test(label)) throw new Error('Invalid run label');
const output = path.join(repository, 'artifacts/visual-parity/web', ...(scope === 'finance-modules' ? [] : [scope]), ...(label ? [label] : []));
const captures = JSON.parse(await fs.readFile(path.join(output, 'capture-results.json')));
const manifest = JSON.parse(await fs.readFile(path.join(output, 'source-manifest.json')));
const images = [];
for (const capture of captures.results) {
  for (const field of ['viewportFile', 'fullFile', 'contentFile']) {
    const relative = capture[field];
    const bytes = await fs.readFile(path.join(output, relative));
    images.push({ path: relative, kind: field, profile: capture.profile, route: capture.route,
      width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20), bytes: bytes.length,
      sha256: createHash('sha256').update(bytes).digest('hex') });
  }
}
const sourceChanged = [];
for (const file of manifest.files) {
  const bytes = await fs.readFile(path.join(repository, manifest.sourceDirectory ?? 'artifacts/visual-parity/web/source', file.path));
  if (createHash('sha256').update(bytes).digest('hex') !== file.sha256) sourceChanged.push(file.path);
}
const summary = {
  scope, runLabel: label, nativePairedInputs: manifest.nativePairedInputs ?? false,
  nativeFixtureSources: manifest.nativeFixtureSources ?? [], sourceRevision: manifest.sourceRevision, datasetSha256: manifest.datasetSha256, clock: manifest.clock,
  observationOnly: true, nativeScreenshotsCompared: false, globalVisualParityVerified: false,
  completedCaptures: captures.results.length, imageCount: images.length, sourceChanged,
  profiles: [...new Set(captures.results.map(value => value.profile))].map(profile => ({ profile,
    routes: captures.results.filter(value => value.profile === profile).map(value => value.route) })),
  pageErrors: captures.errors,
  network: {
    logScope: 'Supabase stubs, local font stylesheet fulfillment and external page request attempts; local Vite asset requests omitted',
    realBackendRequests: 0,
    externalRequestsContinued: 0,
    externalAttemptsAborted: captures.network.filter(value => value.status === 'aborted-external').length,
    localFontStylesheetsFulfilled: captures.network.filter(value => value.status === 'fulfilled-local-font-css').length,
    deniedSyntheticMutations: captures.network.filter(value => value.method !== 'GET' && value.method !== 'HEAD' && value.status === 403).length,
    syntheticReadOnlyPosts: captures.network.filter(value => value.status === 'fulfilled-read-only-post').length,
    unsupportedEndpoints: captures.network.filter(value => value.status === 501).length,
  },
  horizontalOverflow: captures.results.filter(value => value.documentWidth > value.viewportWidth).map(value => ({ profile: value.profile, route: value.route,
    viewportWidth: value.viewportWidth, documentWidth: value.documentWidth })),
  limitations: ['HTML font-size200% is not Android fontScale200%; it changes rem-based spacing too',
    'No pixel-perfect navigation-shell comparison', 'Screenshots require visual review against corresponding native artifacts',
    'Unsupported service mutation/auth flows are denied, not qualified', 'Source and font assets unchanged; Tailwind discovery directive injected in memory only'],
  contentCaptureStyle: manifest.contentCaptureStyle ?? null,
  images,
};
await fs.writeFile(path.join(output, 'capture-summary.json'), JSON.stringify(summary, null, 2) + '\n');
console.log(JSON.stringify({ ...summary, images: `${images.length} entries retained in capture-summary.json` }, null, 2));
if (sourceChanged.length || captures.errors.length || summary.network.unsupportedEndpoints) process.exitCode = 1;
