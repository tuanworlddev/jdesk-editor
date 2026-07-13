#!/usr/bin/env node
// Phase-0d de-risking: prove the real `codex app-server` completes a JSON-RPC `initialize`
// handshake over JSONL stdio before the Phase-4 adapter depends on it. Records the raw
// exchange to stdout for capture as parser-evidence. Exits 0 on a valid response, 1 otherwise.
import { spawn } from 'node:child_process';

const TIMEOUT_MS = 30_000;
const child = spawn('codex', ['app-server'], { stdio: ['pipe', 'pipe', 'pipe'] });

let stdoutBuf = '';
let responded = false;
const events = [];

const timer = setTimeout(() => {
  fail('timeout waiting for initialize response');
}, TIMEOUT_MS);

function fail(reason) {
  console.log(JSON.stringify({ probe: 'codex-initialize', ok: false, reason, events }));
  clearTimeout(timer);
  child.kill('SIGKILL');
  process.exit(1);
}

function succeed(response) {
  console.log(JSON.stringify({
    probe: 'codex-initialize',
    ok: true,
    platformOs: response.result?.platformOs,
    platformFamily: response.result?.platformFamily,
    userAgent: response.result?.userAgent,
    hasCodexHome: Boolean(response.result?.codexHome),
    events,
  }, null, 2));
  clearTimeout(timer);
  child.kill('SIGKILL');
  process.exit(0);
}

child.stdout.on('data', (chunk) => {
  stdoutBuf += chunk.toString('utf8');
  let idx;
  while ((idx = stdoutBuf.indexOf('\n')) >= 0) {
    const line = stdoutBuf.slice(0, idx).trim();
    stdoutBuf = stdoutBuf.slice(idx + 1);
    if (!line) continue;
    let msg;
    try { msg = JSON.parse(line); } catch { events.push({ unparsed: line.slice(0, 200) }); continue; }
    events.push({ method: msg.method ?? null, id: msg.id ?? null, kind: msg.result ? 'response' : (msg.method ? 'notification' : 'other') });
    if (msg.id === 1 && msg.result && !responded) {
      responded = true;
      // Complete the required post-initialize step, then finish.
      succeed(msg);
    }
    if (msg.id === 1 && msg.error) {
      fail('initialize returned error: ' + JSON.stringify(msg.error));
    }
  }
});

child.stderr.on('data', (chunk) => {
  process.stderr.write(chunk);
});

child.on('error', (err) => fail('spawn error: ' + err.message));
child.on('exit', (code) => {
  if (!responded) fail('app-server exited early with code ' + code);
});

const initialize = {
  id: 1,
  method: 'initialize',
  params: {
    clientInfo: { name: 'jdesk-editor-probe', title: 'JDesk Editor Handshake Probe', version: '0.0.1' },
    capabilities: { experimentalApi: false },
  },
};
child.stdin.write(JSON.stringify(initialize) + '\n');
