import { mdiChevronRight } from '@mdi/js';
import { Icon } from './Icon';
import { fileIcon } from './fileIcons';
import { useStore } from '../store';

/** Path breadcrumbs above the editor (VS Code style). */
export function Breadcrumbs({ relPath }: { relPath: string }) {
  const workspace = useStore((s) => s.workspace);
  const parts = relPath.split('/');
  const name = parts[parts.length - 1];
  const icon = fileIcon(name);
  return (
    <div className="flex h-[26px] shrink-0 items-center gap-1 border-b border-[var(--color-border)]
      bg-[var(--color-bg)] px-3 text-[11.5px] text-[var(--color-fg-dim)]">
      <span className="text-[var(--color-fg-dim)]">{workspace?.rootName}</span>
      {parts.map((p, i) => (
        <span key={i} className="flex items-center gap-1">
          <Icon path={mdiChevronRight} size={13} className="opacity-60" />
          {i === parts.length - 1 && <span style={{ color: icon.color }}><Icon path={icon.path} size={13} /></span>}
          <span className={i === parts.length - 1 ? 'text-[var(--color-fg)]' : ''}>{p}</span>
        </span>
      ))}
    </div>
  );
}
