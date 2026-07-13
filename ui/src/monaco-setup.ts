// Monaco environment: same-origin ES-module workers, the strategy proven on WKWebView by the
// Phase-0 gate. Imported once before any editor is created.
import * as monaco from 'monaco-editor';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import TsWorker from 'monaco-editor/esm/vs/language/typescript/ts.worker?worker';
import JsonWorker from 'monaco-editor/esm/vs/language/json/json.worker?worker';
import CssWorker from 'monaco-editor/esm/vs/language/css/css.worker?worker';
import HtmlWorker from 'monaco-editor/esm/vs/language/html/html.worker?worker';

self.MonacoEnvironment = {
  getWorker(_moduleId: string, label: string): Worker {
    if (label === 'typescript' || label === 'javascript') return new TsWorker();
    if (label === 'json') return new JsonWorker();
    if (label === 'css' || label === 'scss' || label === 'less') return new CssWorker();
    if (label === 'html' || label === 'handlebars' || label === 'razor') return new HtmlWorker();
    return new EditorWorker();
  },
};

const LANGUAGE_BY_EXT: Record<string, string> = {
  ts: 'typescript', tsx: 'typescript', js: 'javascript', jsx: 'javascript', mjs: 'javascript',
  json: 'json', css: 'css', scss: 'scss', less: 'less', html: 'html', md: 'markdown',
  java: 'java', py: 'python', go: 'go', rs: 'rust', kt: 'kotlin', kts: 'kotlin',
  xml: 'xml', yaml: 'yaml', yml: 'yaml', sh: 'shell', sql: 'sql', c: 'c', h: 'c',
  cpp: 'cpp', hpp: 'cpp', cs: 'csharp', rb: 'ruby', php: 'php', txt: 'plaintext',
};

export function languageForPath(relPath: string): string {
  const ext = relPath.split('.').pop()?.toLowerCase() ?? '';
  return LANGUAGE_BY_EXT[ext] ?? 'plaintext';
}

export { monaco };
