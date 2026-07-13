import { useState } from 'react';
import {
  mdiChevronDown, mdiChevronRight, mdiFolder, mdiFolderOpen, mdiFilePlusOutline,
  mdiFolderPlusOutline, mdiRefresh, mdiUnfoldLessHorizontal, mdiPencilOutline, mdiTrashCanOutline,
  mdiFileDocumentOutline,
} from '@mdi/js';
import { useStore } from '../store';
import type { FsEntry } from '../ipc';
import { Icon } from './Icon';
import { fileIcon } from './fileIcons';
import { ContextMenu, type MenuEntry } from './ContextMenu';

// A pending inline edit: create a new file/folder under a dir, or rename an existing entry.
type Editing =
  | { type: 'new'; dir: string; kind: 'file' | 'folder' }
  | { type: 'rename'; relPath: string; name: string; isDir: boolean }
  | null;

/** Full VS Code-style Explorer: toolbar, lazy tree, right-click menu, inline create/rename. */
export function Explorer() {
  const rootEntries = useStore((s) => s.rootEntries);
  const workspace = useStore((s) => s.workspace);
  const createFile = useStore((s) => s.createFile);
  const createFolder = useStore((s) => s.createFolder);
  const renameEntry = useStore((s) => s.renameEntry);
  const deleteEntry = useStore((s) => s.deleteEntry);
  const refreshWorkspace = useStore((s) => s.refreshWorkspace);
  const collapseAll = useStore((s) => s.collapseAll);
  const [editing, setEditing] = useState<Editing>(null);
  const [menu, setMenu] = useState<{ x: number; y: number; entries: MenuEntry[] } | null>(null);

  async function commitEdit(value: string) {
    const name = value.trim();
    const e = editing;
    setEditing(null);
    if (!name || !e) return;
    if (e.type === 'new') {
      const rel = e.dir ? `${e.dir}/${name}` : name;
      if (e.kind === 'file') await createFile(rel); else await createFolder(rel);
    } else if (e.type === 'rename') {
      const parent = e.relPath.includes('/') ? e.relPath.slice(0, e.relPath.lastIndexOf('/')) : '';
      const to = parent ? `${parent}/${name}` : name;
      if (to !== e.relPath) await renameEntry(e.relPath, to);
    }
  }

  function rowMenu(entry: FsEntry, ev: React.MouseEvent) {
    ev.preventDefault();
    const dirForNew = entry.directory ? entry.relPath
      : (entry.relPath.includes('/') ? entry.relPath.slice(0, entry.relPath.lastIndexOf('/')) : '');
    setMenu({ x: ev.clientX, y: ev.clientY, entries: [
      { label: 'New File', icon: mdiFilePlusOutline, onClick: () => setEditing({ type: 'new', dir: dirForNew, kind: 'file' }) },
      { label: 'New Folder', icon: mdiFolderPlusOutline, onClick: () => setEditing({ type: 'new', dir: dirForNew, kind: 'folder' }) },
      { separator: true },
      { label: 'Rename', icon: mdiPencilOutline, onClick: () => setEditing({ type: 'rename', relPath: entry.relPath, name: entry.name, isDir: entry.directory }) },
      { label: 'Delete', icon: mdiTrashCanOutline, danger: true,
        onClick: () => void deleteEntry(entry.relPath, entry.directory) },
    ] });
  }

  return (
    <div className="flex h-full min-h-0 flex-col" onContextMenu={(e) => e.preventDefault()}>
      <div className="flex items-center px-3 py-2 text-[11px] font-semibold uppercase tracking-wider text-[--color-fg-dim]">
        <span className="truncate">{workspace ? workspace.rootName : 'Explorer'}</span>
        <div className="ml-auto flex items-center gap-0.5">
          <ToolBtn title="New File" icon={mdiFilePlusOutline} onClick={() => setEditing({ type: 'new', dir: '', kind: 'file' })} />
          <ToolBtn title="New Folder" icon={mdiFolderPlusOutline} onClick={() => setEditing({ type: 'new', dir: '', kind: 'folder' })} />
          <ToolBtn title="Refresh" icon={mdiRefresh} onClick={() => refreshWorkspace()} />
          <ToolBtn title="Collapse All" icon={mdiUnfoldLessHorizontal} onClick={() => collapseAll()} />
        </div>
      </div>

      {!workspace ? (
        <div className="px-4 py-3 text-[12px] leading-relaxed text-[--color-fg-dim]">
          No folder opened. <span className="text-[--color-accent]">File ▸ Open Folder</span> or ⌘O.
        </div>
      ) : (
        <div className="min-h-0 flex-1 overflow-y-auto pb-2" role="tree" aria-label="Explorer">
          {editing?.type === 'new' && editing.dir === '' && (
            <InlineInput kind={editing.kind} depth={0} onCommit={commitEdit} onCancel={() => setEditing(null)} />
          )}
          {rootEntries.map((entry) => (
            <TreeNode key={entry.relPath} entry={entry} depth={0}
              editing={editing} setEditing={setEditing} onMenu={rowMenu} onCommit={commitEdit} />
          ))}
        </div>
      )}

      {menu && <ContextMenu x={menu.x} y={menu.y} entries={menu.entries} onClose={() => setMenu(null)} />}
    </div>
  );
}

function ToolBtn({ title, icon, onClick }: { title: string; icon: string; onClick: () => void }) {
  return (
    <button title={title} onClick={onClick}
      className="flex h-6 w-6 items-center justify-center rounded text-[--color-fg-dim] hover:bg-[--color-bg-elev] hover:text-[--color-fg]">
      <Icon path={icon} size={15} />
    </button>
  );
}

function TreeNode({ entry, depth, editing, setEditing, onMenu, onCommit }: {
  entry: FsEntry; depth: number; editing: Editing;
  setEditing: (e: Editing) => void; onMenu: (e: FsEntry, ev: React.MouseEvent) => void;
  onCommit: (v: string) => void;
}) {
  const expandedDirs = useStore((s) => s.expandedDirs);
  const childrenByDir = useStore((s) => s.childrenByDir);
  const toggleDir = useStore((s) => s.toggleDir);
  const openFile = useStore((s) => s.openFile);
  const activeUri = useStore((s) => s.activeUri);

  const expanded = expandedDirs.has(entry.relPath);
  const semanticId = entry.directory ? `folder:${entry.relPath}` : `file:${entry.relPath}`;
  const isActive = !!activeUri && activeUri.endsWith('/' + entry.relPath);
  const icon = entry.directory
    ? { path: expanded ? mdiFolderOpen : mdiFolder, color: '#bd93f9' }
    : fileIcon(entry.name);

  if (editing?.type === 'rename' && editing.relPath === entry.relPath) {
    return <InlineInput kind={entry.directory ? 'folder' : 'file'} depth={depth} initial={entry.name}
      onCommit={onCommit} onCancel={() => setEditing(null)} />;
  }

  const onActivate = () => (entry.directory ? toggleDir(entry.relPath) : openFile(entry.relPath));

  return (
    <>
      <div
        className={`group flex h-[24px] cursor-pointer select-none items-center gap-1 pr-2 text-[13px]
          ${isActive ? 'bg-[--color-selection] text-[--color-fg-bright]' : 'hover:bg-[--color-bg-elev]'}`}
        data-semantic-id={semanticId} role="treeitem"
        aria-expanded={entry.directory ? expanded : undefined} tabIndex={0}
        style={{ paddingLeft: 6 + depth * 14 }}
        onClick={onActivate}
        onContextMenu={(e) => onMenu(entry, e)}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onActivate(); } }}>
        <span className="flex w-3 justify-center text-[--color-fg-dim]">
          {entry.directory && <Icon path={expanded ? mdiChevronDown : mdiChevronRight} size={14} />}
        </span>
        <span className="shrink-0" style={{ color: icon.color }}><Icon path={icon.path} size={16} /></span>
        <span className="truncate">{entry.name}</span>
      </div>
      {entry.directory && expanded && editing?.type === 'new' && editing.dir === entry.relPath && (
        <InlineInput kind={editing.kind} depth={depth + 1} onCommit={onCommit} onCancel={() => setEditing(null)} />
      )}
      {entry.directory && expanded && (childrenByDir[entry.relPath] ?? []).map((child) => (
        <TreeNode key={child.relPath} entry={child} depth={depth + 1}
          editing={editing} setEditing={setEditing} onMenu={onMenu} onCommit={onCommit} />
      ))}
    </>
  );
}

function InlineInput({ kind, depth, initial, onCommit, onCancel }: {
  kind: 'file' | 'folder'; depth: number; initial?: string;
  onCommit: (v: string) => void; onCancel: () => void;
}) {
  const [value, setValue] = useState(initial ?? '');
  return (
    <div className="flex h-[24px] items-center gap-1 pr-2" style={{ paddingLeft: 6 + depth * 14 }}>
      <span className="w-3" />
      <Icon path={kind === 'folder' ? mdiFolder : mdiFileDocumentOutline} size={16} className="shrink-0 text-[--color-fg-dim]" />
      <input autoFocus value={value} onChange={(e) => setValue(e.target.value)}
        className="min-w-0 flex-1 rounded border border-[--color-accent-dim] bg-[--color-bg] px-1 text-[13px] text-[--color-fg] outline-none"
        onKeyDown={(e) => { if (e.key === 'Enter') onCommit(value); else if (e.key === 'Escape') onCancel(); }}
        onBlur={() => (value.trim() ? onCommit(value) : onCancel())} />
    </div>
  );
}

