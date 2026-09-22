# ADR-0007 — Client-side VLM screenshot extraction with manual JSON upload

- **Status:** Accepted
- **Date:** 2026-06-10
- **Deciders:** Lucas Greuloch (@greluc)
- **Related:** epic [#439](https://github.com/krt-profit/basetool/issues/439), [`docs/archive/REFINERY_SCREENSHOT_IMPORT_PLAN.md`](../archive/REFINERY_SCREENSHOT_IMPORT_PLAN.md), [`docs/specs/refinery-screenshot-import.md`](../specs/refinery-screenshot-import.md), [ADR-0008](0008-refinery-extract-json-contract.md)

## Context

Players want refinery orders auto-filled from Star Citizen refinement-terminal
screenshots instead of typing every row. Reading the screenshots requires a vision-language
model. The basetool server is a small self-hosted deployment without a GPU; squadron
members' gaming PCs have capable GPUs. The screenshots contain personal data (player
handle, account balance), and the upstream constraint set (epic §2) demands: free /
open-source only, no server-side inference, and explicit user control over what reaches
the server.

## Decision

Screenshot understanding runs **entirely on the user's PC**: a desktop tool (the
rebranded `basetool-sc-extractor`, separate repo) drives a local Ollama instance with an
open-weights VLM, post-processes the reads deterministically, and writes a
`RefineryExtract` JSON (ADR-0008). The user **manually uploads** that JSON in the
frontend; the backend only performs master-data matching and returns a non-persisted
draft. v1 has **no** direct desktop→backend channel (deferred to Phase 4) and no
server-side image processing of any kind — images never leave the user's machine.

## Consequences

- The server needs no GPU, no model hosting, no image storage, and never sees the
  privacy-sensitive raw captures; only the user-reviewed, text-only extract is uploaded.
- Extraction quality depends on the user's hardware; the desktop tool therefore ships
  hardware preflight, model auto-selection and CPU fallbacks (epic Phase 3).
- The JSON contract becomes a hard cross-repo interface that must be versioned
  (ADR-0008); backend and extractor can evolve independently behind it.
- The flow has one manual hop more than a direct upload; acceptable for v1 and revisited
  in Phase 4 once the manual path has proven the contract.

## Alternatives considered

- **Server-side inference (upload screenshots, backend runs the VLM):** rejected — needs
  GPU capacity the deployment does not have, uploads personal data, and turns model
  updates into server operations concerns.
- **Hosted VLM APIs (OpenAI/Anthropic/Google):** rejected — violates the free/no-cloud
  constraint and uploads gameplay captures to third parties.
- **Classical OCR (Tesseract) instead of a VLM:** rejected — the SC HUD's stylized
  glyphs, AR-marker overlap and table structure defeated plain OCR in earlier
  experiments; a VLM reads the table as a table.
- **Browser-side inference (WebGPU):** rejected — model size and VRAM control are
  impractical in-browser, and the squadron already uses the blueprint desktop extractor,
  so a desktop workflow is established.
