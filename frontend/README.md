# SpringClaw Frontend

Modern frontend workspace for SpringClaw.

## Stack

- Vue 3
- Vite
- TypeScript
- Pinia
- Vue Router

## Development

```bash
cd frontend
npm install
npm run dev
```

Vite proxies backend requests to `http://localhost:18080`:

- `/api/*`
- `/actuator/*`

Open the app at `http://localhost:5173/#/agent`.

## Build

```bash
cd frontend
npm run build
```

The build output is `frontend/dist/`. It is intentionally ignored by Git.
