---
name: ui-preview
description: Look at a Profit Basetool UI change in a real browser, signed in, on this checkout's running and seeded E2E stack (previewStackUp / previewStackDown). Use before calling a template, CSS or frontend-JS change done, when the user asks to see, check or screenshot a page, or asks how a screen looks on a phone. Covers starting the stack, signing in with the throwaway accounts, the device classes, what to report, and tearing it down.
---

# UI preview

The workflow and its rules are in [`docs/agents/ui-preview.md`](../../../docs/agents/ui-preview.md),
binding for every agent (REQ-OPS-057). Read it first. The steps below are the Claude Code way
through it.

1. **Check the ports:** `docker ps --format '{{.Names}}'`. Containers named `e2e-<other checkout>-…`
   belong to another checkout or agent. Do not stop them; ask the user.
2. **Start:** `./gradlew :frontend:previewStackUp`, run in the background (10–15 minutes on the
   first start). Wait for the completion notice; do not poll.
3. **Open:** `preview_start` with `name: "preview-stack"` (`.claude/launch.json`). The first load
   stops at `ERR_CERT_AUTHORITY_INVALID` (the throwaway test CA). Ask the user to proceed on the
   warning page in the browser pane; never bypass it yourself. Then sign in yourself through
   "Anmelden": the login page is on `keycloak.localhost`, a loopback name, so the throwaway
   accounts the task printed (for example `test-admin` / `test-admin-pw`) are yours to enter.
4. **Look:** `read_page` / `get_page_text` for structure and text, screenshots for the visual
   judgement. Use `resize_window` with `mobile` (375 × 812) and `desktop`, plus a tablet or
   ultra-wide size where the layout changes, and reset to `desktop` afterwards.
5. **Load an edit:** run `previewStackUp` again in the same checkout. It rebuilds and recreates in
   place.
6. **Report:** name the screens and device classes you actually saw, and what did not look right.
7. **Stop:** `./gradlew :frontend:previewStackDown`, always, also after a failed check.
