# ADR-0409 — Every file export follows its format's current standard; PDFs are PDF/A-4

- **Status:** Accepted
- **Date:** 2026-11-24
- **Deciders:** @greluc
- **Related:** spec REQ-API-025 · REQ-API-026 · REQ-API-027 · REQ-API-028 · REQ-PUR-023 ·
  REQ-API-008 · `platform/api/AttachmentResponses` · `platform/api/KrtPdfSupport` ·
  `platform/api/PdfArchiveProfile` · `frontend/kernel/web/BackendDownloads`

## Context

The Basetool exports PDF documents, JSON documents and one CSV file. An audit of every export
against the standards in force on 2026-11-24 — RFC 6266 / RFC 8187 for the download header,
ISO 32000-2:2020 and ISO 19005-4:2020 for PDF, RFC 8259 for JSON, RFC 4180 with RFC 7111 for CSV —
found these deviations:

- **Download header.** The backend's PDF and audit-JSON downloads and two frontend proxies (the
  handover report and the data-export fallback) sent `Content-Disposition: form-data;
  name="attachment"`. `form-data` is the disposition of a `multipart/form-data` part (RFC 7578);
  browsers only saved the file because RFC 6266 4.2 tells them to treat an unknown type like
  `attachment`. The tests checked `contains("attachment")`, which the wrong header passed.
- **PDF.** OpenPDF 3.0.6 writes the header `%PDF-2.0` by default, so every PDF claimed
  ISO 32000-2. It carried no XMP metadata, no title and no language; its `Info` dictionary held the
  `Producer` key PDF 2.0 deprecates, and dates in the PDF 1.x form with a trailing apostrophe; the
  three-month report set two spacer lines in the unembedded standard font Helvetica.
- **CSV.** The purchasing report separated fields with semicolons and records with LF; RFC 4180
  requires commas and CRLF, and the media type lacked `header=present`.
- **JSON** already met RFC 8259: UTF-8 without a BOM, `application/json` without parameters,
  unique member names, `NaN` written as a string, RFC 3339 instants.

## Decision

**Every file export follows the current standard of its format, and a test proves each one.**

1. **Downloads are RFC 6266 attachments.** The backend builds every download through
   `AttachmentResponses` — `attachment; filename="…"`, with an RFC 8187 `filename*` only for a
   non-ASCII name — and the frontend through `BackendDownloads`. `form-data` is never sent on a
   response.
2. **Every backend PDF is PDF/A-4 (ISO 19005-4:2020) on PDF 2.0.** `KrtPdfSupport.open` applies
   `PdfArchiveProfile`: the explicit `%PDF-2.0` header, an XMP packet with title, language,
   generator, dates, producer and `pdfaid:part = 4` / `pdfaid:rev = 2020`, the catalog language,
   an sRGB output intent (the JDK's sRGB profile) and `DisplayDocTitle`; the document title is
   also the heading, so the two cannot drift apart. All text is set in the embedded Lato.
3. **The `Info` dictionary is reduced to `ModDate` beside a catalog `PieceInfo`.** OpenPDF writes
   a trailer `Info` dictionary on every close and offers no way to omit it. PDF/A-4 6.1.3 admits
   one only when the catalog has a `PieceInfo` entry, and then with nothing but `ModDate`; PDF 2.0
   requires `ModDate` exactly in that case. The profile therefore stores the generation instant as
   this application's page-piece data (`/PieceInfo << /ProfitBasetool << /LastModified … >> >>`,
   ISO 32000-2 14.5) and empties `Info` down to the same `ModDate`, in the PDF 2.0 date form
   `D:YYYYMMDDHHmmSSZ`. When OpenPDF can omit `Info`, both entries go.
4. **veraPDF validates every PDF in the build.** `org.verapdf:validation-model-jakarta` (test
   scope, GPLv3+/MPLv2+) checks each document kind against PDF/A-4 through
   `support.PdfA4Conformance`; a negative test proves the validator rejects a plain OpenPDF file,
   so a green run means the rules ran.
5. **The CSV export is RFC 4180:** commas, CRLF, a header row, quoting of fields with a comma, a
   quote or CR/LF, and `text/csv; charset=UTF-8; header=present`. REQ-PUR-023 is amended from
   semicolons to commas; its lossless rule (formula prefixes stay literal) is unchanged.

## Alternatives considered

- **Plain PDF 2.0 without an archival profile.** It fixes the deviations but leaves no
  independent check in the build: veraPDF validates PDF 2.0 itself only through its separate
  Arlington-model artifacts, which reuse the PDF/A build's package names and would need a test
  classpath of their own.
  PDF/A-4 gives the exports an archival guarantee — statements, audit logs and the data export are
  records — and a reference validator. Rejected in favour of PDF/A-4.
- **Stripping `Info` from the trailer bytes after OpenPDF closes the file.** It would need a
  search for the last `trailer` keyword in a binary stream and leave an unreferenced object
  behind. Rejected as a byte-level workaround.
- **Re-saving through Apache PDFBox to drop `Info`.** A second PDF library at runtime for one
  dictionary entry. Rejected.
- **PDF/UA-2 (ISO 14289-2:2024) in this change.** It needs a structure tree in the PDF 2.0
  namespace around every heading, paragraph, table cell and figure, with decoration marked as
  artifact. OpenPDF 3.0.6 tags nothing that `Document.add` lays out and has no namespace
  constants, but a hand-tagged OpenPDF document — content drawn through `ColumnText` inside marked
  content, `/NS` and `/Namespaces` set as plain dictionary entries — passes veraPDF's PDF/UA-2 and
  PDF/A-4 profiles in one file. That tagging layer is a change of its own; the owner chose it over
  iText 9 (AGPL-3.0-or-later), the only Java library with built-in PDF/UA-2.
- **Keeping the semicolon for German spreadsheets.** RFC 4180 and the `text/csv` registration name
  the comma; REQ-PUR-023 already places double-click opening outside the supported workflow.
  Rejected on the owner's decision.

## Consequences

- Every PDF opens in a viewer with its title and language, prints with a defined colour space and
  passes an archival validation; each file grows by the XMP packet and the embedded sRGB profile,
  a few kilobytes.
- Spreadsheet users import the purchasing CSV with the comma as separator.
- `KrtPdfSupport.open(target, title)` replaces `open(target)` plus `addTitle(krt, title)`; a new PDF
  cannot be opened without a title.
- The backend's test classpath gains veraPDF with Saxon-HE; its rules load once per test JVM.
- A character outside Windows-1252 still cannot be shown, because Lato is embedded with the
  WinAnsi encoding (REQ-BANK-017). That is a content limit, not a format deviation.
