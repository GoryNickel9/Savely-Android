// Reads the reference checkout; writes only an Android fixture file.
import { execFileSync } from 'node:child_process';
import { resolve, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { mkdirSync, writeFileSync } from 'node:fs';

const reference = resolve(process.argv[2] ?? '../Savely');
const sha = execFileSync('git', ['-C', reference, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
const baseline = '1cfcf6297e85d56bfafe3522362ceecef6f6b281';
if (sha !== baseline) throw new Error(`Reference revision ${sha} differs from ${baseline}`);
const OriginalDate = Date;
globalThis.Date = class extends OriginalDate {
  constructor(...args) { super(...(args.length ? args : ['2026-10-11T12:00:00Z'])); }
};
const fire = await import(pathToFileURL(resolve(reference, 'src/lib/fire/calculations.ts')));
const fumo = await import(pathToFileURL(resolve(reference, 'src/lib/fumoCrud.ts')));
const inputs = {
  currentAge: 30, retirementAge: 60, currentSavings: 100000, annualContribution: 24000,
  annualIncome: 60000, expectedReturn: 0.07, inflationRate: 0.03,
  withdrawalRate: 0.04, annualExpenses: 48000,
};
const standard = fire.calculateStandardFIRE(inputs);
const baristaInputs = { ...inputs, currentAge: 35, currentSavings: 50000, annualContribution: 12000,
  expectedReturn: 0.06, inflationRate: 0.02, annualExpenses: 36000 };
const barista = fire.calculateBaristaFIRE(35, 50000, 12000, 0.06, 0.02, 36000, 0.04, 12000);
const destination = resolve(dirname(fileURLToPath(import.meta.url)), 'fixtures');
mkdirSync(destination, { recursive: true });
writeFileSync(resolve(destination, 'web-calculations.json'), JSON.stringify({
  schemaVersion: 1, sourceRevision: sha, fixedDate: '2026-10-11T12:00:00Z',
  provenance: 'Executed unmodified TypeScript exports from the read-only web checkout using Node type stripping',
  standard: { inputs, result: standard },
  barista: { inputs: baristaInputs, partTimeIncome: 12000, result: barista },
  fumo: { inputs: { arrival: '2026-01-01', finished: '2026-01-11', quantity: 20, cost: 50 },
    result: fumo.computeDerived('2026-01-01', '2026-01-11', 20, 50) },
}, null, 2) + '\n');
console.log(`Generated ${resolve(destination, 'web-calculations.json')} from ${sha}`);
