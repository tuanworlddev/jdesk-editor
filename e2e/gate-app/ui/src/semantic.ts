// Semantic micro-proof (spec Phase 0): a stable semantic registry, an in-page pointer actor
// whose geometry never crosses the bridge, one typed Monaco edit transaction verified by an
// independent Java hash, and a single undo group. Driven by the Java host through the
// automation /evaluate endpoint (reads window.__semanticReport); the S3 hash comparison uses a
// pure-JS SHA-256 because jdesk://app is not a secure context on macOS (no crypto.subtle).
import * as monaco from 'monaco-editor';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import { sha256 } from '@noble/hashes/sha2';
import { bytesToHex } from '@noble/hashes/utils';

interface SemCase { id: string; outcome: 'PASS' | 'FAIL'; detail: string }

interface SemanticNode {
  id: string;
  role: string;
  name: string;
  visible: boolean;
  rect: { x: number; y: number; width: number; height: number } | null;
}

declare global {
  interface Window {
    __semantic: {
      snapshot(): SemanticNode[];
      pointerCenterInTarget(id: string): boolean | null;
      applyEdit(baseVersion: number, edits: { range: number[]; text: string }[]): { version: number; hash: string };
      contentHash(): string;
      content(): string;
      undo(): string;
      registryRevision(): number;
    };
    __semanticReport?: { done: boolean; tests: SemCase[] };
  }
}

self.MonacoEnvironment = { getWorker: () => new EditorWorker() };

// --- Semantic registry (module-level, outside any framework state) -----------------------
const registry = new Map<string, () => HTMLElement | null>();
let revision = 0;
function registerFromDom() {
  document.querySelectorAll('[data-sem]').forEach((el) => {
    const id = el.id;
    if (id && !registry.has(id)) {
      registry.set(id, () => document.getElementById(id));
      revision++;
    }
  });
}

const statusEl = document.getElementById('status')!;
function log(msg: string, ok?: boolean) {
  const line = document.createElement('div');
  line.textContent = msg;
  if (ok !== undefined) line.className = ok ? 'ok' : 'bad';
  statusEl.appendChild(line);
}

// Plaintext: the semantic proof exercises registry/pointer/edit-transaction/undo, none of which
// need a language service. Using a language model would spin up a language worker unnecessarily.
const model = monaco.editor.createModel('function hello() {\n  return 1;\n}\n', 'plaintext',
  monaco.Uri.parse('file:///gate/semantic.txt'));
const editor = monaco.editor.create(document.getElementById('editor')!, {
  model, automaticLayout: true, theme: 'vs-dark',
});
const editorHost = document.getElementById('editor')!;
editorHost.id = 'editor:semantic.ts';
editorHost.setAttribute('data-sem', 'editor');
registerFromDom();

const jsSha = (s: string) => bytesToHex(sha256(new TextEncoder().encode(s)));
const pointer = document.getElementById('pointer')!;

window.__semantic = {
  snapshot() {
    return Array.from(registry.entries()).map(([id, getter]) => {
      const el = getter();
      const rect = el ? el.getBoundingClientRect() : null;
      return {
        id,
        role: el?.getAttribute('data-sem') ?? 'unknown',
        name: (el?.getAttribute('placeholder') || el?.textContent || id).trim().slice(0, 40),
        visible: !!el && rect !== null && rect.width > 0,
        rect: rect ? { x: rect.x, y: rect.y, width: rect.width, height: rect.height } : null,
      };
    });
  },
  pointerCenterInTarget(id: string) {
    const getter = registry.get(id);
    const el = getter?.();
    if (!el) return null;
    // Geometry is computed here, in the page, at execution time — never handed across the bridge.
    const r = el.getBoundingClientRect();
    const cx = r.x + r.width / 2;
    const cy = r.y + r.height / 2;
    pointer.style.display = 'block';
    pointer.style.left = cx + 'px';
    pointer.style.top = cy + 'px';
    const pr = pointer.getBoundingClientRect();
    const px = pr.x + pr.width / 2;
    const py = pr.y + pr.height / 2;
    return px >= r.x && px <= r.x + r.width && py >= r.y && py <= r.y + r.height;
  },
  applyEdit(baseVersion, edits) {
    if (model.getVersionId() !== baseVersion) {
      throw new Error('stale base version');
    }
    editor.pushUndoStop();
    model.pushEditOperations([], edits.map((e) => ({
      range: new monaco.Range(e.range[0], e.range[1], e.range[2], e.range[3]),
      text: e.text,
    })), () => null);
    editor.pushUndoStop();
    return { version: model.getVersionId(), hash: jsSha(model.getValue()) };
  },
  contentHash() { return jsSha(model.getValue()); },
  content() { return model.getValue(); },
  undo() { editor.trigger('gate', 'undo', null); return jsSha(model.getValue()); },
  registryRevision() { return revision; },
};

// S1..S4 are asserted by the Java host via /evaluate, but the page self-checks and logs too.
const tests: SemCase[] = [];
function record(id: string, ok: boolean, detail: string) {
  tests.push({ id, outcome: ok ? 'PASS' : 'FAIL', detail });
  log(`${ok ? 'PASS' : 'FAIL'} ${id} — ${detail}`, ok);
}

function selfCheck() {
  const snap = window.__semantic.snapshot();
  record('S1', snap.length >= 3 && snap.some((n) => n.id === 'editor:semantic.ts'),
    `${snap.length} nodes registered, revision ${revision}`);

  const inTarget = window.__semantic.pointerCenterInTarget('gate.newFile');
  record('S2', inTarget === true, `pointer center inside gate.newFile = ${inTarget}`);

  const base = model.getVersionId();
  const before = window.__semantic.contentHash();
  const res = window.__semantic.applyEdit(base, [{ range: [2, 10, 2, 11], text: '42' }]);
  const recomputed = jsSha(model.getValue());
  record('S3', res.hash === recomputed && res.version > base,
    `edit txn version ${base}->${res.version}, jsHash=${res.hash.slice(0, 12)}…`);

  const afterUndo = window.__semantic.undo();
  record('S4', afterUndo === before, `single undo restores pre-txn hash: ${afterUndo === before}`);

  window.__semanticReport = { done: true, tests };
  log(`semantic proof complete: ${tests.filter((t) => t.outcome === 'PASS').length}/${tests.length} passed`);
}

setTimeout(selfCheck, 300);
