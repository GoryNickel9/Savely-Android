// Render the trusted web baseline read-only, replacing navigation and translations with static text.
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const source = path.resolve(process.argv[2] || '../Savely');
const webRequire = createRequire(path.join(source, 'package.json'));
const ts = webRequire('typescript');
const React = webRequire('react');
const { renderToStaticMarkup } = webRequire('react-dom/server');

function load(relative, imports = {}) {
  const exports = {};
  const code = ts.transpileModule(fs.readFileSync(path.join(source, relative), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2020 },
  }).outputText;
  const requireKnown = name => {
    if (Object.hasOwn(imports, name)) return imports[name];
    if (name === 'react/jsx-runtime') return webRequire(name);
    throw new Error(`Unexpected dependency: ${name}`);
  };
  new Function('exports', 'require', code)(exports, requireKnown);
  return exports;
}
const legal = load('src/lib/legalContents.ts');
const t = (text, values = {}) => text.replace(/\{\{(\w+)\}\}/g, (_, key) => values[key] ?? `{{${key}}}`);
const imports = {
  'react-i18next': { useTranslation: () => ({ t }) },
  'react-router': { Link: ({ children }) => React.createElement('span', null, children) },
  '@/lib/legalContents': legal,
  '@/components/legal/LegalLayout': {
    __esModule: true,
    default: ({ title, subtitle, children }) => React.createElement('article', null,
      React.createElement('h1', null, title), React.createElement('p', null, subtitle), children),
    LegalSection: ({ number, title, children }) => React.createElement('section', null,
      React.createElement('h2', null, `${number}. ${title}`), children),
  },
};
function textFromHtml(html) {
  return html.replace(/<\/(?:h1|h2|p|li)>/g, '\n\n').replace(/<li[^>]*>/g, '• ')
    .replace(/<[^>]*>/g, '').replace(/&#x27;/g, "'").replace(/&quot;/g, '"')
    .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/\n{3,}/g, '\n\n').trim();
}
const target = path.resolve('app/src/main/assets/legal');
fs.mkdirSync(target, { recursive: true });
for (const [page, file] of [['Privacy', 'privacy'], ['Cookies', 'cookies'], ['Termini', 'terms']]) {
  const Page = load(`src/pages/${page}.tsx`, imports).default;
  const text = textFromHtml(renderToStaticMarkup(React.createElement(Page)));
  if (text.length < 1000 || /\{\{\w+\}\}/.test(text)) throw new Error(`Incomplete legal document: ${page}`);
  fs.writeFileSync(path.join(target, `${file}.txt`), `${text}\n`, 'utf8');
  console.log(`${file}: ${text.length} characters`);
}
