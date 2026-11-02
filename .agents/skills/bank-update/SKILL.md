---
name: bank-update
description: Use this skill to write the monthly KRT-Bank report ("Bank Update") for the DAS KARTELL forum — the post that reports the Kartellbank's inflows and outflows for one calendar month, with the KRT account (the CARTEL account) broken out beside the bank total. It reads the figures from the production ledger read-only, reconciles them, and delivers a finished browser page whose "Formatiert kopieren" button puts forum-safe markup on the clipboard for the forum's CKEditor — never Markdown, never HTML source to hand-edit. Trigger it whenever the user wants the monthly bank report — e.g. "Bank Update für September", "Bankbericht für letzten Monat", "erstelle das Bankupdate", "Monatsbericht der KRT-Bank" — even when they don't name the month. Without a month it continues at the month after the last one reported, using a local progress marker (.bank-update-state.json) in the shared git directory.
---

Read and follow [`.claude/skills/bank-update/SKILL.md`](../../../.claude/skills/bank-update/SKILL.md), the original of this skill; this file only makes it
visible to agents that discover skills under `.agents/skills/` (REQ-OPS-055).
