# SpringClaw Product Experience Audit

Date: 2026-07-02
Audit mode: PM + end-user walkthrough
Surface: local web app, Agent Console and Admin login
Desktop viewport: 1280 x 720
Mobile viewport: 390 x 844

## Scope

This audit covers the first-time Agent experience, guest-to-login transition, authenticated chat, one memory follow-up, and mobile rendering. It does not claim full WCAG compliance because only screenshots, DOM state, visible behavior, and console logs were inspected.

## User Goal

A user should be able to open SpringClaw, understand what the product is for, sign in only when needed, ask a simple question, see progress clearly, get a readable answer, and continue with a follow-up without needing to understand runtime internals.

## Captured Steps

1. Desktop entry: healthy but too much runtime chrome.
   Screenshot: `screenshots/01-desktop-entry.png`
   Health: Medium

2. Guest send attempt: auth requirement appears only after trying to send.
   Screenshot: `screenshots/04-current-after-timeout.png`
   Health: Poor

3. Admin login/register: registration works, but logged-in state is visually mixed with the login form.
   Screenshot: `screenshots/06-after-register.png`
   Health: Medium-low

4. Authenticated Agent entry: model status becomes healthy and chat can be used.
   Screenshot: `screenshots/07-agent-logged-in.png`
   Health: Medium

5. Simple question: final answer completed within a few seconds and was understandable.
   Screenshot: `screenshots/10-agent-final-wait.png`
   Health: Good backend path, medium UI clarity

6. Memory follow-up: "What did I just ask?" returned the previous user question correctly in about 3 seconds.
   Screenshot: `screenshots/12-followup-final.png`
   Health: Good backend path, medium UI clarity

7. Mobile chat: no horizontal overflow, but the chat history area collapses and the composer plus trace panel dominate the screen.
   Screenshot: `screenshots/13-mobile-agent-chat.png`
   Health: Poor

## Strengths

- Backend health was UP across MySQL, Redis, and RabbitMQ.
- Authenticated simple chat path responded quickly enough for a basic question.
- The multi-turn follow-up test succeeded and used a local short-circuit route for previous-message recall.
- Desktop no longer has the previous obvious horizontal overflow issue.
- Trace data is useful for debugging and shows intent, route, model, and completion state.

## UX Risks

1. Login is a hidden prerequisite.
   The composer accepts input while logged out, then shows "please login" only after the user tries to send. This makes the product feel stuck or unreliable.

2. The main screen still reads like a developer console.
   The first viewport contains Agent Console, Sessions, Agents, Skills, Tools, Proposals, Tasks, Learning, Memory Candidates, Knowledge, Usage, model/provider status, and trace concepts before the user has succeeded with one task.

3. Debug information competes with the answer.
   After a successful answer, the right trace panel occupies a large share of desktop width. For normal users, it distracts from the conversation.

4. Mobile chat is effectively broken after a conversation.
   At 390 x 844, the measured chat log height was about 24px and the composer starts at the same y coordinate as the chat log. The user cannot comfortably read the conversation.

5. Status states conflict.
   The final trace shows `Completed`, but the "Current Step" panel can still say `running`. The displayed duration can be tens of milliseconds while the user-perceived answer took seconds, which hurts trust.

6. Language and hierarchy are mixed.
   The UI switches between English and Chinese: `New Session`, `Agent Console`, `Login required`, `Healthy`, `Run Trace`, and Chinese task prompts. This creates extra cognitive load.

7. Admin login success is ambiguous.
   After registration, the identity appears as logged in, but the username/password form and login/register buttons remain prominent. The user has to infer whether registration succeeded.

## Accessibility Risks

- Mobile layout does not preserve readable content order because conversation history is visually squeezed by composer and trace surfaces.
- Some icon-like controls rely on terse labels such as `More`, `Edit`, and arrow buttons; keyboard and screen-reader intent should be verified.
- Debug/status chips use color and compact text together; contrast and non-color status communication need explicit checks.
- The app should verify focus order after route changes, especially from Admin login back to Agent.
- Full accessibility cannot be confirmed from screenshots alone; keyboard-only navigation and screen-reader announcements still need testing.

## Optimization Plan

### P0: Fix Trust Breakers

1. Make auth gating explicit before typing.
   When logged out, replace the composer with a clear login CTA and short reason: "Log in to start an Agent conversation." Keep starter prompts visible but disabled or route them to login.

2. Fix mobile chat layout.
   On mobile, hide the trace inspector by default, give the conversation a stable min-height, keep the composer below visible messages, and use a drawer for run details.

3. Fix completion state consistency.
   When a run is completed, the current-step panel must not remain `running`. Add one front-end state normalizer or use the final lifecycle event as the source of truth.

4. Show user-perceived response timing.
   Track time from send click to first token/placeholder and final answer. Keep backend trace duration separate from user-facing elapsed time.

5. Replace send-after-error with inline recovery.
   For logged-out sends, keep the typed prompt as a draft and show a `Log in and continue` button near the composer.

### P1: Make The Product Understandable

1. Split normal mode and debug mode.
   Default surface: Chat, Sessions, Starter tasks, Account.
   Debug surface: Trace, Tools, Memory, Logs, Proposals, Usage.

2. Rename navigation for user intent.
   Use labels like `Chat`, `History`, `Skills`, `Automation`, `Settings`. Move runtime-only modules into a secondary `Developer` or `Runtime` section.

3. Clean the top status bar.
   Replace provider/model chips with a simple health line: "Model ready" or "Model unavailable". Put provider details in a tooltip or debug drawer.

4. Stabilize the task header.
   The header should describe the current conversation, not reflow around every prompt. Session metadata should collapse behind a details button.

5. Create answer quality checks.
   Add a small regression set: simple identity question, previous-message recall, 5-turn recall, Chinese instruction following, and "answer in one sentence".

### P2: Improve Polish And Accessibility

1. Standardize language.
   Choose Chinese-first for this product surface, with English only where it is a technical identifier.

2. Reduce visual density.
   Use one dominant primary action, fewer chips, fewer nested panels, and more stable vertical rhythm.

3. Improve admin logged-in state.
   After login/register, collapse the form and show account status plus clear next actions.

4. Add keyboard and screen-reader QA.
   Test tab order, focus rings, aria labels, route focus restoration, and live-region announcements for streaming answers.

5. Tokenize layout and status colors.
   Keep semantic tokens for background, surface, border, primary, success, warning, danger, and text hierarchy. Avoid one-off status chip styling.

### P3: Product Maturity

1. Add a first-run checklist.
   Show: sign in, ask a question, run a skill, review a trace. Keep it lightweight and dismissible.

2. Add conversation health diagnostics.
   Show memory enabled/disabled, last saved turn, and retrieval status in debug mode only.

3. Add observability dashboards for product metrics.
   Track first-success rate, auth-blocked sends, p50/p95 first-token time, p50/p95 final-answer time, recall pass rate, and mobile layout error count.

## Acceptance Criteria

- Logged-out users cannot type and send into a dead-end state.
- On 390px mobile, at least 45% of the viewport remains available for conversation reading after one answer.
- Debug trace is hidden by default for normal users and available from one clear control.
- Simple authenticated question reaches first visible feedback within 1 second and final answer within 8 seconds p95 in local/dev conditions.
- Previous-message recall passes for at least 95% of a 20-case regression set.
- Completed runs never display a stale `running` current step.
