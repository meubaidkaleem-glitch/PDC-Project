import { prepare, startBackend, startWorker } from './stack';

/** Starts the backend and two independent worker processes. */
export default async function globalSetup() {
  prepare();
  await startBackend();
  await startWorker('e2e-w1');
  await startWorker('e2e-w2');
}
