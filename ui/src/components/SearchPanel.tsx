import { useState } from 'react';
import { mdiMagnify, mdiRegex, mdiFormatLetterCase, mdiChevronDown, mdiChevronRight, mdiLoading } from '@mdi/js';
import { commands } from '../ipc';
import { useStore } from '../store';
import { Icon } from './Icon';
import { fileIcon } from './fileIcons';

interface Match { line: number; column: number; preview: string }
interface FileMatches { relPath: string; matches: Match[] }

/** Full workspace search (spec §8.4): text or regex, case sensitivity, results grouped by file. */
export function SearchPanel() {
  const [query, setQuery] = useState('');
  const [regex, setRegex] = useState(false);
  const [caseSensitive, setCaseSensitive] = useState(false);
  const [files, setFiles] = useState<FileMatches[]>([]);
  const [total, setTotal] = useState(0);
  const [busy, setBusy] = useState(false);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const openFileAt = useStore((s) => s.openFileAt);

  async function run(text: string) {
    if (!text.trim()) { setFiles([]); setTotal(0); return; }
    setBusy(true);
    try {
      const res = await commands.search.run({ text, regex, caseSensitive, maxResults: 2000 });
      setFiles(res.files);
      setTotal(res.totalMatches);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex items-center gap-2 px-3 py-2.5 text-[11px] font-semibold uppercase
        tracking-wider text-[--color-fg-dim]">
        <Icon path={mdiMagnify} size={15} /> Search
      </div>
      <div className="px-2">
        <div className="flex items-center rounded-md border border-[--color-border] bg-[--color-bg]
          focus-within:border-[--color-accent-dim]">
          <input
            className="min-w-0 flex-1 bg-transparent px-2.5 py-1.5 text-[12.5px] text-[--color-fg] outline-none"
            placeholder="Search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') run(query); }}
          />
          <button title="Match Case" onClick={() => setCaseSensitive((v) => !v)}
            className={`m-0.5 flex h-6 w-6 items-center justify-center rounded
              ${caseSensitive ? 'bg-[--color-accent-dim] text-[--color-fg-bright]' : 'text-[--color-fg-dim] hover:bg-[--color-bg-elev]'}`}>
            <Icon path={mdiFormatLetterCase} size={16} />
          </button>
          <button title="Use Regular Expression" onClick={() => setRegex((v) => !v)}
            className={`m-0.5 flex h-6 w-6 items-center justify-center rounded
              ${regex ? 'bg-[--color-accent-dim] text-[--color-fg-bright]' : 'text-[--color-fg-dim] hover:bg-[--color-bg-elev]'}`}>
            <Icon path={mdiRegex} size={16} />
          </button>
        </div>
        <div className="flex items-center gap-2 px-1 py-1.5 text-[11px] text-[--color-fg-dim]">
          {busy ? <><Icon path={mdiLoading} size={13} className="animate-spin" /> searching…</>
            : total > 0 ? <span>{total} results in {files.length} files</span>
            : query ? <span>No results</span> : <span>Enter a query and press Enter</span>}
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {files.map((f) => {
          const isCollapsed = collapsed.has(f.relPath);
          const name = f.relPath.split('/').pop() ?? f.relPath;
          const icon = fileIcon(name);
          return (
            <div key={f.relPath}>
              <div className="flex h-6 cursor-pointer items-center gap-1 px-2 hover:bg-[--color-bg-elev]"
                onClick={() => setCollapsed((c) => { const n = new Set(c); n.has(f.relPath) ? n.delete(f.relPath) : n.add(f.relPath); return n; })}>
                <Icon path={isCollapsed ? mdiChevronRight : mdiChevronDown} size={14} className="text-[--color-fg-dim]" />
                <span style={{ color: icon.color }}><Icon path={icon.path} size={14} /></span>
                <span className="truncate text-[12.5px] text-[--color-fg]">{name}</span>
                <span className="ml-auto rounded bg-[--color-selection] px-1.5 text-[10px] text-[--color-fg-dim]">{f.matches.length}</span>
              </div>
              {!isCollapsed && f.matches.map((m, i) => (
                <div key={i}
                  className="flex h-[22px] cursor-pointer items-center gap-2 pl-8 pr-2 text-[12px]
                    text-[--color-fg-dim] hover:bg-[--color-selection] hover:text-[--color-fg]"
                  onClick={() => openFileAt(f.relPath, m.line, m.column)}>
                  <span className="w-8 shrink-0 text-right text-[--color-accent]">{m.line}</span>
                  <span className="truncate font-mono">{m.preview}</span>
                </div>
              ))}
            </div>
          );
        })}
      </div>
    </div>
  );
}
