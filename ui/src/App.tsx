import { useEffect, useState } from 'react';
import {
  mdiFileTree, mdiMagnify, mdiSourceBranch, mdiRobotOutline, mdiConsoleLine, mdiFolderOpenOutline,
} from '@mdi/js';
import { useStore } from './store';
import { Explorer } from './components/Explorer';
import { Tabs } from './components/Tabs';
import { EditorPane } from './components/EditorPane';
import { StatusBar } from './components/StatusBar';
import { AgentPanel } from './components/AgentPanel';
import { TerminalPanel } from './components/TerminalPanel';
import { PointerOverlay } from './components/PointerOverlay';
import { Icon } from './components/Icon';
import { commands } from './ipc';

type Activity = 'explorer' | 'search' | 'git';

export function App() {
  const workspace = useStore((s) => s.workspace);
  const saveActive = useStore((s) => s.saveActive);
  const [activity, setActivity] = useState<Activity>('explorer');
  const [agentOpen, setAgentOpen] = useState(true);
  const [terminalOpen, setTerminalOpen] = useState(false);

  // Menu actions from the native menu bar are forwarded as events; handle the UI ones here.
  useEffect(() => {
    const handlers: Record<string, () => void> = {
      'file.save': () => void saveActive(),
      'file.saveAll': () => void saveActive(),
      'edit.undo': () => (window as any).__editorDriver?.undo?.(),
      'edit.redo': () => (window as any).__editorDriver?.redo?.(),
      'view.terminal': () => setTerminalOpen((v) => !v),
      'view.agent': () => setAgentOpen((v) => !v),
      'view.explorer': () => setActivity('explorer'),
      'agent.start': () => setAgentOpen(true),
    };
    const onMenu = (e: Event) => {
      const id = (e as CustomEvent<{ actionId: string }>).detail?.actionId;
      if (id && handlers[id]) handlers[id]();
    };
    window.addEventListener('jdesk:menu', onMenu as EventListener);
    return () => window.removeEventListener('jdesk:menu', onMenu as EventListener);
  }, [saveActive]);

  async function openFolder() {
    // The native picker is triggered Java-side via the menu; this button asks for it too.
    window.dispatchEvent(new CustomEvent('jdesk:menu', { detail: { actionId: 'file.openFolder' } }));
    void commands.workspace.getState(); // no-op keeps the binding referenced
  }

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
      {/* Activity bar */}
      <nav className="flex flex-col items-center gap-1 border-r border-[--color-border]
        bg-[--color-bg-activity] pt-2" style={{ gridArea: 'activity' }}>
        {activityItems.map((item) => {
          const active = (item.id === 'agent' && agentOpen) || (item.id === 'terminal' && terminalOpen)
            || item.id === activity;
          return (
            <button key={item.id} title={item.label} aria-label={item.label}
              onClick={() => {
                if (item.id === 'agent') setAgentOpen((v) => !v);
                else if (item.id === 'terminal') setTerminalOpen((v) => !v);
                else setActivity(item.id);
              }}
              className={`relative flex h-11 w-11 items-center justify-center rounded-[10px]
                transition-colors ${active ? 'text-[--color-accent]' : 'text-[--color-fg-dim] hover:bg-[--color-bg-elev] hover:text-[--color-fg]'}`}>
              {active && <span className="absolute left-0 h-7 w-[2px] rounded-r bg-[--color-accent]" />}
              <Icon path={item.icon} size={22} />
            </button>
          );
        })}
      </nav>

      {/* Sidebar */}
      <aside className="flex flex-col overflow-hidden border-r border-[--color-border]
        bg-[--color-bg-sidebar]" style={{ gridArea: 'sidebar' }}>
        <div className="flex items-center justify-between px-3 py-2.5">
          <span className="truncate text-[11px] font-semibold uppercase tracking-wider text-[--color-fg-dim]">
            {workspace ? workspace.rootName : 'No Folder'}
          </span>
          <button title="Open Folder" onClick={openFolder}
            className="text-[--color-fg-dim] hover:text-[--color-fg]">
            <Icon path={mdiFolderOpenOutline} size={16} />
          </button>
        </div>
        {activity === 'explorer' && <Explorer />}
        {activity === 'search' && <div className="px-4 py-3 text-[12px] text-[--color-fg-dim]">Search — use the agent or the terminal (grep) for now.</div>}
        {activity === 'git' && <div className="px-4 py-3 text-[12px] text-[--color-fg-dim]">Source Control — git status/diff via the terminal.</div>}
      </aside>

      {/* Editor area (+ terminal panel) */}
      <main className="flex min-w-0 flex-col bg-[--color-bg]" style={{ gridArea: 'editor' }}>
        <Tabs />
        <div className="relative flex min-h-0 flex-1 flex-col">
          <div className={terminalOpen ? 'min-h-0 flex-1' : 'flex-1'}>
            <EditorPane />
          </div>
          {terminalOpen && (
            <div className="flex h-[220px] flex-col border-t border-[--color-border] bg-[--color-panel]">
              <div className="flex items-center gap-1.5 px-3 py-1 text-[11px] font-semibold uppercase
                tracking-wider text-[--color-fg-dim]">
                <Icon path={mdiConsoleLine} size={14} /> Terminal
              </div>
              <div className="min-h-0 flex-1"><TerminalPanel visible={terminalOpen} /></div>
            </div>
          )}
        </div>
      </main>

      {/* Agent pane */}
      {agentOpen && (
        <aside className="overflow-hidden border-l border-[--color-border]" style={{ gridArea: 'agent' }}>
          <AgentPanel />
        </aside>
      )}

      <div style={{ gridArea: 'status' }}><StatusBar /></div>
      <PointerOverlay />
    </div>
  );
}
