# Looking at a UI change in a browser

Read this before you claim that a UI change looks right, and before you start or stop a local
stack for that purpose. Tests and linters prove the markup and the behaviour; they do not show
whether a screen is tidy, readable and usable on a phone. The **preview stack** lets you look at
the screen for real. It runs this checkout's code, signed in as a throwaway account, on seeded
data (REQ-OPS-057).

The preview stack is the E2E stack that `:frontend:e2eTest` uses. The only difference is that it
stays up after the start. It has the same images built from the checkout, the same synthetic
realm, the same throwaway credentials and the same seeding. The runbook
[`docs/e2e-test/README.md`](../e2e-test/README.md) ("Gemeinsamer Rahmen") describes that stack.

## Start it

```bash
./gradlew :frontend:previewStackUp
```

- **The first start takes 10–15 minutes**, because it builds four images. A later
  `previewStackUp` in the same checkout rebuilds only what changed and recreates the containers in
  place, which is how you load an edit. The stack serves the code as it was at the last
  `previewStackUp`, not your working tree live.
- **When it is ready, it prints the frontend URL** (`https://localhost:18081`), every throwaway
  account as `username / password / roles`, and the teardown task. `test-admin` / `test-admin-pw`
  is an administrator with a membership in the IRIDIUM Staffel. Every account's password is
  `<username>-pw`. These values exist only in `realm-export.e2e.json` and this local stack. They
  are published on purpose and are never real credentials (root `AGENTS.md`, Testing).
- **The ports are fixed, so only one stack runs per machine.** Before compose starts, the task
  checks every host port of the dev profile (18080, 18081, 11261, 11262, 15432, 15433, 6379). If a
  container of **another** checkout publishes one of them, the task fails and names that
  container. **Never tear down another checkout's or another agent's stack on your own.** Report
  the container and its compose project (`docker inspect <name> --format
  '{{index .Config.Labels "com.docker.compose.project.working_dir"}}'` shows the checkout) and ask
  the owner, or the user, first.
- **Memory:** each image build runs Gradle with a 2 GiB heap inside the Docker VM. The harness
  therefore builds the four images one after another before `up`, which fits a 4 GiB VM. Another
  session's image build at the same time can still exhaust it (`cannot allocate memory`, or a
  backend restarted with exit 137). `docker buildx history ls` shows running builds; start when it
  lists none.

## Look at it

- **Claude Code (desktop app):** start the browser pane with `preview_start` and the name
  `preview-stack` (`.claude/launch.json`), or pass `url: https://localhost:18081`. Read the page
  with `get_page_text` / `read_page`, and use screenshots for the visual judgement.
- **Other agents and people:** any browser, or a Playwright script against the same URL.
- **TLS:** the frontend serves the committed throwaway certificate from `docker/test-tls/`
  (ADR-0139), whose CA the host does not trust. The browser shows a certificate warning for
  `https://localhost:18081`. **An agent does not click past it, turn certificate checks off or
  install the CA.** That is a trust decision for the user. In Claude Code's browser pane, the user
  proceeds once on the warning page, and the pane remembers it for the session. Installing the CA
  into the user's trust store removes the warning for good, at the price of trusting a CA whose
  key is public.
- **Sign-in:** "Anmelden" redirects to the stack's Keycloak at
  `http://keycloak.localhost:18080`. The preview stack stacks `docker-compose.preview.yml`, which
  makes Keycloak advertise that name. A browser resolves every `*.localhost` name to the loopback,
  where Keycloak is published on `127.0.0.1` only, and the containers reach it through a network
  alias. The E2E suites keep `host.docker.internal`, which their browsers map to `127.0.0.1` by a
  resolver rule that an ordinary browser does not have; Docker Desktop resolves that name to the
  host's LAN address, where a loopback-only Keycloak refuses the connection. Because both the
  frontend and the login page are on loopback names, an agent may sign in with the printed
  throwaway accounts itself; it never needs the user for that.
- **Data:** the seed holds the catalogue, the IRIDIUM Staffel's Profit release, materials, an
  orderable item and the accounts' roles. Create everything else the screen needs through the UI.
  For a screen you will check again and again, add a seeding method to `BackendSeeder` instead of
  clicking it together every time.
- **Device classes:** check every class the change touches (`ui-design-system.md`): at least a
  phone width (375 × 812) and a desktop width (1280 × 800), with tablet and ultra-wide when the
  layout changes there. In the browser pane, use `resize_window`, and reset it to `desktop` when
  you are done.
- **Report what you saw,** with the device classes and screens you actually looked at. A screen
  you did not open is unverified; say so.

## Stop it

```bash
./gradlew :frontend:previewStackDown
```

Always stop the stack when you are done. It blocks every other checkout's E2E run and preview
while it runs. The teardown removes the containers and volumes, which means all data you created,
and keeps the container logs under `frontend/build/e2e/`.

If the start fails, the task tears down what it started and the failure names the log under
`frontend/build/e2e/`.
