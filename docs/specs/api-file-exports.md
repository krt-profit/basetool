> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-11-24.
> **Owner area:** API · **Related ADRs:** ADR-0409, ADR-0410

# File exports follow the current standard of their format

## Context & goal

The Basetool hands members and admins files: PDF documents (audit logs, account statements, the
three-month report, handover protocols, the data export's readable half), JSON documents (the audit
exports and the Art. 15 / Art. 20 data export) and one CSV file (the purchasing report). A file
leaves the application and is opened, archived and re-imported by software we do not control, so
each export follows the current published standard of its format, and a test proves it.

The editions in force on 2026-11-24:

| Format                | Standard                                                                                   |
|-----------------------|--------------------------------------------------------------------------------------------|
| Download header       | RFC 6266 (`Content-Disposition`), RFC 8187 (`filename*`), RFC 9110                         |
| PDF                   | ISO 32000-2:2020 (PDF 2.0), ISO 19005-4:2020 (PDF/A-4), RFC 8118 (`application/pdf`)       |
| Tagged PDF            | ISO 14289-2:2024 (PDF/UA-2), Well-Tagged PDF 1.0 (WTPDF, accessibility and reuse levels)   |
| JSON                  | RFC 8259 / STD 90, ECMA-404 2nd edition, RFC 3339 timestamps                               |
| CSV                   | RFC 4180 with the `text/csv` registration as updated by RFC 7111                           |

## Requirements

### REQ-API-025 — Every download is an RFC 6266 attachment

Every response that delivers a file — from the backend and from the frontend proxy that relays it
— carries `Content-Disposition: attachment` with a quoted US-ASCII `filename`. When the name leaves
US-ASCII, an RFC 8187 `filename*=UTF-8''…` follows the ASCII fallback (RFC 6266 appendix D). The
`form-data` disposition belongs to `multipart/form-data` bodies (RFC 7578) and is never sent on a
response. The backend builds every download through `AttachmentResponses`, the frontend through
`BackendDownloads`.

**Acceptance**

- [x] A backend PDF, JSON and CSV download is `attachment; filename="…"`, and `filename*` appears
  only for a non-ASCII name.
- [x] The frontend's handover, data-export fallback, audit, bank and purchasing downloads are
  `attachment` dispositions.

**Enforced by:** `AttachmentResponsesTest`, `BackendDownloadsTest`,
`JobOrderHandoverReportControllerTest`, `JobOrderHandoverReportProxyControllerTest`,
`PurchasingProxyControllerTest`, `DataExportDocumentsTest` · **Code:**
`platform/api/AttachmentResponses`, `frontend/kernel/web/BackendDownloads`

### REQ-API-026 — Every backend PDF is PDF/A-4 on PDF 2.0

Every PDF the backend renders is opened through `KrtPdfDocuments` (REQ-API-029) and written as PDF/A-4
(ISO 19005-4:2020) on PDF 2.0 (ISO 32000-2:2020):

- the header is `%PDF-2.0`, the trailer carries an `/ID`;
- the catalog holds an XMP metadata stream with `dc:title`, `dc:language`, `dc:format`,
  `xmp:CreatorTool`, the creation, modification and metadata dates, `pdf:Producer` and the PDF/A
  identification `pdfaid:part = 4`, `pdfaid:rev = 2020`;
- the catalog holds the language `/Lang (de)`, an sRGB output intent (`/GTS_PDFA1`) for the
  DeviceRGB colours and the logo, and `DisplayDocTitle`, so a viewer shows the title;
- every font is embedded — the text, spacers included, is set in the bundled Lato;
- the `Info` dictionary holds only `ModDate`, written in the PDF 2.0 date form `D:YYYYMMDDHHmmSSZ`,
  beside a catalog `PieceInfo` entry with the same date (ADR-0409: OpenPDF always writes an
  `Info` dictionary, and PDF/A-4 6.1.3 admits one only in this form).

The document title is the heading of its first page; metadata and heading cannot drift apart.

**Acceptance**

- [x] veraPDF validates every document kind against PDF/A-4 without a failed rule: the audit log
  (with and without rows), the account statement, the three-month report, the material handover
  report and its preview, the item handover delivery note and the data-export PDF.
- [x] The same validator rejects a plain OpenPDF file for the `Info`, XMP, font and colour-space
  rules, so a green run means the rules were checked.
- [x] The header, the `Info` dictionary, the XMP packet, the catalog entries and the embedded
  fonts are asserted directly.

**Enforced by:** `PdfConformanceProfileTest`, `AuditLogPdfFormatTest`, `BankReportServiceTest`,
`JobOrderHandoverReportServiceTest`, `JobOrderItemHandoverReportServiceTest`,
`DataExportIntegrationTest` (all through `support.PdfConformance`) · **Code:**
`platform/api/pdf/KrtPdfDocuments`, `platform/internal/pdf/PdfConformanceProfile`

### REQ-API-029 — Every backend PDF is tagged PDF/UA-2 and Well-Tagged PDF

Every PDF the backend renders is opened through `KrtPdfDocuments` (`platform.api.pdf`); its
implementation `TaggedKrtPdfDocument` (`platform.internal.pdf`) tags every block in the PDF 2.0
standard structure namespace (`http://iso.org/pdf2/ssn`) and marks everything else as artifact
(ADR-0410). The `KrtPdfDocument` interface offers blocks only and never the OpenPDF `Document`,
so no content can bypass the tagging.

| Content                                                        | Structure                                                                                  |
|----------------------------------------------------------------|--------------------------------------------------------------------------------------------|
| The document                                                   | one `Document`, the structure tree root's only child                                       |
| Document title                                                 | `H1`                                                                                       |
| Section headings                                               | `H2`                                                                                       |
| Notes, notices, the footer line                                | `P`                                                                                        |
| Key/value meta tables                                          | `Table` > `TR` > `TH` (`Scope Row`) + `TD`                                                 |
| Data tables                                                    | one `Table` across every page; first `TR` of `TH` (`Scope Column`), body rows of `TD`     |
| Detail and empty-state rows of a data table                    | one `TD` with `ColSpan` = column count                                                     |
| Charts                                                         | `Figure` with `Alt` and a `Layout` `BBox` and `Placement Block`                            |
| Page background, accent bar, title rule, cell fills, borders   | artifact, `Type Layout`                                                                    |
| Logo, column headers repeated after a page break               | artifact, `Type Pagination`                                                                |

- A paragraph or a table row continued on the next page stays one element.
- Content elements are written as soon as they are complete, so a table of any length keeps one
  row's elements in memory, plus one object reference per tagged cell for the parent tree OpenPDF
  writes at close; the 100 000-row audit log (REQ-AUDIT-001) stays renderable.
- The XMP packet declares PDF/UA-2 (`pdfuaid:part = 2`, `pdfuaid:rev = 2024`) and carries a
  `pdfd:declarations` bag (namespace `http://pdfa.org/declarations/`) with
  `http://pdfa.org/declarations/wtpdf#accessibility1.0` and `…#reuse1.0`, each with
  `pdfd:claimBy` and `pdfd:claimDate`.
- The catalog's `/Lang (de)`, `DisplayDocTitle` and `dc:title` of REQ-API-026 apply.
- The file is fully compressed: Flate content streams and the structure elements in object
  streams (ISO 32000-2 7.5.7). The 100 000-row audit log is about 26 MB and renders within a
  peak of about 0.5 GB at the production heap of 1.17 GB.

**Acceptance**

- [x] veraPDF validates a document built from every block — title, key/value table, heading, a
  data table over several pages with detail and empty cells, an empty-state table, note, notice,
  figure, footer — against PDF/A-4, PDF/UA-2, WTPDF accessibility and WTPDF reuse in one run
  without a failed rule.
- [x] A paragraph longer than a page and a table row taller than a page conform as well and stay
  one element each.
- [x] Content outside marked content fails PDF/UA-2 rule 8.2.2-1 and both WTPDF levels, so a green
  run means the rules ran.
- [x] The structure tree reads back as one `Document` in the PDF 2.0 namespace with headings in
  visual order, one `TH` row with column scope per data table, row-scoped `TH` in key/value
  tables, `ColSpan` on full-width rows and `Alt` plus `BBox` on the figure.
- [x] Repeated column headers are pagination artifacts and absent from extracted text.
- [x] A content element leaves the structure tree root once it ends.
- [x] veraPDF validates every document kind against the same four profiles without a failed
  rule: the audit log (with and without rows), the account statement, the three-month report,
  the material handover report and its preview, the item handover delivery note and the
  data-export PDF.
- [x] Every document kind reads back as `H1` title, meta table, `H2` headings, data tables whose
  first row is column-scoped `TH` and a footer `P`; every chart of the three-month report is a
  `Figure` whose `Alt` names the account, the window and the opening, closing, lowest and
  highest balance.
- [x] The full audit-log cap of 100 000 rows renders below 40 MB.
- [x] The human checklist below is filled in for every document kind.

**Enforced by:** `TaggedKrtPdfDocumentTest`, `PdfStructureTreeTest`, `PdfConformanceProfileTest`,
`AuditLogPdfFormatTest`, `BankReportServiceTest`, `JobOrderHandoverReportServiceTest`,
`JobOrderItemHandoverReportServiceTest`, `DataExportIntegrationTest` (through
`support.PdfConformance` and `support.TaggedStructure`) · **Code:**
`platform/api/pdf/KrtPdfDocuments`, `platform/internal/pdf/TaggedKrtPdfDocument`,
`platform/internal/pdf/PdfStructureTree`, `platform/internal/pdf/PdfConformanceProfile`

**Human checklist** — what veraPDF cannot judge, checked on the rendered test documents (page
renders and the structure tree read back) on 2026-11-24:

| Document kind                         | Alternate text                                              | Headings and reading order                                        | Table headers                                                         | Nothing real as artifact                         | Language            |
|---------------------------------------|-------------------------------------------------------------|-------------------------------------------------------------------|-----------------------------------------------------------------------|--------------------------------------------------|---------------------|
| Audit log (activity and bank)         | no figure                                                   | `H1` title, `H2` events; meta, table, footer in visual order      | meta labels head their rows; time, actor, event, subject, details head their columns | background, rules, fills, borders, logo, repeated header | German (`de`)       |
| Account statement                     | no figure                                                   | `H1`, `H2` bookings; meta, bookings, footer in visual order       | as above; date, type, holder, counterparty, amount, balance           | as above                                         | German              |
| Three-month report                    | each chart names account, window, opening, closing, lowest and highest balance — everything the axes show | `H1`, then per account `H2` account, `H2` chart, holders `H2`; visual order | summary labels head their rows; booking and holder columns headed     | as above; the chart itself is the `Figure`       | German              |
| Material handover report and preview  | no figure                                                   | `H1`, `H2` materials; visual order                                | material, location, amount, quality                                   | as above                                         | German              |
| Item handover delivery note           | no figure                                                   | `H1`, `H2` items; visual order                                    | item, amount                                                          | as above                                         | German              |
| Data-export PDF                       | no figure                                                   | `H1`, `H2` per inventory, printed section and out-of-scope list   | section, rows, legal basis; field and value                           | as above                                         | German              |

- **Contrast** (WCAG 2.2 AA asks 4.5:1 for text): white on the panel 16.7:1, on the canvas 19.2:1,
  on the header bar 18.0:1; label gray on the panel 6.4:1; light gray notes and detail rows 11.9:1
  and more; orange headings on the canvas 6.8:1, the orange chart line on its panel 5.9:1.
- **Colour is never the only carrier of meaning**: every amount is white and carries its sign; the
  orange accent marks headings and the header underline only.
- **Full-width rows**: a booking's detail row and a table's empty-state row span every column, so
  the scope algorithm relates them to every column header. Their text names its own labels
  (`Begründung: …`), so the relation adds nothing misleading; WTPDF requires no `Headers`
  attribute for it.
- **The logo** is pagination artifact: it repeats on every page and carries no information the
  title does not.

### REQ-API-027 — JSON exports follow RFC 8259

A JSON download is UTF-8 without a byte order mark (RFC 8259 8.1) and is sent as
`application/json` without a `charset` parameter (RFC 8259 11). Member names within an object are
unique, a non-finite floating-point value is written as the string `"NaN"` / `"Infinity"` rather
than as a bare token (RFC 8259 6), and an instant is an RFC 3339 string in UTC with upper-case `T`
and `Z`.

**Acceptance**

- [x] The data-export JSON starts with `{`, decodes as strict UTF-8, parses with duplicate
  detection on, writes `NaN` as a string and the generation instant as `…T…Z`.
- [x] The JSON attachment carries `application/json` without parameters.

**Enforced by:** `DataExportDocumentsTest`, `AttachmentResponsesTest`,
`DataSubjectRightsAuditIntegrationTest` · **Code:** `privacy/api/DataExportDocuments`,
`audit/web/AuditAdminController`, `bank/web/BankAdminController`

### REQ-API-028 — The CSV export follows RFC 4180

The purchasing report's CSV file (REQ-PUR-023) is RFC 4180: a header row, comma-separated fields,
CRLF after every record, and a field holding a comma, a double quote, CR or LF is quoted with its
inner quotes doubled. It is sent as `text/csv; charset=UTF-8; header=present` (RFC 7111 5.1), and
the bytes are UTF-8 without a byte order mark.

**Acceptance**

- [x] The file reads `Deal,Monat,…\r\n` followed by one CRLF-terminated record per deal.
- [x] A field with a comma, quote, CR/LF round-trips through RFC 4180 quoting.
- [x] Backend and frontend send `text/csv` with `charset=UTF-8` and `header=present`.

**Enforced by:** `PurchasingReportServiceTest`, `PurchasingReportControllerTest`,
`PurchasingProxyControllerTest` · **Code:** `purchasing/internal/PurchasingReportService`,
`purchasing/web/PurchasingReportController`, `frontend/purchasing/web/PurchasingProxyController`

## Out of scope

- **Spreadsheet behaviour.** RFC 4180 does not define how a spreadsheet opens a file; REQ-PUR-023
  names the supported import workflow.
- **Exchange documents** (`/exchange/v1/openapi.json`, `/exchange/v1/schemas/*`) are served inline,
  not downloaded, and follow REQ-XCH-011.

## Open questions

None.
