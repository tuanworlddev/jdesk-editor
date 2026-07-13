import { useEffect, useRef } from 'react';
import { Icon } from './Icon';

export interface MenuEntry { label?: string; icon?: string; separator?: boolean; danger?: boolean; onClick?: () => void }

/** A positioned right-click context menu. Closes on outside click or Escape. */
export function ContextMenu({ x, y, entries, onClose }:
  { x: number; y: number; entries: MenuEntry[]; onClose: () => void }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const onDown = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) onClose(); };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('mousedown', onDown);
    window.addEventListener('keydown', onKey);
    return () => { window.removeEventListener('mousedown', onDown); window.removeEventListener('keydown', onKey); };
  }, [onClose]);

  return (
    <div ref={ref} className="fixed z-[10001] min-w-[200px] overflow-hidden rounded-md border
      border-[var(--color-border)] bg-[var(--color-panel)] py-1 shadow-2xl"
      style={{ left: Math.min(x, window.innerWidth - 220), top: Math.min(y, window.innerHeight - entries.length * 28 - 10) }}>
      {entries.map((e, i) => e.separator ? (
        <div key={i} className="my-1 border-t border-[var(--color-border)]" />
      ) : (
        <div key={i}
          className={`flex cursor-pointer items-center gap-2 px-3 py-1 text-[12.5px]
            ${e.danger ? 'text-[var(--color-danger)]' : 'text-[var(--color-fg)]'} hover:bg-[var(--color-selection)]`}
          onClick={() => { e.onClick?.(); onClose(); }}>
          {e.icon ? <Icon path={e.icon} size={14} /> : <span className="w-3.5" />}
          {e.label}
        </div>
      ))}
    </div>
  );
}
