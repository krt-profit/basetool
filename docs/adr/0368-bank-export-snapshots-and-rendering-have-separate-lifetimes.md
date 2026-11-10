# ADR-0368 — Bank export snapshots and rendering have separate lifetimes

- **Status:** Accepted
- **Date:** 2026-11-10
- **Deciders:** @greluc
- **Related:** [REQ-BANK-014/015](../specs/bank.md), audit finding #3287

## Context

Statements retained every selected posting, related ledger leg and finished PDF while rendering
inside a database transaction. Management reports also retained every chart vertex and PDF table
row. A valid long period could therefore increase heap use and hold a connection throughout PDF
generation. Restricting the period or dropping rows would change the financial export contract.

## Decision

1. Capture one repeatable-read snapshot into immutable JSON frames of at most 500 postings and
   their resolved holder/counterparty display values. Fetch only relevant holder and transfer
   legs. Management accounts and holder totals are paged in the same snapshot. Entity state
   remains inside the query transaction; account labels and totals are detached data.
2. Close the snapshot transaction before rendering. The org-unit authorization read also ends
   before generation. Export coordinators use `NEVER` propagation so a caller cannot silently
   retain or suspend a connection across rendering.
3. Render tables by chunk into a PDF file. Keep every itemized posting and exact financial total.
   The chart retains chronological first/last/minimum/maximum points per horizontal PDF point,
   preserving exact endpoints and extrema while bounding vector content by display resolution.
   OpenPDF still retains output-page metadata; this decision does not claim constant total heap
   independent of output-page count.
4. Create an owner-only per-export directory under the validated absolute Spring property
   `app.bank.exports.directory`, defaulting to the JVM temporary directory. A filesystem must
   support POSIX permissions or owner ACLs; otherwise initialization fails closed. Files contain
   no identifying path components. No new host environment allow-list entry is required.
5. Return a reopenable Spring `Resource`. A request cleanup scope removes its directory after
   synchronous response conversion, including HEAD, empty-body responses, failures and client
   disconnects. Snapshot/render failures remove their storage immediately and record no success.
   The existing export audit is written in a writable transaction after successful rendering.

## Consequences

Row, leg, table and chart working sets are bounded by chunk or viewport size. Disk use follows
the complete snapshot and PDF size until response completion. No ledger-period or row-count cutoff
is introduced, and the existing routes, media types, filenames, redaction and browser downloads
remain compatible. Tests can materialize controlled fixture PDFs explicitly and must close their
resources. Resource profiles report workload, heap and timings without claiming improvement
without a comparable baseline measurement.

Audit-log and handover PDF generators remain separate workloads. Their existing limits and
working sets must be characterized independently; the Bank snapshot schema is not a general
export framework.
