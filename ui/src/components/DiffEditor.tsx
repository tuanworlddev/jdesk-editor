import { useEffect, useRef } from 'react';
import { monaco } from '../monaco-setup';
import { getDiffModel } from '../models';

/** Monaco diff editor for a git change (original = HEAD, modified = working copy). */
export function DiffEditor({ diffUri }: { diffUri: string }) {
  const hostRef = useRef<HTMLDivElement>(null);
  const editorRef = useRef<monaco.editor.IStandaloneDiffEditor | null>(null);

  useEffect(() => {
    if (!hostRef.current) return;
    const editor = monaco.editor.createDiffEditor(hostRef.current, {
      theme: 'dracula',
      automaticLayout: true,
      readOnly: true,
      renderSideBySide: true,
      fontSize: 13,
      fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
      minimap: { enabled: false },
    });
    editorRef.current = editor;
    return () => editor.dispose();
  }, []);

  useEffect(() => {
    const entry = getDiffModel(diffUri);
    if (editorRef.current && entry) {
      editorRef.current.setModel({ original: entry.original, modified: entry.modified });
    }
  }, [diffUri]);

  return <div ref={hostRef} className="absolute inset-0" />;
}
