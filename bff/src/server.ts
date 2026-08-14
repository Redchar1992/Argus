import type { FastifyInstance } from 'fastify';
import { buildApp } from './app.js';
import { loadConfig } from './config.js';
import { createRuntimeDependencies } from './runtime.js';

async function start(): Promise<void> {
  const config = loadConfig();
  const dependencies = await createRuntimeDependencies(config);
  let app: FastifyInstance;
  try {
    app = await buildApp(config, dependencies);
  } catch (error) {
    await dependencies.close?.();
    throw error;
  }

  const shutdown = async (signal: string): Promise<void> => {
    app.log.info({ signal }, 'Shutting down');
    await app.close();
    process.exit(0);
  };

  process.on('SIGINT', () => void shutdown('SIGINT'));
  process.on('SIGTERM', () => void shutdown('SIGTERM'));

  try {
    await app.listen({ host: config.host, port: config.port });
  } catch (error) {
    app.log.error(error);
    await app.close();
    throw error;
  }
}

try {
  await start();
} catch (error) {
  // Configuration values are deliberately not logged because Redis URLs can
  // contain credentials and encryption keys are process secrets.
  console.error(error instanceof Error ? error.message : 'Identity BFF failed to start');
  process.exit(1);
}
