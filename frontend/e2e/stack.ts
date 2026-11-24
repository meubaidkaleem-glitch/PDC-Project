/**
 * The platform under end-to-end test: the packaged backend jar (serving the built interface) and worker processes
 * started from the DistriCompute-Distributed jar, against the MySQL database given in DISTRICOMPUTE_DB_URL. Process
 * ids are kept in a state file so that tests can kill a worker or crash and restart the backend.
 * See docs/testing.md, "End-to-end tests".
 */
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, openSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

function required(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set; run the end-to-end tests with scripts/e2e.sh`);
  return value;
}

export const stack = {
  get dir() { return required('E2E_DIR'); },
  get dataDir() { return join(this.dir, 'data'); },
  get logDir() { return join(this.dir, 'logs'); },
  get httpPort() { return Number(process.env.E2E_HTTP_PORT ?? 18080); },
  get workerPort() { return Number(process.env.E2E_WORKER_PORT ?? 17680); },
  get baseUrl() { return `http://127.0.0.1:${this.httpPort}`; },
  adminUser: 'admin',
  adminPassword: 'e2e-admin-password-1',
};

interface State {
  backendPid?: number;
  backendStarts: number;
  workers: Record<string, number>;
}

const stateFile = () => join(stack.dir, 'state.json');

export function readState(): State {
  return existsSync(stateFile()) ? JSON.parse(readFileSync(stateFile(), 'utf8')) as State : { backendStarts: 0, workers: {} };
}

function writeState(state: State) {
  writeFileSync(stateFile(), JSON.stringify(state, null, 2));
}

function java(args: string[], log: string) {
  const out = openSync(join(stack.logDir, log), 'a');
  const child = spawn(process.env.E2E_JAVA ?? 'java', args, { detached: true, stdio: ['ignore', out, out], env: process.env });
  child.unref();
  return child.pid!;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

async function until(what: string, timeoutMs: number, check: () => Promise<boolean> | boolean) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      if (await check()) return;
    } catch {
      // not yet
    }
    await sleep(250);
  }
  throw new Error(`Timed out after ${timeoutMs} ms waiting for ${what}`);
}

export function prepare() {
  mkdirSync(stack.dataDir, { recursive: true });
  mkdirSync(stack.logDir, { recursive: true });
  writeFileSync(join(stack.dir, 'admin.password'), `${stack.adminPassword}\n`, { mode: 0o600 });
}

/** Starts the backend and waits until it is healthy. */
export async function startBackend() {
  const state = readState();
  state.backendStarts += 1;
  state.backendPid = java(['-jar', required('E2E_BACKEND_JAR'),
    `--server.port=${stack.httpPort}`,
    `--districompute.data-dir=${stack.dataDir}`,
    `--districompute.security.admin-password-file=${join(stack.dir, 'admin.password')}`,
    '--districompute.coordinator.bind-address=127.0.0.1',
    `--districompute.coordinator.port=${stack.workerPort}`,
    '--districompute.faults.enabled=true',
  ], `backend-${state.backendStarts}.log`);
  writeState(state);
  await until('the backend to be healthy', 180_000, async () => {
    const response = await fetch(`${stack.baseUrl}/actuator/health`);
    return response.ok;
  });
}

/** Kills the backend: SIGKILL simulates a crash, SIGTERM a graceful shutdown. */
export async function killBackend(signal: NodeJS.Signals = 'SIGKILL') {
  const state = readState();
  if (state.backendPid) {
    try {
      process.kill(state.backendPid, signal);
    } catch {
      // already gone
    }
    await until('the backend to stop', 60_000, () => {
      try {
        process.kill(state.backendPid!, 0);
        return false;
      } catch {
        return true;
      }
    });
  }
  writeState({ ...state, backendPid: undefined });
}

/** Starts a worker process and waits until it has registered with the backend's coordinator. */
export async function startWorker(id: string, slots = 2) {
  const state = readState();
  const log = `worker-${id}.log`;
  state.workers[id] = java(['-Xmx512m', '-jar', required('E2E_WORKER_JAR'), 'worker',
    '--coordinator', `127.0.0.1:${stack.workerPort}`, '--id', id, '--max-concurrent-tasks', String(slots),
    '--secret-file', join(stack.dataDir, 'cluster.secret')], log);
  writeState(state);
  await until(`worker ${id} to register`, 60_000,
    () => readFileSync(join(stack.logDir, log), 'utf8').includes(`Worker ${id} registered`));
}

/** Kills a worker process (SIGKILL: no goodbye to the coordinator). */
export function killWorker(id: string, signal: NodeJS.Signals = 'SIGKILL') {
  const state = readState();
  const pid = state.workers[id];
  if (pid) {
    try {
      process.kill(pid, signal);
    } catch {
      // already gone
    }
  }
  delete state.workers[id];
  writeState(state);
}

export async function stopAll() {
  for (const id of Object.keys(readState().workers)) killWorker(id, 'SIGTERM');
  await killBackend('SIGTERM');
}
