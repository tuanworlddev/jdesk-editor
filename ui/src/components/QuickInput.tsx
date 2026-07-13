import { useEffect, useMemo, useRef, useState } from 'react';
import { mdiMagnify, mdiConsoleLine } from '@mdi/js';
import { commands } from '../ipc';
import { useStore } from '../store';
import { Icon } from './Icon';
import { fileIcon } from './fileIcons';

export type QuickMode = 'files' | 'commands';
export interface Command { id: string; label: string; hint?: string; run: () => void }

interface Item { id: string; label: string; sub?: string; icon?: { path: string; color: string }; run: () => void }

// Simple subsequence fuzzy score: lower is better; -1 = no match.
function fuzzy(query: string, text: string): number {
  if (!query) return 0;
  const q = query.toLowerCase();
  const t = text.toLowerCase();
  let ti = 0;
  let score = 0;
  let lastHit = -1;
  for (let qi = 0; qi < q.length; qi++) {
    const found = t.indexOf(q[qi], ti);
    if (found < 0) return -1;
    if (lastHit >= 0) score += found - lastHit; // prefer contiguous
    lastHit = found;
    ti = found + 1;
  }
  return score + (t.length - q.length) * 0.01;
}

/** ⌘P (files) / ⌘⇧P (commands) quick-input overlay with fuzzy filtering. */
export function QuickInput({ mode, cmds, onClose }: { mode: QuickMode; cmds: Command[]; onClose: () => void }) {
  const [query, setQuery] = useState(mode === 'commands' ? '' : '');
  const [files, setFiles] = useState<string[]>([]);
  const [sel, setSel] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const openFile = useStore((s) => s.openFile);

  useEffect(() => { inputRef.current?.focus(); }, []);
  useEffect(() => {
    if (mode === 'files') void commands.workspace.allFiles().then((r) => setFiles(r.paths));
  }, [mode]);

  const items: Item[] = useMemo(() => {
    if (mode === 'commands') {
      return cmds
        .map((c) => ({ item: { id: c.id, label: c.label, sub: c.hint, run: c.run }, s: fuzzy(query, c.label) }))
        .filter((x) => x.s >= 0).sort((a, b) => a.s - b.s).map((x) => x.item);
    }
    return files
      .map((p) => ({ p, s: fuzzy(query, p) }))
      .filter((x) => x.s >= 0).sort((a, b) => a.s - b.s).slice(0, 200)
      .map(({ p }) => {
        const name = p.split('/').pop() ?? p;
        return { id: p, label: name, sub: p.includes('/') ? p.slice(0, p.lastIndexOf('/')) : '',
          icon: fileIcon(name), run: () => { void openFile(p); } };
      });
  }, [mode, query, files, cmds, openFile]);

  useEffect(() => { setSel(0); }, [query, mode]);

  function choose(i: number) {
    const item = items[i];
    if (item) { item.run(); onClose(); }
  }

  return (
    <div className="fixed inset-0 z-[10000] flex justify-center bg-black/50 pt-[10vh]" onMouseDown={onClose}>
      <div className="h-fit w-[560px] max-w-[90vw] overflow-hidden rounded-lg border border-[var(--color-accent-dim)]
        bg-[var(--color-bg-elev)] shadow-2xl ring-1 ring-black/40" onMouseDown={(e) => e.stopPropagation()}>
        <div className="flex items-center gap-2 border-b border-[var(--color-border)] px-3 py-2">
          <Icon path={mode === 'commands' ? mdiConsoleLine : mdiMagnify} size={16} className="text-[var(--color-fg-dim)]" />
          <input ref={inputRef} value={query} onChange={(e) => setQuery(e.target.value)}
            placeholder={mode === 'commands' ? 'Type a command…' : 'Search files by name…'}
            className="flex-1 bg-transparent text-[13px] text-[var(--color-fg)] outline-none"
            onKeyDown={(e) => {
              if (e.key === 'ArrowDown') { e.preventDefault(); setSel((s) => Math.min(s + 1, items.length - 1)); }
              else if (e.key === 'ArrowUp') { e.preventDefault(); setSel((s) => Math.max(s - 1, 0)); }
              else if (e.key === 'Enter') { e.preventDefault(); choose(sel); }
              else if (e.key === 'Escape') { onClose(); }
            }} />
        </div>
        <div className="max-h-[50vh] overflow-y-auto py-1">
          {items.length === 0 && <div className="px-3 py-2 text-[12px] text-[var(--color-fg-dim)]">No matches</div>}
          {items.map((item, i) => (
            <div key={item.id}
              className={`flex cursor-pointer items-center gap-2 px-3 py-1.5 text-[12.5px]
                ${i === sel ? 'bg-[var(--color-selection)] text-[var(--color-fg-bright)]' : 'text-[var(--color-fg)] hover:bg-[var(--color-bg-elev)]'}`}
              onMouseEnter={() => setSel(i)} onClick={() => choose(i)}>
              {item.icon && <span style={{ color: item.icon.color }}><Icon path={item.icon.path} size={15} /></span>}
              <span className="truncate">{item.label}</span>
              {item.sub && <span className="ml-auto truncate pl-3 text-[11px] text-[var(--color-fg-dim)]">{item.sub}</span>}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
