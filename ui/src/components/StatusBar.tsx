import { mdiSourceBranch } from '@mdi/js';
import { useStore } from '../store';
import { Icon } from './Icon';

export function StatusBar() {
  const statusMessage = useStore((s) => s.statusMessage);
  const activeUri = useStore((s) => s.activeUri);
  const tabs = useStore((s) => s.tabs);
  const active = tabs.find((t) => t.uri === activeUri);

  return (
    <footer className="flex items-center justify-between border-t border-[var(--color-border)]
      bg-[var(--color-statusbar)] px-3 text-[11.5px] font-medium text-[var(--color-fg)]">
      <div className="flex items-center gap-3">
        <span className="flex items-center gap-1 text-[var(--color-green)]">
          <Icon path={mdiSourceBranch} size={13} /> main
        </span>
        <span className="text-[var(--color-fg-dim)]">{active ? active.relPath : 'no file'}</span>
      </div>
      <div className="flex items-center gap-3.5 text-[var(--color-fg-dim)]">
        {active?.dirty && <span className="font-bold text-[var(--color-yellow)]">unsaved</span>}
        <span className="truncate max-w-[360px]">{statusMessage}</span>
        <span>UTF-8</span>
        <span>LF</span>
      </div>
    </footer>
  );
}
