import { useStore } from '../store';

export function StatusBar() {
  const statusMessage = useStore((s) => s.statusMessage);
  const activeUri = useStore((s) => s.activeUri);
  const tabs = useStore((s) => s.tabs);
  const active = tabs.find((t) => t.uri === activeUri);

  return (
    <footer className="status-bar">
      <div className="status-left">
        <span className="status-branch">⎇ main</span>
        <span>{active ? active.relPath : 'no file'}</span>
      </div>
      <div className="status-right">
        {active?.dirty && <span className="status-dirty">unsaved</span>}
        <span>{statusMessage}</span>
        <span>UTF-8</span>
        <span>LF</span>
      </div>
    </footer>
  );
}
