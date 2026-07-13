import { useEffect, useRef, useState } from 'react';
import { mdiRobotOutline, mdiSend, mdiStop, mdiToolboxOutline, mdiCheckCircleOutline } from '@mdi/js';
import { commands } from '../ipc';
import { Icon } from './Icon';

interface ToolActivity { name: string }

/**
 * Agent conversation pane (spec §3.2). Start an embedded agent, send it a prompt, and watch its
 * tool activity and completion. The agent's file edits stream into the editor live (see the
 * editor.docChanged handler); here we surface the turn's control flow.
 */
export function AgentPanel() {
  const [prompt, setPrompt] = useState('');
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
      const session = await commands.agent.startClaude({ prompt });
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
    <div className="flex h-full flex-col bg-[--color-bg-sidebar]">
      <div className="flex items-center gap-2 border-b border-[--color-border] px-3 py-2 text-[11px]
        font-semibold uppercase tracking-wider text-[--color-fg-dim]">
        <Icon path={mdiRobotOutline} size={16} />
        Agent
      </div>

      <div className="flex-1 space-y-3 overflow-y-auto p-3">
        {messages.length === 0 && (
          <div className="text-[12px] leading-relaxed text-[--color-fg-dim]">
            Ask an agent to work in this project. Its file edits stream into the editor live and its
            terminal commands run in the panel below.
          </div>
        )}
        {messages.map((m, i) => (
          <div key={i} className={`rounded-lg px-3 py-2 text-[12.5px] leading-relaxed
            ${m.role === 'user'
              ? 'ml-6 bg-[#22303a] text-[--color-fg-bright]'
              : 'mr-2 bg-[--color-bg-elev] text-[--color-fg]'}`}>
            {m.text}
          </div>
        ))}

        {(running || toolCalls.length > 0) && (
          <div className="rounded-lg border border-[--color-border] bg-[--color-panel] p-2">
            <div className="mb-1 flex items-center gap-1.5 text-[11px] text-[--color-fg-dim]">
              <Icon path={mdiToolboxOutline} size={14} /> Tool activity
              {running && <span className="ml-auto animate-pulse text-[--color-accent]">working…</span>}
            </div>
            {toolCalls.length === 0 && <div className="text-[11px] text-[--color-fg-dim]">thinking…</div>}
            {toolCalls.map((t, i) => (
              <div key={i} className="flex items-center gap-1.5 py-0.5 text-[12px] text-[--color-fg]">
                <Icon path={mdiCheckCircleOutline} size={13} className="text-[--color-accent]" />
                <code className="text-[11.5px]">{t.name.replace('mcp__jdesk_editor__', '')}</code>
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="border-t border-[--color-border] p-2">
        <div className="flex items-end gap-2">
          <textarea
            className="min-h-[38px] max-h-[140px] flex-1 resize-none rounded-md border border-[--color-border]
              bg-[--color-bg] px-2.5 py-2 text-[12.5px] text-[--color-fg] outline-none
              focus:border-[--color-accent-dim]"
            placeholder="Ask the agent to build or change something…"
            value={prompt}
            rows={1}
            onChange={(e) => setPrompt(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); start(); } }}
          />
          {running ? (
            <button onClick={interrupt} title="Interrupt"
              className="flex h-9 w-9 items-center justify-center rounded-md bg-[--color-danger] text-black">
              <Icon path={mdiStop} size={18} />
            </button>
          ) : (
            <button onClick={start} title="Send" disabled={!prompt.trim()}
              className="flex h-9 w-9 items-center justify-center rounded-md bg-[--color-accent]
                text-black disabled:opacity-40">
              <Icon path={mdiSend} size={17} />
            </button>
          )}
        </div>
        {result && !running && (
          <div className="mt-1.5 truncate text-[11px] text-[--color-fg-dim]" title={result}>{result}</div>
        )}
      </div>
    </div>
  );
}
