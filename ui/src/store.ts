import { create } from 'zustand';
import { commands, type FsEntry, type WorkspaceState } from './ipc';
import { ensureModel, disposeModel, flushNow, javaVersion, markSaved, isDirty } from './models';

export interface Tab {
  uri: string;
  relPath: string;
  dirty: boolean;
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
  openFile(relPath: string): Promise<void>;
  setActive(uri: string): void;
  closeTab(uri: string): Promise<void>;
  saveActive(): Promise<void>;
  createFile(relPath: string): Promise<void>;
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

  async openFile(relPath: string) {
    const snapshot = await commands.doc.open({ relPath });
    ensureModel(snapshot.uri, snapshot.relPath, snapshot.content, snapshot.version);
    set((s) => {
      const exists = s.tabs.some((t) => t.uri === snapshot.uri);
      const tabs = exists ? s.tabs
        : [...s.tabs, { uri: snapshot.uri, relPath: snapshot.relPath, dirty: false }];
      return { tabs, activeUri: snapshot.uri, statusMessage: snapshot.relPath };
    });
  },

  setActive(uri: string) {
    set({ activeUri: uri });
  },

  async closeTab(uri: string) {
    const tab = get().tabs.find((t) => t.uri === uri);
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
    await get().refreshWorkspace();
    await get().openFile(relPath);
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
