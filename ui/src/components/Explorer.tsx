import { mdiChevronDown, mdiChevronRight, mdiFolder, mdiFolderOpen } from '@mdi/js';
import { useStore } from '../store';
import type { FsEntry } from '../ipc';
import { Icon } from './Icon';
import { fileIcon } from './fileIcons';

/**
 * Workspace file tree with Material Design icons. Children load lazily on expand; every row carries
 * its workspace-relative path as a stable semantic id (the semantic registry consumes these).
 */
export function Explorer() {
  const rootEntries = useStore((s) => s.rootEntries);
  const workspace = useStore((s) => s.workspace);

  if (!workspace) {
    return (
      <div className="px-4 py-6 text-[12px] text-[--color-fg-dim] leading-relaxed">
        No folder opened.
        <br />
        Use <span className="text-[--color-accent]">File ▸ Open Folder</span>, drag a folder onto the
        window, or press <kbd className="rounded bg-[--color-bg-elev] px-1">⌘O</kbd>.
      </div>
    );
  }
  return (
    <div className="flex-1 overflow-y-auto pb-2" role="tree" aria-label="Explorer">
      {rootEntries.map((entry) => (
        <TreeNode key={entry.relPath} entry={entry} depth={0} />
      ))}
    </div>
  );
}

function TreeNode({ entry, depth }: { entry: FsEntry; depth: number }) {
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

  const onActivate = () => (entry.directory ? toggleDir(entry.relPath) : openFile(entry.relPath));

  return (
    <>
      <div
        className={`group flex h-[24px] cursor-pointer select-none items-center gap-1 pr-2 text-[13px]
          ${isActive ? 'bg-[--color-selection] text-[--color-fg-bright]' : 'hover:bg-[--color-bg-elev]'}`}
        data-semantic-id={semanticId}
        role="treeitem"
        aria-expanded={entry.directory ? expanded : undefined}
        tabIndex={0}
        style={{ paddingLeft: 6 + depth * 14 }}
        onClick={onActivate}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onActivate(); } }}
      >
        <span className="flex w-3 justify-center text-[--color-fg-dim]">
          {entry.directory && (
            <Icon path={expanded ? mdiChevronDown : mdiChevronRight} size={14} />
          )}
        </span>
        <span className="shrink-0" style={{ color: icon.color }}>
          <Icon path={icon.path} size={16} />
        </span>
        <span className="truncate">{entry.name}</span>
      </div>
      {entry.directory && expanded && (childrenByDir[entry.relPath] ?? []).map((child) => (
        <TreeNode key={child.relPath} entry={child} depth={depth + 1} />
      ))}
    </>
  );
}
