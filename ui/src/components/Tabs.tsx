import { mdiClose, mdiCircle } from '@mdi/js';
import { useStore } from '../store';
import { Icon } from './Icon';
import { fileIcon } from './fileIcons';

export function Tabs() {
  const tabs = useStore((s) => s.tabs);
  const activeUri = useStore((s) => s.activeUri);
  const setActive = useStore((s) => s.setActive);
  const closeTab = useStore((s) => s.closeTab);

  if (tabs.length === 0) return <div className="h-9 bg-[var(--color-bg)]" />;
  return (
    <div className="flex h-9 overflow-x-auto border-b border-[var(--color-border)] bg-[var(--color-bg-elev)]"
      role="tablist">
      {tabs.map((tab) => {
        const active = tab.uri === activeUri;
        const name = tab.relPath.split('/').pop() ?? tab.relPath;
        const icon = fileIcon(name);
        return (
          <div key={tab.uri}
            className={`group flex h-9 max-w-[220px] cursor-pointer items-center gap-2 border-r
              border-[var(--color-border)] px-3 text-[12.5px]
              ${active ? 'bg-[var(--color-bg)] text-[var(--color-fg-bright)] border-t-2 border-t-[var(--color-accent)]'
                : 'text-[var(--color-fg-dim)] hover:text-[var(--color-fg)]'}`}
            data-semantic-id={`tab:${tab.relPath}`}
            role="tab" aria-selected={active}
            onClick={() => setActive(tab.uri)}>
            <span style={{ color: icon.color }}><Icon path={icon.path} size={15} /></span>
            <span className="truncate">{name}</span>
            <button
              className="flex h-4 w-4 items-center justify-center rounded hover:bg-[#333a44]"
              title={tab.dirty ? 'Unsaved changes' : 'Close'}
              onClick={(e) => { e.stopPropagation(); closeTab(tab.uri); }}>
              <Icon path={tab.dirty ? mdiCircle : mdiClose} size={tab.dirty ? 9 : 13} />
            </button>
          </div>
        );
      })}
    </div>
  );
}
