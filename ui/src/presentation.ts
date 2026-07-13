// Code presentation modes (spec §12). An agent workspace edit is applied to a Monaco model in one
// of three modes. The modes differ ONLY in how the change is revealed over time — the final content
// is byte-identical in all three (spec §12.4), which the Phase-2 acceptance verifies by hashing.
// All three form exactly one undo group (pushStackElement before and after, no undo stops between).
import { monaco } from './monaco-setup';

export type PresentationMode = 'INSTANT' | 'LIVE' | 'CINEMATIC';

export interface StagedEdit {
  startLine: number;
  startColumn: number;
  endLine: number;
  endColumn: number;
  text: string;
}

function toMonacoEdits(edits: StagedEdit[]): monaco.editor.IIdentifiedSingleEditOperation[] {
  return edits.map((e) => ({
    range: new monaco.Range(e.startLine, e.startColumn, e.endLine, e.endColumn),
    text: e.text,
    forceMoveMarkers: true,
  }));
}

const raf = () => new Promise<number>((r) => requestAnimationFrame(r));

/**
 * Applies a staged edit batch to the model in the given mode, resolving when the final content is
 * in place. INSTANT applies once; LIVE applies in frame-batched groups; CINEMATIC reveals the
 * changed text in small character groups. All converge to the same final content.
 */
export async function applyTransaction(
  editor: monaco.editor.ICodeEditor | null,
  model: monaco.editor.ITextModel,
  edits: StagedEdit[],
  mode: PresentationMode,
): Promise<void> {
  model.pushStackElement(); // one undo group: boundary before

  if (mode === 'INSTANT') {
    model.pushEditOperations([], toMonacoEdits(edits), () => null);
    model.pushStackElement();
    return;
  }

  // LIVE and CINEMATIC apply the same net edits, but insertion text is streamed in pieces so the
  // change is visible. We compute the final edits once, then apply them progressively without undo
  // stops. Edits are applied last-to-first (by start offset) so earlier ranges stay valid.
  const ordered = [...edits].sort((a, b) =>
    b.startLine - a.startLine || b.startColumn - a.startColumn);
  const groupSize = mode === 'CINEMATIC' ? 3 : 256; // chars revealed per frame

  for (const edit of ordered) {
    if (edit.text.length <= groupSize) {
      model.pushEditOperations([], toMonacoEdits([edit]), () => null);
      if (editor) editor.revealLineInCenterIfOutsideViewport(edit.startLine);
      await raf();
      continue;
    }
    // First delete the target range, then stream the replacement text in chunks at the start.
    model.pushEditOperations([], toMonacoEdits([{ ...edit, text: '' }]), () => null);
    let line = edit.startLine;
    let column = edit.startColumn;
    for (let i = 0; i < edit.text.length; i += groupSize) {
      const piece = edit.text.slice(i, i + groupSize);
      model.pushEditOperations([], [{
        range: new monaco.Range(line, column, line, column),
        text: piece,
        forceMoveMarkers: true,
      }], () => null);
      // Advance the insertion cursor past what we just inserted.
      const lines = piece.split('\n');
      if (lines.length > 1) {
        line += lines.length - 1;
        column = lines[lines.length - 1].length + 1;
      } else {
        column += piece.length;
      }
      if (editor) {
        editor.setPosition({ lineNumber: line, column });
        editor.revealPositionInCenterIfOutsideViewport({ lineNumber: line, column });
      }
      await raf();
    }
  }

  model.pushStackElement(); // one undo group: boundary after
}
