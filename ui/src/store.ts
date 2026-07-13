import { create } from 'zustand';
import { commands, type FsEntry, type WorkspaceState } from './ipc';
import { ensureModel, disposeModel, flushNow, javaVersion, markSaved, isDirty, ensureDiffModel, disposeDiffModel } from './models';
import { monaco } from './monaco-setup';

export interface Tab {
  uri: string;
  relPath: string;
  dirty: boolean;
  kind: 'file' | 'diff';
}

interface EditorStore {
  workspace: WorkspaceState | null;
  rootEntries: FsEntry[];
  childrenByDir: Record<string, FsEntry[]>;
  expandedDirs: Set<string>;
  tabs: Tab[];
  activeUri: string | null;
  statusMessage: string;
  pointer: { visible: boolean; x: number; y: number; label: string; color: string };
  pointerEverShown: boolean;

  openWorkspace(path: string): Promise<void>;
  refreshWorkspace(): Promise<void>;
  reloadWorkspace(): Promise<void>;
  toggleDir(relPath: string): Promise<void>;
  collapseAll(): void;
  openFile(relPath: string): Promise<void>;
  openFileAt(relPath: string, line: number, column: number): Promise<void>;
  setActive(uri: string): void;
  closeTab(uri: string): Promise<void>;
  saveActive(): Promise<void>;
  createFile(relPath: string): Promise<void>;
  createFolder(relPath: string): Promise<void>;
  renameEntry(fromRel: string, toRel: string): Promise<void>;
  deleteEntry(relPath: string, isDir: boolean): Promise<void>;
  openDiff(relPath: string): Promise<void>;
  refreshTree(relPath: string): Promise<void>;
  markTabDirty(uri: string, dirty: boolean): void;
  setStatus(message: string): void;
  showPointer(x: number, y: number, label: string, color: string): void;
  hidePointer(): void;
}

export const useStore = create<EditorStore>((set, get) => ({
  workspace: null,
  rootEntries: [],
  childrenByDir: {},
  expandedDirs: new Set(),
  tabs: [],
  activeUri: null,
  statusMessage: 'Ready',
  pointer: { visible: false, x: 0, y: 0, label: '', color: '#bd93f9' },
  pointerEverShown: false,

  async openWorkspace(path: string) {
    const state = await commands.workspace.open({ path });
    set({ workspace: state, rootEntries: state.rootEntries, childrenByDir: {},
      expandedDirs: new Set(), statusMessage: `Opened ${state.rootName}` });
  },

  async refreshWorkspace() {
    const state = await commands.workspace.getState();
    if (state.open) set({ workspace: state, rootEntries: state.rootEntries });
  },

  async reloadWorkspace() {
    // A different folder was opened natively: drop the old tabs/models and load the new tree.
    get().tabs.forEach((t) => disposeModel(t.uri));
    const state = await commands.workspace.getState();
    set({
      workspace: state.open ? state : null,
      rootEntries: state.open ? state.rootEntries : [],
      childrenByDir: {}, expandedDirs: new Set(), tabs: [], activeUri: null,
      statusMessage: state.open ? `Opened ${state.rootName}` : 'Ready',
    });
  },

  async toggleDir(relPath: string) {
    const expanded = new Set(get().expandedDirs);
    if (expanded.has(relPath)) {
      expanded.delete(relPath);
      set({ expandedDirs: expanded });
      return;
    }
    if (!get().childrenByDir[relPath]) {
      const listing = await commands.workspace.children({ relPath });
      set((s) => ({ childrenByDir: { ...s.childrenByDir, [relPath]: listing.entries } }));
    }
    expanded.add(relPath);
    set({ expandedDirs: expanded });
  },

  collapseAll() {
    set({ expandedDirs: new Set() });
  },

  async openFile(relPath: string) {
    const snapshot = await commands.doc.open({ relPath });
    ensureModel(snapshot.uri, snapshot.relPath, snapshot.content, snapshot.version);
    set((s) => {
      const exists = s.tabs.some((t) => t.uri === snapshot.uri);
      const tabs = exists ? s.tabs
        : [...s.tabs, { uri: snapshot.uri, relPath: snapshot.relPath, dirty: false, kind: 'file' as const }];
      return { tabs, activeUri: snapshot.uri, statusMessage: snapshot.relPath };
    });
  },

  async openFileAt(relPath: string, line: number, column: number) {
    await get().openFile(relPath);
    // Let EditorPane mount the model, then reveal the position.
    setTimeout(() => {
      const editor = monaco.editor.getEditors()[0];
      if (editor) {
        editor.setPosition({ lineNumber: line, column });
        editor.revealLineInCenterIfOutsideViewport(line);
        editor.focus();
      }
    }, 90);
  },

  setActive(uri: string) {
    set({ activeUri: uri });
  },

  async closeTab(uri: string) {
    const tab = get().tabs.find((t) => t.uri === uri);
    if (tab?.kind === 'diff') {
      disposeDiffModel(uri);
      set((s) => {
        const tabs = s.tabs.filter((t) => t.uri !== uri);
        return { tabs, activeUri: s.activeUri === uri ? (tabs.at(-1)?.uri ?? null) : s.activeUri };
      });
      return;
    }
    if (tab && isDirty(uri)) {
      // A production close-confirm dialog lands with the persistence work; for now, keep it open.
      set({ statusMessage: `Unsaved changes in ${tab.relPath} — save before closing` });
      return;
    }
    await commands.doc.close({ uri, discardDirty: false }).catch(() => {});
    disposeModel(uri);
    set((s) => {
      const tabs = s.tabs.filter((t) => t.uri !== uri);
      const activeUri = s.activeUri === uri ? (tabs.at(-1)?.uri ?? null) : s.activeUri;
      return { tabs, activeUri };
    });
  },

  async saveActive() {
    const uri = get().activeUri;
    if (!uri) return;
    await flushNow(uri);
    const result = await commands.doc.save({ uri, expectedVersion: javaVersion(uri), force: false });
    markSaved(uri, jsHashFromSaved(uri));
    set({ statusMessage: `Saved (v${result.version})` });
    get().markTabDirty(uri, false);
  },

  async createFile(relPath: string) {
    await commands.workspace.createFile({ relPath });
    await get().refreshTree(parentOf(relPath));
    await get().openFile(relPath);
  },

  async createFolder(relPath: string) {
    await commands.workspace.createFolder({ relPath });
    await get().refreshTree(parentOf(relPath));
  },

  async renameEntry(fromRel: string, toRel: string) {
    await commands.workspace.rename({ fromRelPath: fromRel, toRelPath: toRel });
    await get().refreshTree(parentOf(fromRel));
    await get().refreshTree(parentOf(toRel));
  },

  async deleteEntry(relPath: string, isDir: boolean) {
    await commands.workspace.delete({ relPath, recursive: isDir });
    // Close any tab for the deleted path.
    const gone = get().tabs.filter((t) => t.relPath === relPath);
    for (const t of gone) { disposeModel(t.uri); }
    set((s) => {
      const tabs = s.tabs.filter((t) => t.relPath !== relPath);
      return { tabs, activeUri: s.tabs.some((t) => t.relPath === relPath && t.uri === s.activeUri)
        ? (tabs.at(-1)?.uri ?? null) : s.activeUri };
    });
    await get().refreshTree(parentOf(relPath));
  },

  // Reloads a directory's children so a mutation shows immediately without collapsing the tree.
  async refreshTree(relDir: string) {
    if (relDir === '') {
      const state = await commands.workspace.getState();
      if (state.open) set({ rootEntries: state.rootEntries });
    } else if (get().expandedDirs.has(relDir)) {
      const listing = await commands.workspace.children({ relPath: relDir });
      set((s) => ({ childrenByDir: { ...s.childrenByDir, [relDir]: listing.entries } }));
    }
  },

  async openDiff(relPath: string) {
    const diff = await commands.git.diff({ relPath });
    const diffUri = `diff:${relPath}`;
    ensureDiffModel(diffUri, diff.original, diff.modified, relPath);
    set((s) => {
      const exists = s.tabs.some((t) => t.uri === diffUri);
      const tabs = exists ? s.tabs
        : [...s.tabs, { uri: diffUri, relPath, dirty: false, kind: 'diff' as const }];
      return { tabs, activeUri: diffUri, statusMessage: `Diff: ${relPath}` };
    });
  },

  markTabDirty(uri: string, dirty: boolean) {
    set((s) => ({ tabs: s.tabs.map((t) => (t.uri === uri ? { ...t, dirty } : t)) }));
  },

  setStatus(message: string) {
    set({ statusMessage: message });
  },

  showPointer(x: number, y: number, label: string, color: string) {
    set({ pointer: { visible: true, x, y, label, color }, pointerEverShown: true });
  },

  hidePointer() {
    set((s) => ({ pointer: { ...s.pointer, visible: false } }));
  },
}));

// After a save the on-disk hash equals the current buffer hash, so recompute from the model.
import { jsSha256, getModel } from './models';
function jsHashFromSaved(uri: string): string {
  const model = getModel(uri);
  return model ? jsSha256(model.getValue()) : '';
}

function parentOf(relPath: string): string {
  return relPath.includes('/') ? relPath.slice(0, relPath.lastIndexOf('/')) : '';
}
