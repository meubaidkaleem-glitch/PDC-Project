import { stopAll } from './stack';

export default async function globalTeardown() {
  await stopAll();
}
