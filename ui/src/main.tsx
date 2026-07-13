import './monaco-setup'; // installs MonacoEnvironment before any editor is created
import React from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { useStore } from './store';
import { onDirtyChange, getModel, flushNow, javaVersion } from './models';
import './styles.css';

// Bridge model dirty-state changes into the tab store (kept out of React's render path).
onDirtyChange((uri, dirty) => useStore.getState().markTabDirty(uri, dirty));

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
};

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
