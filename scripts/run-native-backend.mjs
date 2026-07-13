import { spawn } from 'node:child_process';
import { readFileSync } from 'node:fs';

const [configPath, ...mavenArguments] = process.argv.slice(2);

const documentedSpringclawSettings = [
  'SPRINGCLAW_HTTP_BIND_ADDRESS',
  'SPRINGCLAW_HTTP_PORT',
  'SPRINGCLAW_ADMIN_USERNAMES',
  'SPRINGCLAW_PASSWORD_PEPPER',
  'SPRINGCLAW_AUTH_BOOTSTRAP_FIRST_USER_ADMIN',
  'SPRINGCLAW_AUTH_COOKIE_SECURE',
  'SPRINGCLAW_SYSTEM_COMMAND_ENABLED',
  'SPRINGCLAW_WEBHOOK_SECURITY_ENABLED',
  'SPRINGCLAW_WEBHOOK_SECRET',
  'SPRINGCLAW_WEBHOOK_SECRET_TELEGRAM',
  'SPRINGCLAW_WEBHOOK_SECRET_WECHAT',
  'SPRINGCLAW_WEBHOOK_SECRET_FEISHU',
  'SPRINGCLAW_AI_ACTIVE_PROVIDER',
  'SPRINGCLAW_PRIMARY_ENABLED',
  'SPRINGCLAW_PRIMARY_API_KEY',
  'SPRINGCLAW_PRIMARY_BASE_URL',
  'SPRINGCLAW_PRIMARY_MODEL',
  'SPRINGCLAW_QWEN_ENABLED',
  'SPRINGCLAW_QWEN_API_KEY',
  'SPRINGCLAW_QWEN_BASE_URL',
  'SPRINGCLAW_QWEN_MODEL',
  'SPRINGCLAW_CODING_PLAN_ENABLED',
  'SPRINGCLAW_CODING_PLAN_API_KEY',
  'SPRINGCLAW_CODING_PLAN_BASE_URL',
  'SPRINGCLAW_CODING_PLAN_MODEL',
  'SPRINGCLAW_DEEPSEEK_ENABLED',
  'SPRINGCLAW_DEEPSEEK_API_KEY',
  'SPRINGCLAW_DEEPSEEK_BASE_URL',
  'SPRINGCLAW_DEEPSEEK_MODEL',
  'SPRINGCLAW_VOLCENGINE_CODING_PLAN_ENABLED',
  'SPRINGCLAW_VOLCENGINE_CODING_PLAN_API_KEY',
  'SPRINGCLAW_VOLCENGINE_CODING_PLAN_BASE_URL',
  'SPRINGCLAW_VOLCENGINE_CODING_PLAN_MODEL',
];

const requiredApplicationSettings = [
  'MYSQL_DB',
  'MYSQL_USER',
  'MYSQL_PASSWORD',
  'REDIS_PASSWORD',
  'RABBITMQ_USERNAME',
  'RABBITMQ_PASSWORD',
];

const requiredNativeRuntimeSafetySettings = {
  SPRINGCLAW_PERSISTENCE_DB_ENABLED: 'true',
  SPRINGCLAW_FEISHU_OUTBOUND_ENABLED: 'false',
  SPRINGCLAW_FEISHU_LONG_CONNECTION_ENABLED: 'false',
};

function fail(message) {
  process.stderr.write(`Native backend startup failed: ${message}\n`);
  process.exit(1);
}

function requireObject(value, message) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    fail(message);
  }
  return value;
}

function requireString(value, message) {
  if (typeof value !== 'string') {
    fail(message);
  }
  return value;
}

function loopbackPublishedPort(services, serviceName, targetPort) {
  const service = requireObject(services[serviceName], 'resolved Compose configuration is missing a development service');
  const ports = Array.isArray(service.ports) ? service.ports : [];
  const mapping = ports.find((port) => (
    Number(port.target) === targetPort
    && String(port.protocol ?? 'tcp') === 'tcp'
    && String(port.host_ip ?? '') === '127.0.0.1'
    && port.published !== undefined
  ));
  if (!mapping) {
    fail('resolved Compose configuration is missing a loopback development port');
  }

  const published = String(mapping.published);
  if (!/^[1-9][0-9]{0,4}$/.test(published) || Number(published) > 65535) {
    fail('resolved Compose configuration has an invalid development port');
  }
  return published;
}

if (!configPath) {
  fail('resolved Compose configuration path is missing');
}

let resolvedConfig;
try {
  resolvedConfig = JSON.parse(readFileSync(configPath, 'utf8'));
} catch {
  fail('could not read the resolved Compose configuration');
}

const services = requireObject(resolvedConfig.services, 'resolved Compose configuration is missing services');
const app = requireObject(services.app, 'resolved Compose configuration is missing the application service');
const appEnvironment = requireObject(app.environment, 'resolved Compose configuration is missing application settings');
const selectedEnvironment = {
  MYSQL_HOST: '127.0.0.1',
  MYSQL_PORT: loopbackPublishedPort(services, 'mysql', 3306),
  REDIS_HOST: '127.0.0.1',
  REDIS_PORT: loopbackPublishedPort(services, 'redis', 6379),
  RABBITMQ_HOST: '127.0.0.1',
  RABBITMQ_PORT: loopbackPublishedPort(services, 'rabbitmq', 5672),
};

for (const key of requiredApplicationSettings) {
  selectedEnvironment[key] = requireString(
    appEnvironment[key],
    'resolved Compose configuration is missing a required native application setting',
  );
}

for (const [key, expectedValue] of Object.entries(requiredNativeRuntimeSafetySettings)) {
  const resolvedValue = requireString(
    appEnvironment[key],
    'resolved Compose configuration is missing a required native runtime safety setting',
  );
  if (resolvedValue !== expectedValue) {
    fail('resolved Compose configuration has an invalid required native runtime safety setting');
  }
  selectedEnvironment[key] = resolvedValue;
}

for (const key of documentedSpringclawSettings) {
  if (appEnvironment[key] !== undefined) {
    selectedEnvironment[key] = requireString(
      appEnvironment[key],
      'resolved Compose configuration has an invalid documented SpringClaw setting',
    );
  }
}

const maven = spawn('mvn', ['spring-boot:run', ...mavenArguments], {
  env: { ...process.env, ...selectedEnvironment },
  stdio: 'inherit',
});

maven.once('error', () => fail('Maven could not start'));
maven.once('exit', (code, signal) => {
  process.exit(code ?? (signal ? 1 : 0));
});
