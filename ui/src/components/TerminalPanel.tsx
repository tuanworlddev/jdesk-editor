import { useEffect, useRef } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { commands } from '../ipc';

/**
 * Interactive terminal backed by a real PTY (spec §17). xterm renders; keystrokes go to the PTY via
 * terminal.write; output is polled from terminal.read and written back. The same PTY an agent can
 * drive, so the human and agent see identical output.
 */
export function TerminalPanel({ visible }: { visible: boolean }) {
  const hostRef = useRef<HTMLDivElement>(null);
  const termRef = useRef<Terminal | null>(null);
  const fitRef = useRef<FitAddon | null>(null);
  const idRef = useRef<string | null>(null);
  const pollRef = useRef<number | null>(null);

  useEffect(() => {
    if (!hostRef.current || termRef.current) return;
    const term = new Terminal({
      fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
      fontSize: 12.5,
      theme: { background: '#16181e', foreground: '#c7ccd6', cursor: '#4dd6c1' },
      cursorBlink: true,
      convertEol: false,
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(hostRef.current);
    fit.fit();
    termRef.current = term;
    fitRef.current = fit;

    (async () => {
      const opened = await commands.terminal.open({ cols: term.cols, rows: term.rows });
      idRef.current = opened.terminalId;
      term.onData((data) => {
        if (idRef.current) void commands.terminal.write({ terminalId: idRef.current, data });
      });
      pollRef.current = window.setInterval(async () => {
        if (!idRef.current) return;
        try {
          const out = await commands.terminal.read({ terminalId: idRef.current });
          if (out.data) term.write(out.data);
        } catch { /* transient */ }
      }, 60);
    })();

    return () => {
      if (pollRef.current) window.clearInterval(pollRef.current);
      if (idRef.current) void commands.terminal.close({ terminalId: idRef.current }).catch(() => {});
      term.dispose();
      termRef.current = null;
    };
  }, []);

  // Refit when shown or the container resizes.
  useEffect(() => {
    if (!visible) return;
    const fit = () => {
      fitRef.current?.fit();
      if (idRef.current && termRef.current) {
        void commands.terminal.resize({
          terminalId: idRef.current, cols: termRef.current.cols, rows: termRef.current.rows,
        }).catch(() => {});
      }
    };
    const t = window.setTimeout(fit, 30);
    window.addEventListener('resize', fit);
    return () => { window.clearTimeout(t); window.removeEventListener('resize', fit); };
  }, [visible]);

  return <div ref={hostRef} className="h-full w-full px-2 pt-1" />;
}
