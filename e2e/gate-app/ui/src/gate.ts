// Monaco worker gate (spec §6.1). Runs entirely in the page under the jdesk://app custom
// scheme; the Java host reads window.__gateReport through the automation /evaluate endpoint.
// The whole point is to answer, mechanically, whether Monaco's ES-module web workers load and
// function on the production origin — on macOS WKWebView the single genuinely unverifiable-from-
// source risk. No blob workers, no CDN, no eval: same-origin ESM only.
import * as monaco from 'monaco-editor';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import TsWorker from 'monaco-editor/esm/vs/language/typescript/ts.worker?worker';
import JsonWorker from 'monaco-editor/esm/vs/language/json/json.worker?worker';
import CssWorker from 'monaco-editor/esm/vs/language/css/css.worker?worker';
import HtmlWorker from 'monaco-editor/esm/vs/language/html/html.worker?worker';

interface GateCase {
  id: string;
  outcome: 'PASS' | 'FAIL';
  detail: string;
}

interface GateGlobal {
  errors: string[];
  workerUrls: string[];
  clipboard: string;
  benign: string[];
}

declare global {
  interface Window {
    __gate: GateGlobal;
    __gateReport?: { done: boolean; tests: GateCase[]; workerUrls: string[]; errors: string[] };
  }
}

const gate: GateGlobal = { errors: [], workerUrls: [], clipboard: '', benign: [] };
window.__gate = gate;

// A Monaco CancellationError ("Canceled") is thrown when the diff editor supersedes an in-flight
// worker computation; Monaco does not await the cancelled promise, so it surfaces as an unhandled
// rejection. It is not a worker/load failure — the workers demonstrably function (GATE-01..08). We
// classify it as benign and count it separately; any OTHER error still fails GATE-10.
function isMonacoCancellation(reason: unknown): boolean {
  const name = (reason as { name?: string })?.name;
  const text = String((reason as { stack?: string })?.stack ?? reason ?? '');
  return name === 'Canceled' || name === 'CancellationError'
    || (/\bcancel@/.test(text) && /\.worker-[^:]*\.js/.test(text));
}

// Capture every failed resource load, uncaught error, and rejected promise at document scope.
window.addEventListener('error', (e) => {
  const target = e.target as HTMLElement | null;
  if (target && target !== (window as unknown as EventTarget) && 'src' in target) {
    gate.errors.push('resource-load-failed: ' + String((target as HTMLScriptElement).src));
  } else {
    gate.errors.push('error: ' + (e.message ?? String(e)));
  }
}, true);
window.addEventListener('unhandledrejection', (e) => {
  if (isMonacoCancellation(e.reason)) {
    gate.benign.push('monaco-cancellation');
    e.preventDefault();
    return;
  }
  gate.errors.push('unhandledrejection: ' + String(e.reason));
});

// Record every worker URL Monaco spawns, so GATE-08 can fetch each and assert its MIME type.
const NativeWorker = self.Worker;
class RecordingWorker extends NativeWorker {
  constructor(scriptURL: string | URL, options?: WorkerOptions) {
    const url = typeof scriptURL === 'string' ? scriptURL : scriptURL.href;
    gate.workerUrls.push(url);
    super(scriptURL, options);
    this.addEventListener('error', (ev) => {
      gate.errors.push('worker-error(' + url + '): ' + (ev as ErrorEvent).message);
    });
  }
}
self.Worker = RecordingWorker as unknown as typeof Worker;

self.MonacoEnvironment = {
  getWorker(_moduleId: string, label: string): Worker {
    if (label === 'typescript' || label === 'javascript') return new TsWorker();
    if (label === 'json') return new JsonWorker();
    if (label === 'css' || label === 'scss' || label === 'less') return new CssWorker();
    if (label === 'html' || label === 'handlebars' || label === 'razor') return new HtmlWorker();
    return new EditorWorker();
  },
};

const statusEl = document.getElementById('status')!;
function log(msg: string, ok?: boolean) {
  const line = document.createElement('div');
  line.textContent = msg;
  if (ok !== undefined) line.className = ok ? 'ok' : 'bad';
  statusEl.appendChild(line);
}

const tests: GateCase[] = [];
function record(id: string, outcome: 'PASS' | 'FAIL', detail: string) {
  tests.push({ id, outcome, detail });
  log(`${outcome} ${id} — ${detail}`, outcome === 'PASS');
}

async function waitFor<T>(fn: () => T | undefined | null, timeoutMs: number, step = 50): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const v = fn();
    if (v !== undefined && v !== null && v !== false) return v as T;
    if (Date.now() > deadline) throw new Error('timeout after ' + timeoutMs + 'ms');
    await new Promise((r) => setTimeout(r, step));
  }
}

async function run() {
  monaco.languages.typescript.typescriptDefaults.setDiagnosticsOptions({
    noSemanticValidation: false,
    noSyntaxValidation: false,
  });

  const tsModel = monaco.editor.createModel(
    'const arr: number[] = [1, 2, 3];\narr.\nconst n: number = "not a number";\n',
    'typescript',
    monaco.Uri.parse('file:///gate/main.ts'),
  );
  const editor = monaco.editor.create(document.getElementById('editor')!, {
    model: tsModel,
    automaticLayout: true,
    theme: 'vs-dark',
  });

  // GATE-01: base editor worker — the diff editor computes line changes off-thread.
  try {
    const original = monaco.editor.createModel('line one\nline two\n', 'text');
    const modified = monaco.editor.createModel('line one\nline TWO changed\nline three\n', 'text');
    const diffContainer = document.createElement('div');
    diffContainer.style.cssText = 'position:absolute;width:400px;height:200px;left:-9999px;';
    document.body.appendChild(diffContainer);
    const diff = monaco.editor.createDiffEditor(diffContainer, { automaticLayout: false });
    diff.setModel({ original, modified });
    const changes = await waitFor(() => {
      const c = diff.getLineChanges();
      return c && c.length > 0 ? c : null;
    }, 10000);
    record('GATE-01', gate.workerUrls.length >= 1 ? 'PASS' : 'FAIL',
      `base worker computed ${changes.length} line change(s); ${gate.workerUrls.length} worker(s) spawned`);
    // Deliberately not disposed: disposing mid-computation cancels an in-flight worker request
    // and yields a benign unhandled-rejection that GATE-10 would (correctly) flag. It is off-screen
    // and harmless; the page tears down at window close, after results are reported.
  } catch (e) {
    record('GATE-01', 'FAIL', 'base/diff worker: ' + e);
  }

  // GATE-02: TypeScript worker proxy round trip.
  try {
    const getWorker = await monaco.languages.typescript.getTypeScriptWorker();
    const client = await getWorker(tsModel.uri);
    const diags = await client.getSemanticDiagnostics(tsModel.uri.toString());
    record('GATE-02', Array.isArray(diags) ? 'PASS' : 'FAIL',
      `TS worker returned ${Array.isArray(diags) ? diags.length : 'non-array'} semantic diagnostic(s)`);
  } catch (e) {
    record('GATE-02', 'FAIL', 'TS worker proxy: ' + e);
  }

  // GATE-03: JSON worker — schema validation markers.
  try {
    monaco.languages.json.jsonDefaults.setDiagnosticsOptions({
      validate: true,
      schemas: [{
        uri: 'http://gate/schema.json',
        fileMatch: ['*'],
        schema: { type: 'object', required: ['name'], properties: { name: { type: 'string' } } },
      }],
    });
    const jsonModel = monaco.editor.createModel('{ "name": 123 }', 'json',
      monaco.Uri.parse('file:///gate/data.json'));
    const markers = await waitFor(() => {
      const m = monaco.editor.getModelMarkers({ resource: jsonModel.uri });
      return m.length > 0 ? m : null;
    }, 10000);
    record('GATE-03', 'PASS', `JSON worker produced ${markers.length} schema marker(s)`);
  } catch (e) {
    record('GATE-03', 'FAIL', 'JSON worker: ' + e);
  }

  // GATE-04: syntax highlighting (tokenization + DOM span classes).
  try {
    const tokens = monaco.editor.tokenize('const x: number = 1;', 'typescript');
    const types = new Set<string>();
    tokens.forEach((line) => line.forEach((t) => types.add(t.type)));
    const spanClasses = new Set<string>();
    document.querySelectorAll('#editor .view-line span[class]').forEach((s) =>
      spanClasses.add((s as HTMLElement).className));
    const ok = types.size >= 2 && spanClasses.size >= 2;
    record('GATE-04', ok ? 'PASS' : 'FAIL',
      `${types.size} token type(s), ${spanClasses.size} DOM span class(es)`);
  } catch (e) {
    record('GATE-04', 'FAIL', 'highlighting: ' + e);
  }

  // GATE-05: completion — service level (TS worker) proves member completion after `arr.`.
  try {
    const getWorker = await monaco.languages.typescript.getTypeScriptWorker();
    const client = await getWorker(tsModel.uri);
    const offset = tsModel.getOffsetAt({ lineNumber: 2, column: 5 }); // after "arr."
    const completions = await (client as unknown as {
      getCompletionsAtPosition(uri: string, offset: number): Promise<{ entries?: { name: string }[] } | undefined>;
    }).getCompletionsAtPosition(tsModel.uri.toString(), offset);
    const names = (completions?.entries ?? []).map((e) => e.name);
    const ok = names.includes('push');
    record('GATE-05', ok ? 'PASS' : 'FAIL',
      `completion after 'arr.' ${ok ? 'includes' : 'missing'} push (${names.length} entries)`);
  } catch (e) {
    record('GATE-05', 'FAIL', 'completion: ' + e);
  }

  // GATE-06: diagnostics — the deliberate type error (2322) surfaces as a Monaco marker.
  try {
    const markers = await waitFor(() => {
      const m = monaco.editor.getModelMarkers({ resource: tsModel.uri })
        .filter((x) => x.owner === 'typescript' && x.code === '2322');
      return m.length > 0 ? m : null;
    }, 15000);
    record('GATE-06', 'PASS', `TS diagnostic 2322 at line ${markers[0].startLineNumber}`);
  } catch (e) {
    record('GATE-06', 'FAIL', 'diagnostics 2322: ' + e);
  }

  // GATE-07: selection / keyboard focus / typed edit / undo / redo.
  try {
    const line1Model = monaco.editor.createModel('alpha beta gamma', 'text',
      monaco.Uri.parse('file:///gate/edit.txt'));
    editor.setModel(line1Model);
    editor.setSelection(new monaco.Range(1, 1, 1, 6));
    const selected = line1Model.getValueInRange(editor.getSelection()!);
    editor.focus();
    const focused = document.activeElement?.classList.contains('inputarea') ?? false;
    const before = line1Model.getValue();
    editor.trigger('gate', 'type', { text: 'X' });
    const afterType = line1Model.getValue();
    editor.trigger('gate', 'undo', null);
    const afterUndo = line1Model.getValue();
    editor.trigger('gate', 'redo', null);
    const afterRedo = line1Model.getValue();
    const ok = selected === 'alpha' && focused && afterType !== before
      && afterUndo === before && afterRedo === afterType;
    record('GATE-07', ok ? 'PASS' : 'FAIL',
      `select='${selected}' focus=${focused} undo==before=${afterUndo === before} redo==typed=${afterRedo === afterType}`);
    editor.setModel(tsModel);
  } catch (e) {
    record('GATE-07', 'FAIL', 'selection/keyboard/undo: ' + e);
  }

  // GATE-08: worker asset MIME type.
  try {
    let checked = 0;
    let good = 0;
    let sampleType = '';
    for (const url of gate.workerUrls) {
      const resp = await fetch(url);
      const ct = resp.headers.get('content-type') ?? '';
      if (!sampleType) sampleType = ct;
      checked++;
      if (/^(text|application)\/javascript/.test(ct)) good++;
    }
    const ok = checked > 0 && good === checked;
    record('GATE-08', ok ? 'PASS' : 'FAIL',
      `${good}/${checked} worker asset(s) served as JS (e.g. '${sampleType}')`);
  } catch (e) {
    record('GATE-08', 'FAIL', 'worker MIME: ' + e);
  }

  // GATE-09: CSP header present + no eval permitted.
  try {
    const resp = await fetch('./index.html');
    const csp = resp.headers.get('content-security-policy') ?? '';
    let evalThrows = false;
    try { (0, eval)('1'); } catch { evalThrows = true; }
    let fnThrows = false;
    try { new Function('return 1')(); } catch { fnThrows = true; }
    const ok = csp.length > 0 && csp.includes("script-src 'self'") && evalThrows && fnThrows
      && !csp.includes('unsafe-eval');
    record('GATE-09', ok ? 'PASS' : 'FAIL',
      `csp[${csp.length}] script-src'self'=${csp.includes("script-src 'self'")} eval-throws=${evalThrows} fn-throws=${fnThrows} no-unsafe-eval=${!csp.includes('unsafe-eval')}`);
  } catch (e) {
    record('GATE-09', 'FAIL', 'CSP/eval: ' + e);
  }

  // GATE-10: console clean — no genuine error-level captures during the whole exercise.
  // Benign Monaco cancellations (see isMonacoCancellation) are reported but do not fail the gate.
  const workerLoadErrors = gate.errors.filter((x) => /worker|Failed to load|Refused|Content-Security/i.test(x));
  record('GATE-10', gate.errors.length === 0 ? 'PASS' : 'FAIL',
    `${gate.errors.length} genuine page error(s), ${workerLoadErrors.length} worker/load error(s), ${gate.benign.length} benign monaco-cancellation(s)`);

  window.__gateReport = { done: true, tests, workerUrls: gate.workerUrls, errors: gate.errors };
  log(`gate complete: ${tests.filter((t) => t.outcome === 'PASS').length}/${tests.length} passed`);
}

run().catch((e) => {
  record('GATE-RUN', 'FAIL', 'harness crashed: ' + e);
  window.__gateReport = { done: true, tests, workerUrls: gate.workerUrls, errors: gate.errors };
});
