import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  chmodSync,
  existsSync,
  mkdtempSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const launcher = join(repositoryRoot, 'scripts', 'run-native-backend.mjs');
const temporaryDirectory = mkdtempSync(join(tmpdir(), 'springclaw-native-launcher-test-'));
const binDirectory = join(temporaryDirectory, 'bin');
const capturePath = join(temporaryDirectory, 'maven-environment.json');
const resolvedConfigPath = join(temporaryDirectory, 'resolved-compose.json');
const callerHome = join(temporaryDirectory, 'caller-home');
const callerPath = `${binDirectory}${delimiter}${process.env.PATH ?? ''}`;

const runtimeSafetySettings = {
  SPRINGCLAW_PERSISTENCE_DB_ENABLED: 'true',
  SPRINGCLAW_FEISHU_OUTBOUND_ENABLED: 'false',
  SPRINGCLAW_FEISHU_LONG_CONNECTION_ENABLED: 'false',
};

function resolvedConfig() {
  return {
    services: {
      app: {
        environment: {
          MYSQL_DB: 'native_test_db',
          MYSQL_USER: 'native_test_user',
          MYSQL_PASSWORD: 'native_test_mysql_password',
          REDIS_PASSWORD: 'native_test_redis_password',
          RABBITMQ_USERNAME: 'native_test_rabbitmq_user',
          RABBITMQ_PASSWORD: 'native_test_rabbitmq_password',
          ...runtimeSafetySettings,
          PATH: '/container-only/path',
          HOME: '/container-only/home',
        },
      },
      mysql: { ports: [{ target: 3306, published: 13306, host_ip: '127.0.0.1' }] },
      redis: { ports: [{ target: 6379, published: 16379, host_ip: '127.0.0.1' }] },
      rabbitmq: { ports: [{ target: 5672, published: 15672, host_ip: '127.0.0.1' }] },
    },
  };
}

function runLauncher(config) {
  writeFileSync(resolvedConfigPath, JSON.stringify(config), { mode: 0o600 });
  return spawnSync(process.execPath, [launcher, resolvedConfigPath], {
    cwd: repositoryRoot,
    env: {
      ...process.env,
      PATH: callerPath,
      HOME: callerHome,
      NATIVE_BACKEND_ENV_CAPTURE: capturePath,
    },
    encoding: 'utf8',
  });
}

try {
  mkdirSync(binDirectory);
  writeFileSync(join(binDirectory, 'mvn'), `#!/usr/bin/env node
const { writeFileSync } = require('node:fs');
writeFileSync(process.env.NATIVE_BACKEND_ENV_CAPTURE, JSON.stringify(process.env), { mode: 0o600 });
`, { mode: 0o700 });
  chmodSync(join(binDirectory, 'mvn'), 0o700);

  const launched = runLauncher(resolvedConfig());
  assert.equal(launched.status, 0, 'the launcher should run Maven with a valid resolved config');
  const capturedEnvironment = JSON.parse(readFileSync(capturePath, 'utf8'));

  assert.deepEqual(
    Object.fromEntries(Object.keys(runtimeSafetySettings).map((key) => [key, capturedEnvironment[key]])),
    runtimeSafetySettings,
    'Maven receives the Compose-enforced persistence and Feishu safety settings exactly',
  );
  assert.deepEqual(
    {
      MYSQL_DB: capturedEnvironment.MYSQL_DB,
      MYSQL_USER: capturedEnvironment.MYSQL_USER,
      MYSQL_PASSWORD: capturedEnvironment.MYSQL_PASSWORD,
      MYSQL_HOST: capturedEnvironment.MYSQL_HOST,
      MYSQL_PORT: capturedEnvironment.MYSQL_PORT,
      REDIS_HOST: capturedEnvironment.REDIS_HOST,
      REDIS_PORT: capturedEnvironment.REDIS_PORT,
      RABBITMQ_HOST: capturedEnvironment.RABBITMQ_HOST,
      RABBITMQ_PORT: capturedEnvironment.RABBITMQ_PORT,
    },
    {
      MYSQL_DB: 'native_test_db',
      MYSQL_USER: 'native_test_user',
      MYSQL_PASSWORD: 'native_test_mysql_password',
      MYSQL_HOST: '127.0.0.1',
      MYSQL_PORT: '13306',
      REDIS_HOST: '127.0.0.1',
      REDIS_PORT: '16379',
      RABBITMQ_HOST: '127.0.0.1',
      RABBITMQ_PORT: '15672',
    },
    'Maven receives configured database credentials and resolved loopback development ports',
  );
  assert.equal(capturedEnvironment.PATH, callerPath, 'container PATH cannot override the caller PATH');
  assert.equal(capturedEnvironment.HOME, callerHome, 'container HOME cannot override the caller HOME');

  rmSync(capturePath);
  const invalidConfig = resolvedConfig();
  delete invalidConfig.services.app.environment.SPRINGCLAW_FEISHU_OUTBOUND_ENABLED;
  const rejected = runLauncher(invalidConfig);
  assert.notEqual(rejected.status, 0, 'missing runtime safety settings reject native startup');
  assert.equal(existsSync(capturePath), false, 'Maven does not start when runtime safety settings are invalid');

  process.stdout.write('native backend launcher regression tests passed.\n');
} finally {
  rmSync(temporaryDirectory, { force: true, recursive: true });
}
