import { useStore } from '../store';

export function Tabs() {
  const tabs = useStore((s) => s.tabs);
  const activeUri = useStore((s) => s.activeUri);
  const setActive = useStore((s) => s.setActive);
  const closeTab = useStore((s) => s.closeTab);

  if (tabs.length === 0) return <div className="tabs empty" />;
  return (
    <div className="tabs" role="tablist">
      {tabs.map((tab) => (
        <div
          key={tab.uri}
          className={`tab${tab.uri === activeUri ? ' active' : ''}`}
          data-semantic-id={`tab:${tab.relPath}`}
          role="tab"
          aria-selected={tab.uri === activeUri}
          onClick={() => setActive(tab.uri)}
        >
          <span className="tab-name">{basename(tab.relPath)}</span>
          <span
            className="tab-close"
            title={tab.dirty ? 'Unsaved changes' : 'Close'}
            onClick={(e) => { e.stopPropagation(); closeTab(tab.uri); }}
          >
            {tab.dirty ? '●' : '×'}
          </span>
        </div>
      ))}
    </div>
  );
}

function basename(relPath: string): string {
  return relPath.split('/').pop() ?? relPath;
}
