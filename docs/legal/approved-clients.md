# Approved clients of the Exchange API

This is the public record of the client applications approved to use the Basetool's Exchange API on a
member's behalf (REQ-XCH-002, ADR-0217). Section 4 of the terms of use links this list and defines
what it covers: every program that reaches the Basetool's interfaces and is not part of the platform
itself (REQ-SEC-027). The web interface and the Android app are part of the platform and are not
listed; the SC Extractor is a separate program, so it is listed although the operator publishes it.
A third-party client is approved when the owner merges the pull request that adds it here, following
its public application issue; the criteria are in [`docs/exchange/onboarding.md`](../exchange/onboarding.md).

The list records the **approval**: who the client is, who maintains it, which capabilities were
approved and where the approval was decided. It does not mirror the client's runtime state — a
suspension, a minimum version or a revocation lives in the registry that administrators manage under
„Verbundene Anwendungen" and takes effect at once. Widening an approved client's capabilities starts
with a pull request to this file.

The document is outside the consent hash of the terms of use, so changing it asks no member to
consent again (REQ-SEC-028).

| Client id | Product | Publisher and maintainer contact | Approved capabilities | Approval issue | Approval PR | Approved since |
|:----------|:--------|:---------------------------------|:----------------------|:---------------|:------------|:---------------|
| `basetool-sc-extractor` | [Basetool SC Extractor](https://github.com/krt-profit/basetool-sc-extractor), version 2.10.0 or newer | The Basetool's operator (see the Impressum); security contact per [`.github/SECURITY.md`](../../.github/SECURITY.md); its processing is described in the Basetool's privacy policy, section „Begleitende Desktop-Anwendung (Datenimport)" | `exchange.connect`, `exchange.blueprints.read`, `exchange.blueprints.write`, `exchange.drafts.blueprints`, `exchange.drafts.refinery` | [#2092](https://github.com/krt-profit/basetool/issues/2092) | [#2245](https://github.com/krt-profit/basetool/pull/2245) | 2026-09-28 |
| `versekit` | [VerseKit](https://github.com/Xharig/VerseKit), version 3.60.0 or newer | Xharig ([GitHub](https://github.com/Xharig)); security contact per [`SECURITY.en.md`](https://github.com/Xharig/VerseKit/blob/main/SECURITY.en.md) (private vulnerability reporting); privacy statement [`PRIVACY.en.md`](https://github.com/Xharig/VerseKit/blob/main/PRIVACY.en.md) | `exchange.connect`, `exchange.blueprints.read`, `exchange.blueprints.write`, `exchange.stock.read`, `exchange.stock.write`, `exchange.hangar.read`, `exchange.hangar.write`, `exchange.demand.read` | [#2272](https://github.com/krt-profit/basetool/issues/2272) | [#2273](https://github.com/krt-profit/basetool/pull/2273) | 2026-09-28 |
