import { useEffect, useRef, useState, type ReactNode } from 'react';
import { mdiSend, mdiStop, mdiToolboxOutline, mdiCheckCircleOutline } from '@mdi/js';
import { commands } from '../ipc';
import { Icon } from './Icon';

interface ToolActivity { name: string }
type Provider = 'claude' | 'codex';

// Original stylized provider marks (brand colours, not the trademarked artwork).
function ClaudeMark({ size = 16 }: { size?: number }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true">
      <path fill="currentColor" d="M12 1.5c.4 4 1.2 6.5 2.7 8 1.5 1.5 4 2.3 7.8 2.7-3.8.4-6.3 1.2-7.8 2.7-1.5
        1.5-2.3 4-2.7 7.8-.4-3.8-1.2-6.3-2.7-7.8-1.5-1.5-4-2.3-7.8-2.7 3.8-.4 6.3-1.2 7.8-2.7C10.8 8 11.6 5.5 12 1.5Z" />
    </svg>
  );
}
function CodexMark({ size = 16 }: { size?: number }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true"
      fill="none" stroke="currentColor" strokeWidth="1.7">
      <path strokeLinejoin="round" d="M12 2.5 20 7v10l-8 4.5L4 17V7l8-4.5Z" />
      <circle cx="12" cy="12" r="3.1" />
    </svg>
  );
}

const PROVIDERS: { id: Provider; label: string; color: string; mark: (p: { size?: number }) => ReactNode }[] = [
  { id: 'claude', label: 'Claude Code', color: '#d97757', mark: ClaudeMark },
  { id: 'codex', label: 'Codex', color: '#10a37f', mark: CodexMark },
];

/**
 * Agent conversation pane (spec §3.2). Start an embedded agent, send it a prompt, and watch its
 * tool activity and completion. The agent's file edits stream into the editor live (see the
 * editor.docChanged handler); here we surface the turn's control flow.
 */
export function AgentPanel() {
  const [prompt, setPrompt] = useState('');
  const [provider, setProvider] = useState<Provider>('claude');
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [running, setRunning] = useState(false);
  const [toolCalls, setToolCalls] = useState<ToolActivity[]>([]);
  const [result, setResult] = useState<string>('');
  const [messages, setMessages] = useState<{ role: 'user' | 'agent'; text: string }[]>([]);
  const pollRef = useRef<number | null>(null);

  useEffect(() => () => { if (pollRef.current) window.clearInterval(pollRef.current); }, []);

  async function start() {
    if (!prompt.trim() || running) return;
    setMessages((m) => [...m, { role: 'user', text: prompt }]);
    setToolCalls([]);
    setResult('');
    setRunning(true);
    try {
      const session = await commands.agent.start({ prompt, provider });
      setSessionId(session.sessionId);
      setPrompt('');
      poll(session.sessionId);
    } catch (e) {
      setRunning(false);
      setResult('Failed to start agent: ' + String(e));
    }
  }

  function poll(id: string) {
    if (pollRef.current) window.clearInterval(pollRef.current);
    pollRef.current = window.setInterval(async () => {
      try {
        const status = await commands.agent.status({ sessionId: id });
        setToolCalls((status.toolCalls ?? []).map((n) => ({ name: n })));
        if (status.done) {
          window.clearInterval(pollRef.current!);
          pollRef.current = null;
          setRunning(false);
          setResult(status.result || (status.success ? 'Done.' : 'Finished.'));
          if (status.result) setMessages((m) => [...m, { role: 'agent', text: status.result }]);
        }
      } catch {
        /* transient */
      }
    }, 800);
  }

  async function interrupt() {
    if (sessionId) await commands.agent.interrupt({ sessionId }).catch(() => {});
    setRunning(false);
  }

  return (
    <div className="flex h-full flex-col bg-[var(--color-bg-sidebar)]">
      <div className="flex items-center gap-2 px-3 pt-2 text-[11px]
        font-semibold uppercase tracking-wider text-[var(--color-fg-dim)]">
        Agent
      </div>

      {/* Provider picker: choose which authenticated CLI the editor spawns. */}
      <div className="flex gap-1.5 px-2 pt-2 pb-2.5">
        {PROVIDERS.map((p) => {
          const active = provider === p.id;
          return (
            <button key={p.id} onClick={() => !running && setProvider(p.id)} disabled={running}
              title={`Use ${p.label}`}
              className={`flex flex-1 items-center justify-center gap-1.5 rounded-md border px-2 py-1.5 text-[12px]
                font-medium transition-colors disabled:opacity-50
                ${active
                  ? 'border-transparent bg-[var(--color-bg-elev)] text-[var(--color-fg)]'
                  : 'border-transparent text-[var(--color-fg-dim)] hover:bg-[var(--color-bg-elev)]/60'}`}>
              <span style={{ color: active ? p.color : 'currentColor' }}>{p.mark({ size: 16 })}</span>
              {p.label}
            </button>
          );
        })}
      </div>

      <div className="flex-1 space-y-3 overflow-y-auto p-3">
        {messages.length === 0 && (
          <div className="text-[12px] leading-relaxed text-[var(--color-fg-dim)]">
            Pick <span className="text-[var(--color-fg)]">Claude Code</span> or
            <span className="text-[var(--color-fg)]"> Codex</span> above, then ask it to work in this
            project. Its file edits stream into the editor live and its terminal commands run in the
            panel below.
          </div>
        )}
        {messages.map((m, i) => (
          <div key={i} className={`rounded-lg px-3 py-2 text-[12.5px] leading-relaxed
            ${m.role === 'user'
              ? 'ml-6 bg-[var(--color-selection)] text-[var(--color-fg-bright)]'
              : 'mr-2 bg-[var(--color-bg-elev)] text-[var(--color-fg)]'}`}>
            {m.text}
          </div>
        ))}

        {(running || toolCalls.length > 0) && (
          <div className="rounded-lg border border-[var(--color-border)] bg-[var(--color-panel)] p-2">
            <div className="mb-1 flex items-center gap-1.5 text-[11px] text-[var(--color-fg-dim)]">
              <Icon path={mdiToolboxOutline} size={14} /> Tool activity
              {running && <span className="ml-auto animate-pulse text-[var(--color-accent)]">working…</span>}
            </div>
            {toolCalls.length === 0 && <div className="text-[11px] text-[var(--color-fg-dim)]">thinking…</div>}
            {toolCalls.map((t, i) => (
              <div key={i} className="flex items-center gap-1.5 py-0.5 text-[12px] text-[var(--color-fg)]">
                <Icon path={mdiCheckCircleOutline} size={13} className="text-[var(--color-accent)]" />
                <code className="text-[11.5px]">{t.name.replace('mcp__jdesk_editor__', '')}</code>
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="border-t border-[var(--color-border)] p-2">
        <div className="flex items-end gap-2">
          <textarea
            className="min-h-[38px] max-h-[140px] flex-1 resize-none rounded-md border border-[var(--color-border)]
              bg-[var(--color-bg)] px-2.5 py-2 text-[12.5px] text-[var(--color-fg)] outline-none
              focus:border-[var(--color-accent-dim)]"
            placeholder="Ask the agent to build or change something…"
            value={prompt}
            rows={1}
            onChange={(e) => setPrompt(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); start(); } }}
          />
          {running ? (
            <button onClick={interrupt} title="Interrupt"
              className="flex h-9 w-9 items-center justify-center rounded-md bg-[var(--color-danger)] text-black">
              <Icon path={mdiStop} size={18} />
            </button>
          ) : (
            <button onClick={start} title="Send" disabled={!prompt.trim()}
              className="flex h-9 w-9 items-center justify-center rounded-md bg-[var(--color-accent)]
                text-black disabled:opacity-40">
              <Icon path={mdiSend} size={17} />
            </button>
          )}
        </div>
        {result && !running && (
          <div className="mt-1.5 truncate text-[11px] text-[var(--color-fg-dim)]" title={result}>{result}</div>
        )}
      </div>
    </div>
  );
}
