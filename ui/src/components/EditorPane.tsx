import { useEffect, useRef } from 'react';
import { useStore } from '../store';
import { monaco } from '../monaco-setup';
import { getModel } from '../models';

/**
 * A single Monaco editor instance for the whole editor group (spec §8.2). Switching tabs swaps the
 * editor's model — it never creates a second editor, even with 50 tabs open. Per-tab view state
 * (cursor, scroll, folding) is preserved across switches.
 */
export function EditorPane() {
  const containerRef = useRef<HTMLDivElement>(null);
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const viewStates = useRef(new Map<string, monaco.editor.ICodeEditorViewState | null>());
  const currentUri = useRef<string | null>(null);
  const activeUri = useStore((s) => s.activeUri);

  useEffect(() => {
    if (!containerRef.current) return;
    const editor = monaco.editor.create(containerRef.current, {
      model: null,
      theme: 'jdesk-dark',
      automaticLayout: true,
      fontSize: 13,
      fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
      minimap: { enabled: true },
      scrollBeyondLastLine: false,
      renderWhitespace: 'selection',
      smoothScrolling: true,
    });
    editorRef.current = editor;
    return () => editor.dispose();
  }, []);

  useEffect(() => {
    const editor = editorRef.current;
    if (!editor) return;
    // Save the outgoing model's view state, then mount the incoming one.
    if (currentUri.current && currentUri.current !== activeUri) {
      viewStates.current.set(currentUri.current, editor.saveViewState());
    }
    if (activeUri) {
      const model = getModel(activeUri);
      if (model) {
        editor.setModel(model);
        const saved = viewStates.current.get(activeUri);
        if (saved) editor.restoreViewState(saved);
        editor.focus();
      }
    } else {
      editor.setModel(null);
    }
    currentUri.current = activeUri;
  }, [activeUri]);

  return (
    <div className="editor-pane">
      {!activeUri && (
        <div className="editor-welcome">
          <h1>JDesk Editor</h1>
          <p>Select a file in the Explorer to start editing.</p>
          <p className="hint">Agent-native · Monaco on the native WebView · ⌘S to save</p>
        </div>
      )}
      <div ref={containerRef} className="monaco-host" style={{ display: activeUri ? 'block' : 'none' }} />
    </div>
  );
}

// Register a distinct dark theme (original styling, not VS Code's — spec §8.1).
monaco.editor.defineTheme('jdesk-dark', {
  base: 'vs-dark',
  inherit: true,
  rules: [],
  colors: {
    'editor.background': '#15171c',
    'editorGutter.background': '#15171c',
    'editorLineNumber.foreground': '#3b4048',
    'editorLineNumber.activeForeground': '#8a94a6',
  },
});
