# ADR-0410 — Every backend PDF is tagged: PDF/UA-2 and Well-Tagged PDF through a layout layer of our own on OpenPDF

- **Status:** Accepted
- **Date:** 2026-11-24
- **Deciders:** @greluc
- **Related:** spec REQ-API-026 · REQ-API-029 · ADR-0409 · ADR-0047 ·
  `platform/api/pdf/KrtPdfDocuments` · `platform/internal/pdf/TaggedKrtPdfDocument` ·
  `platform/internal/pdf/PdfStructureTree` · `platform/internal/pdf/PdfConformanceProfile`

## Context

ADR-0409 made every backend PDF a PDF/A-4 file and left accessibility for a change of its own.
PDF/UA-2 (ISO 14289-2:2024) is not a legal duty for the Basetool — the BFSG covers services with
consumer contracts only — but a quality goal. Well-Tagged PDF 1.0 (WTPDF, PDF Association 2024)
defines two conformance levels on the same tagging: *accessibility*, which a PDF/UA-2 file meets,
and *reuse*, for text extraction and reflow.

- **The library.** OpenPDF 3.0.6 tags nothing that `Document.add(Paragraph | PdfPTable)` lays out,
  and a `PdfPCellEvent` fires only after a cell is written. iText 9.8.1 is the only Java library
  with built-in PDF/UA-2, but it is AGPL-3.0-or-later, and its network clause would oblige the app
  to offer the deployed source to every user. FOP 2.12, openhtmltopdf 1.1.117, Typst 0.15.1 and
  Flying Saucer 10.8 reach PDF/UA-1 or no PDF/A-4; PDFBox is a low-level library only.
- **The spike.** A hand-tagged OpenPDF document — content drawn through `ColumnText` inside
  marked content, `/NS` and `/Namespaces` set as plain dictionary entries — passes veraPDF's
  PDF/UA-2 and PDF/A-4 profiles in one file; the untagged variant fails.
- **OpenPDF's structure tree.** `PdfWriter.setTagged()` gives marked-content identifiers, the
  pages' `StructParents` and the parent tree. But OpenPDF keeps every `PdfStructureElement` in its
  parent until the file closes, and its tree writer resolves every kid through the reader, so an
  element cannot be written early. The audit log may hold 100 000 rows — about 600 000 elements.
- **WTPDF.** veraPDF 1.30.4 ships both WTPDF profiles. Compared rule by rule, the accessibility
  level is the PDF/UA-2 profile without the `pdfuaid` identification plus a PDF Declaration
  `http://pdfa.org/declarations/wtpdf#accessibility1.0`; the reuse level is a subset of it plus
  `…#reuse1.0`. The only reuse-specific provision of the specification concerns lists continued
  across list elements, which the Basetool's PDFs do not have.

## Decision

**Every backend PDF is drawn through a tagging layout layer of our own on OpenPDF's marked-content
API and is a PDF/A-4, PDF/UA-2 and WTPDF (accessibility and reuse) file.**

1. **One layer, one entry point.** `platform.api.pdf.KrtPdfDocuments` opens every backend PDF
   as a `KrtPdfDocument`, an interface of blocks only: heading, note, notice, key/value table,
   data table, figure, section gap, page break, footer. Its implementation
   `TaggedKrtPdfDocument` and the layout classes live in `platform.internal.pdf`, which depends
   on the `api` interfaces and never the reverse (ADR-0047). The OpenPDF `Document` is never
   handed out, so no content can bypass the tagging; the renderers receive `KrtPdfDocuments` as
   a Spring bean.
2. **The structure.** One `Document` element in the PDF 2.0 namespace under the root; the title as
   `H1`, section headings as `H2`, notes, notices and the footer as `P`; key/value tables as
   `Table` > `TR` > `TH` (scope row) + `TD`; data tables as one `Table` across all pages whose
   first `TR` holds `TH` with scope column, body rows `TD`, detail and empty-state rows one `TD`
   with `ColSpan`; charts as `Figure` with `Alt` and a layout `BBox`. Page background, accent bar,
   title rule, cell fills and borders are artifacts of type `Layout`; the logo and repeated table
   headers of type `Pagination` (WTPDF 8.3.1).
3. **A bounded structure tree.** Group elements (`Document`, `Table`, `TR`) are plain dictionaries
   written when they end. Content elements are OpenPDF `PdfStructureElement`s, so OpenPDF still
   assigns the marked-content identifiers and the parent tree; they are created under the
   structure tree root, written as soon as their content is complete and detached again. At close
   the root's `K` becomes the reference of the `Document` element, which OpenPDF's tree writer
   leaves alone. Memory holds the open groups and one table row; only OpenPDF's parent tree keeps
   one object reference per tagged cell until close.
4. **Layout.** Text is set through `ColumnText`. A table row is measured, drawn and written at
   once: a row that does not fit the page starts on the next one, a row taller than a page
   continues there inside the same cells, and the column headers are repeated as artifacts.
   Streaming renderers (REQ-BANK-014/015) keep their bounded working set without `flushContent`.
5. **The claims.** `PdfConformanceProfile` adds the PDF/UA identification `pdfuaid:part = 2`,
   `pdfuaid:rev = 2024` and a `pdfd:declarations` bag with both WTPDF declarations, each with
   `claimBy` and `claimDate`. The namespace is `http://pdfa.org/declarations/` and the URIs use the
   corrected form without a slash before `#`, as veraPDF reads them.
6. **veraPDF validates every document kind** against PDF/A-4, PDF/UA-2 and both WTPDF levels in
   one run (`support.PdfConformance`); a negative test proves untagged content fails, and
   structure assertions (`support.TaggedStructure`) pin heading order, header scopes and figure
   descriptions.
7. **What a validator cannot judge** — meaningful alternate text, reading order, header
   relationships, nothing real marked as artifact, languages, contrast — is a human checklist per
   document kind in the spec.

## Alternatives considered

- **iText 9.8.1.** Built-in PDF/UA-2, but AGPL-3.0-or-later with the network clause. Rejected by
  the owner.
- **FOP, openhtmltopdf, Typst, Flying Saucer.** PDF/UA-1 or no PDF/A-4. Rejected.
- **PDFBox as layout engine.** Low-level drawing only, and a second PDF library at runtime.
  Rejected.
- **OpenPDF's structure tree as it is.** Holds every element until close; the audit-log cap would
  keep about 600 000 dictionaries alive. Rejected for the bounded tree of point 3.
- **Drawing `PdfPTable` cell by cell with `writeSelectedRows`.** Keeps OpenPDF's cell layout but
  still needs our own pagination, header repetition and row splitting, on canvases internal to
  OpenPDF. Rejected; the layer draws cells with the same padding, fills and borders itself.
- **Claiming PDF/UA-2 alone.** The WTPDF declarations cost two XMP entries and their profiles run
  in the same validation. Rejected on the owner's decision.

## Consequences

- The PDFs look as before; screen readers get headings, table headers and figure descriptions,
  and text extraction skips artifacts, so a repeated table header is read once.
- The logo is embedded once per file instead of once per page; the structure tree adds a few
  kilobytes.
- A new PDF is built from `KrtPdfDocument` blocks; a new block type needs its structure mapping
  and a conformance test, and every renderer takes `KrtPdfDocuments` as a dependency.
- The layer relies on two OpenPDF behaviours: a `PdfStructureElement` may be created with the
  root as parent, and the tree writer passes over a root `K` that is not an array. An OpenPDF
  upgrade that changes either fails the conformance tests.
- Strings outside Windows-1252 still cannot be shown (ADR-0409); WinAnsi is a standard encoding,
  so every glyph maps to Unicode.
