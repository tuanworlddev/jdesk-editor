import { useStore } from '../store';
import type { FsEntry } from '../ipc';

/**
 * Workspace file tree. Children load lazily on expand; every row carries its workspace-relative
 * path as a stable semantic id (the phase-2 semantic registry will consume these same ids).
 */
export function Explorer() {
  const rootEntries = useStore((s) => s.rootEntries);
  const workspace = useStore((s) => s.workspace);

  if (!workspace) {
    return <div className="explorer-empty">Open a folder to begin.</div>;
  }
  return (
    <div className="explorer" role="tree" aria-label="Explorer">
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

  const onActivate = () => {
    if (entry.directory) toggleDir(entry.relPath);
    else openFile(entry.relPath);
  };

  return (
    <>
      <div
        className={`tree-row${isActive ? ' active' : ''}`}
        data-semantic-id={semanticId}
        role="treeitem"
        aria-expanded={entry.directory ? expanded : undefined}
        tabIndex={0}
        style={{ paddingLeft: 8 + depth * 14 }}
        onClick={onActivate}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onActivate(); } }}
      >
        <span className="twisty">
          {entry.directory ? (expanded ? '▾' : '▸') : ''}
        </span>
        <span className="tree-icon">{entry.directory ? '📁' : fileGlyph(entry.name)}</span>
        <span className="tree-name">{entry.name}</span>
      </div>
      {entry.directory && expanded && (childrenByDir[entry.relPath] ?? []).map((child) => (
        <TreeNode key={child.relPath} entry={child} depth={depth + 1} />
      ))}
    </>
  );
}

function fileGlyph(name: string): string {
  const ext = name.split('.').pop()?.toLowerCase() ?? '';
  if (['ts', 'tsx', 'js', 'jsx', 'mjs'].includes(ext)) return '📜';
  if (ext === 'json') return '🧩';
  if (ext === 'md') return '📝';
  if (['java', 'py', 'go', 'rs', 'kt'].includes(ext)) return '📄';
  return '📄';
}
