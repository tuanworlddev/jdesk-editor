import './monaco-setup'; // installs MonacoEnvironment before any editor is created
import React from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { useStore } from './store';
import { onDirtyChange, getModel, flushNow, javaVersion, hasModel, applyAuthoritativeContent, jsSha256 } from './models';
import { applyTransaction } from './presentation';
import { monaco as monacoRef } from './monaco-setup';
import { on } from './ipc';
import './styles.css';

// Bridge model dirty-state changes into the tab store (kept out of React's render path).
onDirtyChange((uri, dirty) => useStore.getState().markTabDirty(uri, dirty));

// Agent edits arriving via MCP push new content to the live model, so an agent's change appears
// in the running editor. If the changed document is not open yet, open it so the agent's work is
// visible.
on('editor.docChanged', (payload) => {
  const event = payload as { uri: string; content: string; version: number };
  if (hasModel(event.uri)) {
    applyAuthoritativeContent(event.uri, event.content, event.version);
    useStore.getState().setActive(event.uri);
    return;
  }
  // Not open yet (agent created a new file): refresh the Explorer, then open it so the edit shows.
  const store = useStore.getState();
  const rootPath = store.workspace?.rootPath;
  const filePath = decodeURIComponent(event.uri.replace(/^file:\/\//, ''));
  const rel = rootPath && filePath.startsWith(rootPath)
    ? filePath.slice(rootPath.length).replace(/^\//, '')
    : null;
  void store.refreshWorkspace().then(() => {
    if (rel) void store.openFile(rel).catch(() => {});
  });
});

// A file changed on disk outside the editor (spec §16). A clean document reloads; a dirty one is
// marked conflicted and NEVER overwritten. Distinct from an agent's semantic edit above.
const externalState: Record<string, string> = {};
on('editor.externalChange', (payload) => {
  const event = payload as { uri: string; state: string; content: string | null; origin: string };
  externalState[event.uri] = event.state;
  if (event.state === 'RELOADED' && event.content !== null && hasModel(event.uri)) {
    applyAuthoritativeContent(event.uri, event.content, javaVersion(event.uri) + 1);
    useStore.getState().setStatus(`Reloaded from disk (${event.origin})`);
  } else if (event.state === 'CONFLICT') {
    useStore.getState().setStatus(`Conflict: file changed on disk (${event.origin}) — not overwritten`);
  } else if (event.state === 'DELETED') {
    useStore.getState().setStatus(`File deleted on disk (${event.origin})`);
  }
});

// E2E read-only probe extension for watcher assertions.
(window as unknown as { __watcherProbe: unknown }).__watcherProbe = {
  externalState: (uri: string) => externalState[uri] ?? 'NONE',
};

// Restore the workspace the Java side may have opened at startup (E2E passes --workspace).
useStore.getState().refreshWorkspace().catch(() => {});

// Expose a small read-only probe for E2E assertions (test builds drive it via /evaluate).
(window as unknown as { __editorProbe: unknown }).__editorProbe = {
  activeUri: () => useStore.getState().activeUri,
  tabs: () => useStore.getState().tabs.map((t) => t.relPath),
  workspaceRoot: () => useStore.getState().workspace?.rootName ?? null,
  activeText: () => {
    const uri = useStore.getState().activeUri;
    return uri ? (getModel(uri)?.getValue() ?? null) : null;
  },
  activeDirty: () => {
    const uri = useStore.getState().activeUri;
    return uri ? useStore.getState().tabs.find((t) => t.uri === uri)?.dirty ?? false : false;
  },
  // Synchronous getter: the last document version acknowledged by Java (edits auto-flush per frame).
  javaVersion: () => {
    const uri = useStore.getState().activeUri;
    return uri ? javaVersion(uri) : 0;
  },
};

// E2E driver: reachable only through the test-only automation endpoint (production omits the
// automation module entirely). Drives the real code paths — open, edit through Monaco, save.
(window as unknown as { __editorDriver: unknown }).__editorDriver = {
  async openFile(relPath: string) { await useStore.getState().openFile(relPath); return true; },
  async type(text: string) {
    const uri = useStore.getState().activeUri;
    const model = uri ? getModel(uri) : null;
    if (!model) return false;
    const end = model.getFullModelRange().getEndPosition();
    model.applyEdits([{ range: { startLineNumber: end.lineNumber, startColumn: end.column,
      endLineNumber: end.lineNumber, endColumn: end.column }, text }]);
    return true;
  },
  async save() { await useStore.getState().saveActive(); return true; },
  async flushAndVersion() {
    const uri = useStore.getState().activeUri;
    if (!uri) return null;
    await flushNow(uri);
    return javaVersion(uri);
  },
  // Applies the same staged edit in a presentation mode to a scratch model and records the final
  // content hash. The Phase-2 acceptance calls this for INSTANT/LIVE/CINEMATIC and asserts the
  // three hashes are identical (spec §12.4 byte-identical final content).
  applyMode(base: string, editsJson: string, mode: 'INSTANT' | 'LIVE' | 'CINEMATIC') {
    const edits = JSON.parse(editsJson);
    const model = monacoRef.editor.createModel(base, 'plaintext');
    void applyTransaction(null, model, edits, mode).then(() => {
      modeResults[mode] = jsSha256(model.getValue());
      model.dispose();
    });
    return true;
  },
};

const modeResults: Record<string, string> = {};
(window as unknown as { __modeResults: unknown }).__modeResults = modeResults;

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
