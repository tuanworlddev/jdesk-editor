import { useEffect, useState, useCallback } from 'react';
import { mdiSourceBranch, mdiRefresh, mdiSourceCommit } from '@mdi/js';
import { commands } from '../ipc';
import { useStore } from '../store';
import { Icon } from './Icon';
import { fileIcon } from './fileIcons';

interface FileStatus { relPath: string; index: string; worktree: string; untracked: boolean }
interface GitStatus { available: boolean; branch: string; ahead: number; behind: number; files: FileStatus[] }

// A one-letter status badge + color, VS Code style.
function badge(f: FileStatus): { letter: string; color: string } {
  if (f.untracked) return { letter: 'U', color: '#50fa7b' };
  const w = f.worktree;
  if (w === 'M') return { letter: 'M', color: '#f1fa8c' };
  if (w === 'D') return { letter: 'D', color: '#ff5555' };
  if (w === 'A') return { letter: 'A', color: '#50fa7b' };
  if (f.index !== '.') return { letter: 'S', color: '#8be9fd' };
  return { letter: 'M', color: '#f1fa8c' };
}

/** Source Control panel (spec §19): branch, changed files, click to open a diff against HEAD. */
export function SourceControlPanel() {
  const [status, setStatus] = useState<GitStatus | null>(null);
  const openDiff = useStore((s) => s.openDiff);

  const refresh = useCallback(async () => {
    try { setStatus(await commands.git.status() as GitStatus); } catch { setStatus(null); }
  }, []);

  useEffect(() => { void refresh(); const t = window.setInterval(refresh, 4000); return () => window.clearInterval(t); }, [refresh]);

  if (!status?.available) {
    return (
      <div className="flex h-full flex-col">
        <div className="flex items-center gap-2 px-3 py-2.5 text-[11px] font-semibold uppercase tracking-wider text-[var(--color-fg-dim)]">
          <Icon path={mdiSourceBranch} size={15} /> Source Control
        </div>
        <div className="px-4 py-3 text-[12px] text-[var(--color-fg-dim)]">Not a Git repository.</div>
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex items-center gap-2 px-3 py-2.5 text-[11px] font-semibold uppercase tracking-wider text-[var(--color-fg-dim)]">
        <Icon path={mdiSourceBranch} size={15} /> Source Control
        <button title="Refresh" onClick={refresh} className="ml-auto text-[var(--color-fg-dim)] hover:text-[var(--color-fg)]">
          <Icon path={mdiRefresh} size={15} />
        </button>
      </div>
      <div className="flex items-center gap-2 px-3 pb-2 text-[12px]">
        <Icon path={mdiSourceBranch} size={14} className="text-[var(--color-green)]" />
        <span className="text-[var(--color-fg)]">{status.branch || 'detached'}</span>
        {(status.ahead > 0 || status.behind > 0) && (
          <span className="text-[var(--color-fg-dim)]">↑{status.ahead} ↓{status.behind}</span>
        )}
        <button title="Commit (via terminal in v1)" disabled
          className="ml-auto flex items-center gap-1 rounded px-1.5 py-0.5 text-[11px] text-[var(--color-fg-dim)] opacity-50">
          <Icon path={mdiSourceCommit} size={14} /> Commit
        </button>
      </div>
      <div className="px-3 pb-1 text-[11px] font-semibold uppercase tracking-wider text-[var(--color-fg-dim)]">
        Changes {status.files.length > 0 && <span className="rounded bg-[var(--color-selection)] px-1.5">{status.files.length}</span>}
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto">
        {status.files.length === 0 && <div className="px-4 py-2 text-[12px] text-[var(--color-fg-dim)]">No changes.</div>}
        {status.files.map((f) => {
          const b = badge(f);
          const name = f.relPath.split('/').pop() ?? f.relPath;
          const dir = f.relPath.includes('/') ? f.relPath.slice(0, f.relPath.lastIndexOf('/')) : '';
          const icon = fileIcon(name);
          return (
            <div key={f.relPath}
              className="group flex h-[24px] cursor-pointer items-center gap-2 px-3 hover:bg-[var(--color-selection)]"
              onClick={() => openDiff(f.relPath)} title={`Open diff: ${f.relPath}`}>
              <span style={{ color: icon.color }}><Icon path={icon.path} size={15} /></span>
              <span className="truncate text-[12.5px] text-[var(--color-fg)]">{name}</span>
              {dir && <span className="truncate text-[11px] text-[var(--color-fg-dim)]">{dir}</span>}
              <span className="ml-auto font-mono text-[12px] font-bold" style={{ color: b.color }}>{b.letter}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
