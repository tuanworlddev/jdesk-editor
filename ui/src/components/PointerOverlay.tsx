import { mdiCursorDefault } from '@mdi/js';
import { useStore } from '../store';
import { Icon } from './Icon';

/**
 * The agent pointer overlay (spec §10). Rendered above everything with pointer-events disabled; its
 * position is set from geometry computed in the page (never from coordinates sent by the agent). It
 * eases toward each target and carries the agent's name + color, so you can watch the agent work.
 */
export function PointerOverlay() {
  const pointer = useStore((s) => s.pointer);
  if (!pointer.visible) return null;
  return (
    <div className="agent-pointer" style={{ left: pointer.x, top: pointer.y }}>
      <span style={{ color: pointer.color, filter: 'drop-shadow(0 1px 2px rgba(0,0,0,.6))' }}>
        <Icon path={mdiCursorDefault} size={20} />
      </span>
      <span
        className="ml-1 rounded px-1.5 py-0.5 text-[10px] font-semibold text-black"
        style={{ background: pointer.color, transform: 'translateY(-6px)', display: 'inline-block' }}
      >
        {pointer.label}
      </span>
    </div>
  );
}
