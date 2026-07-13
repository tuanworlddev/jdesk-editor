import { useEffect } from 'react';
import { useStore } from './store';
import { Explorer } from './components/Explorer';
import { Tabs } from './components/Tabs';
import { EditorPane } from './components/EditorPane';
import { StatusBar } from './components/StatusBar';

export function App() {
  const workspace = useStore((s) => s.workspace);
  const saveActive = useStore((s) => s.saveActive);

  // Cmd/Ctrl+S saves the active document.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 's') {
        e.preventDefault();
        saveActive().catch(() => {});
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [saveActive]);

  return (
    <div className="app">
      <div className="activity-bar">
        <div className="activity-item active" title="Explorer" aria-label="Explorer">
          <FilesIcon />
        </div>
        <div className="activity-item" title="Search" aria-label="Search"><SearchIcon /></div>
        <div className="activity-item" title="Source Control" aria-label="Source Control"><GitIcon /></div>
        <div className="activity-item" title="Agent" aria-label="Agent"><AgentIcon /></div>
      </div>
      <aside className="sidebar">
        <div className="sidebar-header">
          <span>{workspace ? workspace.rootName : 'No Folder Opened'}</span>
        </div>
        <Explorer />
      </aside>
      <main className="editor-area">
        <Tabs />
        <EditorPane />
      </main>
      <StatusBar />
    </div>
  );
}

const FilesIcon = () => (
  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6">
    <path d="M13 3H6a1 1 0 0 0-1 1v16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9z" />
    <path d="M13 3v6h6" />
  </svg>
);
const SearchIcon = () => (
  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6">
    <circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" />
  </svg>
);
const GitIcon = () => (
  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6">
    <circle cx="6" cy="6" r="2.5" /><circle cx="6" cy="18" r="2.5" /><circle cx="18" cy="9" r="2.5" />
    <path d="M6 8.5v7M18 11.5c0 4-6 1-6 6" />
  </svg>
);
const AgentIcon = () => (
  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6">
    <rect x="4" y="7" width="16" height="12" rx="2" /><path d="M12 7V4M9 13h.01M15 13h.01" />
  </svg>
);
