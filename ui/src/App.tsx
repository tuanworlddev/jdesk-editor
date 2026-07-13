import { useEffect, useState } from 'react';
import {
  mdiFileTree, mdiMagnify, mdiSourceBranch, mdiRobotOutline, mdiConsoleLine, mdiFolderOpenOutline,
  mdiAlertCircleOutline, mdiClose,
} from '@mdi/js';
import { useStore } from './store';
import { Explorer } from './components/Explorer';
import { SearchPanel } from './components/SearchPanel';
import { SourceControlPanel } from './components/SourceControlPanel';
import { Tabs } from './components/Tabs';
import { EditorPane } from './components/EditorPane';
import { StatusBar } from './components/StatusBar';
import { AgentPanel } from './components/AgentPanel';
import { TerminalPanel } from './components/TerminalPanel';
import { PointerOverlay } from './components/PointerOverlay';
import { QuickInput, type QuickMode, type Command } from './components/QuickInput';
import { Icon } from './components/Icon';

type Activity = 'explorer' | 'search' | 'git';
type BottomTab = 'terminal' | 'problems';

export function App() {
  const workspace = useStore((s) => s.workspace);
  const saveActive = useStore((s) => s.saveActive);
  const [activity, setActivity] = useState<Activity>('explorer');
  const [agentOpen, setAgentOpen] = useState(true);
  const [bottomOpen, setBottomOpen] = useState(false);
  const [bottomTab, setBottomTab] = useState<BottomTab>('terminal');
  const [quick, setQuick] = useState<QuickMode | null>(null);

  const drv = () => (window as any).__editorDriver;
  const commandList: Command[] = [
    { id: 'file.openFolder', label: 'File: Open Folder…', hint: '⌘O', run: () => fire('file.openFolder') },
    { id: 'file.newFile', label: 'File: New File', hint: '⌘N', run: () => fire('file.newFile') },
    { id: 'file.save', label: 'File: Save', hint: '⌘S', run: () => void saveActive() },
    { id: 'edit.undo', label: 'Edit: Undo', hint: '⌘Z', run: () => drv()?.undo?.() },
    { id: 'edit.redo', label: 'Edit: Redo', hint: '⇧⌘Z', run: () => drv()?.redo?.() },
    { id: 'view.explorer', label: 'View: Explorer', run: () => setActivity('explorer') },
    { id: 'view.search', label: 'View: Search', hint: '⇧⌘F', run: () => setActivity('search') },
    { id: 'view.git', label: 'View: Source Control', run: () => setActivity('git') },
    { id: 'view.terminal', label: 'View: Toggle Terminal', hint: '⌃`', run: () => setBottomOpen((v) => !v) },
    { id: 'view.problems', label: 'View: Problems', run: () => { setBottomTab('problems'); setBottomOpen(true); } },
    { id: 'view.agent', label: 'View: Toggle Agent Panel', run: () => setAgentOpen((v) => !v) },
    { id: 'go.quickOpen', label: 'Go to File…', hint: '⌘P', run: () => setQuick('files') },
  ];

  function fire(actionId: string) {
    window.dispatchEvent(new CustomEvent('jdesk:menu', { detail: { actionId } }));
  }

  // Menu actions from the native menu bar → run the same UI command.
  useEffect(() => {
    const handlers: Record<string, () => void> = {
      'file.save': () => void saveActive(),
      'file.saveAll': () => void saveActive(),
      'edit.undo': () => drv()?.undo?.(),
      'edit.redo': () => drv()?.redo?.(),
      'view.terminal': () => setBottomOpen((v) => !v),
      'view.agent': () => setAgentOpen((v) => !v),
      'view.explorer': () => setActivity('explorer'),
    };
    const onMenu = (e: Event) => {
      const id = (e as CustomEvent<{ actionId: string }>).detail?.actionId;
      if (id && handlers[id]) handlers[id]();
    };
    window.addEventListener('jdesk:menu', onMenu as EventListener);
    return () => window.removeEventListener('jdesk:menu', onMenu as EventListener);
  }, [saveActive]);

  // Keyboard shortcuts (VS Code style).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const mod = e.metaKey || e.ctrlKey;
      if (mod && e.shiftKey && e.key.toLowerCase() === 'p') { e.preventDefault(); setQuick('commands'); }
      else if (mod && !e.shiftKey && e.key.toLowerCase() === 'p') { e.preventDefault(); setQuick('files'); }
      else if (mod && e.shiftKey && e.key.toLowerCase() === 'f') { e.preventDefault(); setActivity('search'); }
      else if (mod && e.key.toLowerCase() === 'b') { e.preventDefault(); setActivity((a) => a); }
      else if (e.ctrlKey && e.key === '`') { e.preventDefault(); setBottomOpen((v) => !v); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const activityItems: { id: Activity | 'agent' | 'terminal'; icon: string; label: string }[] = [
    { id: 'explorer', icon: mdiFileTree, label: 'Explorer' },
    { id: 'search', icon: mdiMagnify, label: 'Search' },
    { id: 'git', icon: mdiSourceBranch, label: 'Source Control' },
    { id: 'agent', icon: mdiRobotOutline, label: 'Agent' },
    { id: 'terminal', icon: mdiConsoleLine, label: 'Terminal' },
  ];

  return (
    <div className="grid h-screen" style={{
      gridTemplateColumns: `52px 260px 1fr ${agentOpen ? '340px' : '0px'}`,
      gridTemplateRows: '1fr 24px',
      gridTemplateAreas: `'activity sidebar editor agent' 'status status status status'`,
    }}>
      <nav className="flex flex-col items-center gap-1 bg-[var(--color-bg-activity)] pt-2"
        style={{ gridArea: 'activity' }}>
        {activityItems.map((item) => {
          const active = (item.id === 'agent' && agentOpen) || (item.id === 'terminal' && bottomOpen)
            || item.id === activity;
          return (
            <button key={item.id} title={item.label} aria-label={item.label}
              onClick={() => {
                if (item.id === 'agent') setAgentOpen((v) => !v);
                else if (item.id === 'terminal') { setBottomTab('terminal'); setBottomOpen((v) => !v); }
                else setActivity(item.id);
              }}
              className={`relative flex h-11 w-11 items-center justify-center rounded-[10px] transition-colors
                ${active ? 'text-[var(--color-accent)]' : 'text-[var(--color-fg-dim)] hover:bg-[var(--color-bg-elev)] hover:text-[var(--color-fg)]'}`}>
              {active && <span className="absolute left-0 h-7 w-[2px] rounded-r bg-[var(--color-accent)]" />}
              <Icon path={item.icon} size={22} />
            </button>
          );
        })}
      </nav>

      <aside className="flex flex-col overflow-hidden bg-[var(--color-bg-sidebar)]"
        style={{ gridArea: 'sidebar' }}>
        {activity === 'explorer' && (
          <>
            <div className="flex items-center justify-between px-3 pt-2">
              <span className="truncate text-[11px] font-semibold uppercase tracking-wider text-[var(--color-fg-dim)]">
                {workspace ? 'Explorer' : 'No Folder'}
              </span>
              <button title="Open Folder" onClick={() => fire('file.openFolder')}
                className="text-[var(--color-fg-dim)] hover:text-[var(--color-fg)]">
                <Icon path={mdiFolderOpenOutline} size={16} />
              </button>
            </div>
            <Explorer />
          </>
        )}
        {activity === 'search' && <SearchPanel />}
        {activity === 'git' && <SourceControlPanel />}
      </aside>

      <main className="flex min-w-0 flex-col bg-[var(--color-bg)]" style={{ gridArea: 'editor' }}>
        <Tabs />
        <div className="relative flex min-h-0 flex-1 flex-col">
          <div className="min-h-0 flex-1"><EditorPane /></div>
          {bottomOpen && (
            <div className="flex h-[240px] flex-col border-t border-[var(--color-border)] bg-[var(--color-panel)]">
              <div className="flex items-center gap-1 border-b border-[var(--color-border)] px-2">
                <BottomTabBtn label="Terminal" icon={mdiConsoleLine} active={bottomTab === 'terminal'} onClick={() => setBottomTab('terminal')} />
                <BottomTabBtn label="Problems" icon={mdiAlertCircleOutline} active={bottomTab === 'problems'} onClick={() => setBottomTab('problems')} />
                <button title="Close panel" onClick={() => setBottomOpen(false)}
                  className="ml-auto flex h-6 w-6 items-center justify-center rounded text-[var(--color-fg-dim)] hover:bg-[var(--color-bg-elev)]">
                  <Icon path={mdiClose} size={15} />
                </button>
              </div>
              <div className="min-h-0 flex-1" style={{ display: bottomTab === 'terminal' ? 'block' : 'none' }}>
                <TerminalPanel visible={bottomOpen && bottomTab === 'terminal'} />
              </div>
              {bottomTab === 'problems' && (
                <div className="flex min-h-0 flex-1 items-center justify-center text-[12px] text-[var(--color-fg-dim)]">
                  No problems detected in the workspace.
                </div>
              )}
            </div>
          )}
        </div>
      </main>

      {agentOpen && (
        <aside className="overflow-hidden" style={{ gridArea: 'agent' }}>
          <AgentPanel />
        </aside>
      )}

      <div style={{ gridArea: 'status' }}><StatusBar /></div>
      <PointerOverlay />
      {quick && <QuickInput mode={quick} cmds={commandList} onClose={() => setQuick(null)} />}
    </div>
  );
}

function BottomTabBtn({ label, icon, active, onClick }:
  { label: string; icon: string; active: boolean; onClick: () => void }) {
  return (
    <button onClick={onClick}
      className={`flex items-center gap-1.5 border-b-2 px-3 py-1.5 text-[11px] font-semibold uppercase tracking-wider
        ${active ? 'border-[var(--color-accent)] text-[var(--color-fg)]' : 'border-transparent text-[var(--color-fg-dim)] hover:text-[var(--color-fg)]'}`}>
      <Icon path={icon} size={14} /> {label}
    </button>
  );
}
