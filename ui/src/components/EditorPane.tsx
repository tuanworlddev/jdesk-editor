import { useEffect, useRef } from 'react';
import { useStore } from '../store';
import { monaco } from '../monaco-setup';
import { getModel } from '../models';
import { DiffEditor } from './DiffEditor';
import { Breadcrumbs } from './Breadcrumbs';

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
  const tabs = useStore((s) => s.tabs);
  const activeTab = tabs.find((t) => t.uri === activeUri);
  const isDiff = activeTab?.kind === 'diff';

  useEffect(() => {
    if (!containerRef.current) return;
    const editor = monaco.editor.create(containerRef.current, {
      model: null,
      theme: 'dracula',
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
    if (activeUri && !isDiff) {
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
    currentUri.current = isDiff ? null : activeUri;
  }, [activeUri, isDiff]);

  return (
    <div className="relative flex h-full w-full flex-col">
      {activeTab && !isDiff && <Breadcrumbs relPath={activeTab.relPath} />}
      <div className="relative min-h-0 flex-1">
      {isDiff && activeUri && <DiffEditor key={activeUri} diffUri={activeUri} />}
      {!activeUri && (
        <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 text-[var(--color-fg-dim)]">
          <h1 className="m-0 text-[26px] font-semibold tracking-tight text-[var(--color-fg-bright)]">JDesk Editor</h1>
          <p>Select a file in the Explorer to start editing.</p>
          <p className="text-[12px] text-[var(--color-accent-dim)]">
            Agent-native · Monaco on the native WebView · ⌘S to save
          </p>
        </div>
      )}
      <div ref={containerRef} className="absolute inset-0"
        style={{ display: activeUri && !isDiff ? 'block' : 'none' }} />
      </div>
    </div>
  );
}

// Official Dracula theme for Monaco (https://draculatheme.com token mapping).
monaco.editor.defineTheme('dracula', {
  base: 'vs-dark',
  inherit: true,
  rules: [
    { token: '', foreground: 'f8f8f2', background: '282a36' },
    { token: 'comment', foreground: '6272a4' },
    { token: 'string', foreground: 'f1fa8c' },
    { token: 'number', foreground: 'bd93f9' },
    { token: 'constant.numeric', foreground: 'bd93f9' },
    { token: 'constant.language', foreground: 'bd93f9' },
    { token: 'keyword', foreground: 'ff79c6' },
    { token: 'keyword.operator', foreground: 'ff79c6' },
    { token: 'operator', foreground: 'ff79c6' },
    { token: 'storage', foreground: 'ff79c6' },
    { token: 'type', foreground: '8be9fd', fontStyle: 'italic' },
    { token: 'type.identifier', foreground: '8be9fd' },
    { token: 'identifier', foreground: 'f8f8f2' },
    { token: 'function', foreground: '50fa7b' },
    { token: 'entity.name.function', foreground: '50fa7b' },
    { token: 'variable', foreground: 'f8f8f2' },
    { token: 'variable.parameter', foreground: 'ffb86c', fontStyle: 'italic' },
    { token: 'tag', foreground: 'ff79c6' },
    { token: 'metatag', foreground: 'ff79c6' },
    { token: 'attribute.name', foreground: '50fa7b' },
    { token: 'attribute.value', foreground: 'f1fa8c' },
    { token: 'delimiter', foreground: 'f8f8f2' },
    { token: 'delimiter.bracket', foreground: 'f8f8f2' },
    { token: 'string.key.json', foreground: '8be9fd' },
    { token: 'string.value.json', foreground: 'f1fa8c' },
    { token: 'keyword.json', foreground: 'bd93f9' },
  ],
  colors: {
    'editor.background': '#282a36',
    'editor.foreground': '#f8f8f2',
    'editor.lineHighlightBackground': '#44475a75',
    'editor.selectionBackground': '#44475a',
    'editor.selectionHighlightBackground': '#424450',
    'editorCursor.foreground': '#f8f8f2',
    'editorLineNumber.foreground': '#6272a4',
    'editorLineNumber.activeForeground': '#f8f8f2',
    'editorGutter.background': '#282a36',
    'editorIndentGuide.background': '#3b3d4b',
    'editorIndentGuide.activeBackground': '#6272a4',
    'editorWhitespace.foreground': '#3b3d4b',
    'editorSuggestWidget.background': '#21222c',
    'editorSuggestWidget.selectedBackground': '#44475a',
    'editorSuggestWidget.border': '#191a21',
    'editorHoverWidget.background': '#21222c',
    'editorBracketMatch.background': '#44475a',
    'editorBracketMatch.border': '#bd93f9',
  },
});
