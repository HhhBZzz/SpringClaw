# Security Policy

SpringClaw includes model access, local tool execution, skill execution, memory, authentication, and admin operations. Please report security issues privately.

## Supported Versions

Security fixes target the default branch of this repository.

## Reporting a Vulnerability

Please do not open a public issue for suspected vulnerabilities. Instead, use GitHub's private vulnerability reporting flow if available, or contact the repository owner through GitHub.

Include:

- A concise description of the issue.
- Steps to reproduce.
- Affected configuration or endpoint.
- Expected impact.
- Any relevant logs or request samples with secrets removed.

## Areas of Interest

Reports are especially useful for:

- Authentication or role bypasses.
- Tool permission bypasses.
- Unsafe script or skill execution.
- Server-side request forgery in web/search tools.
- Exposure of API keys, tokens, cookies, or local files.
- Cross-session memory or context leakage.
- Admin API access control issues.

## Handling

The maintainer will triage reports, reproduce when possible, and coordinate a fix before public disclosure.
