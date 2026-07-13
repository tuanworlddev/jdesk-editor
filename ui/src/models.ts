// Monaco model + sync-state registry, deliberately OUTSIDE React state (spec §5.2: never store
// each typed character in React). One text model per open URI; a single editor instance mounts
// whichever model is active. Human edits are batched per animation frame and sent to Java as
// TextEdit deltas under optimistic-concurrency versioning.
import { commands, type TextEditDto } from './ipc';
import { monaco, languageForPath } from './monaco-setup';
import { sha256 } from '@noble/hashes/sha2';
import { bytesToHex } from '@noble/hashes/utils';

interface ModelEntry {
  model: monaco.editor.ITextModel;
  relPath: string;
  javaVersion: number;       // last version acknowledged by Java
  pendingEdits: TextEditDto[];
  flushScheduled: boolean;
  inFlight: boolean;
  savedHash: string;         // content hash at last save
  suppress: boolean;         // set while we apply Java-authored content (don't echo back)
}

const entries = new Map<string, ModelEntry>();
type DirtyListener = (uri: string, dirty: boolean) => void;
let dirtyListener: DirtyListener = () => {};

export function onDirtyChange(listener: DirtyListener) {
  dirtyListener = listener;
}

export function jsSha256(text: string): string {
  return bytesToHex(sha256(new TextEncoder().encode(text)));
}

export function hasModel(uri: string): boolean {
  return entries.has(uri);
}

export function getModel(uri: string): monaco.editor.ITextModel | undefined {
  return entries.get(uri)?.model;
}

export function isDirty(uri: string): boolean {
  const e = entries.get(uri);
  return e ? jsSha256(e.model.getValue()) !== e.savedHash : false;
}

/** Creates (or returns) the model for a freshly opened document snapshot. */
export function ensureModel(uri: string, relPath: string, content: string, version: number): monaco.editor.ITextModel {
  const existing = entries.get(uri);
  if (existing) return existing.model;
  const model = monaco.editor.createModel(content, languageForPath(relPath), monaco.Uri.parse(uri));
  const entry: ModelEntry = {
    model, relPath, javaVersion: version, pendingEdits: [], flushScheduled: false,
    inFlight: false, savedHash: jsSha256(content), suppress: false,
  };
  entries.set(uri, entry);
  model.onDidChangeContent((event) => {
    if (entry.suppress) return;
    for (const change of event.changes) {
      entry.pendingEdits.push(rangeToEdit(change));
    }
    scheduleFlush(uri);
    dirtyListener(uri, isDirty(uri));
  });
  return model;
}

export function markSaved(uri: string, hash: string) {
  const e = entries.get(uri);
  if (e) {
    e.savedHash = hash;
    dirtyListener(uri, false);
  }
}

export function disposeModel(uri: string) {
  const e = entries.get(uri);
  if (e) {
    e.model.dispose();
    entries.delete(uri);
  }
}

function rangeToEdit(change: monaco.editor.IModelContentChange): TextEditDto {
  const r = change.range;
  return {
    startLine: r.startLineNumber, startColumn: r.startColumn,
    endLine: r.endLineNumber, endColumn: r.endColumn, text: change.text,
  };
}

function scheduleFlush(uri: string) {
  const entry = entries.get(uri);
  if (!entry || entry.flushScheduled) return;
  entry.flushScheduled = true;
  requestAnimationFrame(() => flush(uri));
}

async function flush(uri: string) {
  const entry = entries.get(uri);
  if (!entry) return;
  entry.flushScheduled = false;
  if (entry.inFlight || entry.pendingEdits.length === 0) return;
  const edits = entry.pendingEdits;
  entry.pendingEdits = [];
  entry.inFlight = true;
  try {
    const ack = await commands.doc.change({
      uri, baseVersion: entry.javaVersion, edits, seq: entry.javaVersion,
    });
    entry.javaVersion = ack.version;
    if (ack.resyncRequired) {
      // Optimistic-concurrency drift: pull the authoritative snapshot and re-seat the model.
      const snapshot = await commands.doc.open({ relPath: entry.relPath });
      entry.suppress = true;
      entry.model.setValue(snapshot.content);
      entry.suppress = false;
      entry.javaVersion = snapshot.version;
    }
  } finally {
    entry.inFlight = false;
    if (entry.pendingEdits.length > 0) scheduleFlush(uri);
  }
}

/** Forces any queued edits for a document to be sent and acknowledged (used before save). */
export async function flushNow(uri: string): Promise<void> {
  const entry = entries.get(uri);
  if (!entry) return;
  while (entry.pendingEdits.length > 0 || entry.inFlight) {
    await flush(uri);
    await new Promise((r) => setTimeout(r, 5));
  }
}

export function javaVersion(uri: string): number {
  return entries.get(uri)?.javaVersion ?? 0;
}

// ---- diff models (git diff view) — separate from editable models ----
interface DiffEntry {
  original: monaco.editor.ITextModel;
  modified: monaco.editor.ITextModel;
  relPath: string;
}
const diffModels = new Map<string, DiffEntry>();

export function ensureDiffModel(diffUri: string, original: string, modified: string, relPath: string): DiffEntry {
  const existing = diffModels.get(diffUri);
  if (existing) {
    existing.original.setValue(original);
    existing.modified.setValue(modified);
    return existing;
  }
  const lang = languageForPath(relPath);
  const entry: DiffEntry = {
    original: monaco.editor.createModel(original, lang),
    modified: monaco.editor.createModel(modified, lang),
    relPath,
  };
  diffModels.set(diffUri, entry);
  return entry;
}

export function getDiffModel(diffUri: string): DiffEntry | undefined {
  return diffModels.get(diffUri);
}

export function disposeDiffModel(diffUri: string) {
  const e = diffModels.get(diffUri);
  if (e) {
    e.original.dispose();
    e.modified.dispose();
    diffModels.delete(diffUri);
  }
}

/**
 * Applies a Java-authoritative content update (an agent edit arriving via MCP) to a live model.
 * Suppresses the change echo so we don't send it back, and re-seats the model to the new content
 * while preserving cursor/selection where the diff allows.
 */
export function applyAuthoritativeContent(uri: string, content: string, version: number) {
  const entry = entries.get(uri);
  if (!entry) return;
  if (entry.model.getValue() === content) {
    entry.javaVersion = version;
    return;
  }
  entry.suppress = true;
  try {
    // Full-range replace via edit op (preserves undo history better than setValue).
    entry.model.pushEditOperations(
      [],
      [{ range: entry.model.getFullModelRange(), text: content }],
      () => null,
    );
  } finally {
    entry.suppress = false;
  }
  entry.javaVersion = version;
  entry.savedHash = jsSha256(content) === entry.savedHash ? entry.savedHash : entry.savedHash;
  dirtyListener(uri, isDirty(uri));
}
