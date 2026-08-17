/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend.service.pdf;

import java.awt.Color;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;

/**
 * Shared KRT-corporate-design layer for every PDF the backend renders (handover reports, bank
 * statement, bank three-month report — epic #556 Phase 3). Centralizes what the two handover
 * services used to duplicate: the DAS KARTELL color palette, the page-background event (dark fill,
 * a thin orange top-accent bar, bottom-right logo), the meta/table cell helpers and — new with this
 * layer — the embedded Lato fonts (design-system typography, REQ-BANK-017).
 *
 * <p>Colour usage follows the design system's action hierarchy (REQ-UI-002 / REQ-UI-003): orange is
 * an <em>accent</em> for headings and identity only (title, section headers, the single line under
 * a table header, the chart line, the top bar, the logo); surfaces are black / {@link
 * #COLOR_DARK_GRAY} / {@link #COLOR_SURFACE_INPUT} and every data-cell grid line is the neutral
 * {@link #COLOR_HAIRLINE}, so the orange never overwhelms the document.
 *
 * <p>Lato is embedded with {@link BaseFont#WINANSI} encoding on purpose: WinAnsi keeps the text
 * operators byte-readable in the (uncompressed, see {@link #open(OutputStream)}) content stream, so
 * the existing plain-byte content assertions and {@code PdfTextExtractor} both keep working, while
 * the glyphs still come from the bundled OFL-licensed TTFs ({@code fonts/OFL.txt}).
 */
@Slf4j
public final class KrtPdfSupport {

  /** Page background fill — pure black, matching the app's HUD canvas. */
  public static final Color COLOR_BLACK = new Color(0x00, 0x00, 0x00);

  /** Panel background for meta rows and odd table rows. */
  public static final Color COLOR_DARK_GRAY = new Color(0x14, 0x14, 0x14);

  /**
   * DAS KARTELL primary orange (design token {@code --color-primary}). Per the design system's
   * action hierarchy (REQ-UI-002) orange marks <em>action and identity</em> only: the title, the
   * section headings, the single accent line under each table header, the balance-chart line, the
   * thin page top-accent bar and the logo. It is deliberately <strong>not</strong> used for surface
   * fills or the borders framing plain data cells — those are {@link #COLOR_SURFACE_INPUT} and
   * {@link #COLOR_HAIRLINE} — so the orange stays an accent and never overwhelms the page.
   */
  public static final Color COLOR_ORANGE = new Color(0xE7, 0x7E, 0x23);

  /** Default body / data-value text on the dark background (design token {@code --data-fg}). */
  public static final Color COLOR_WHITE = new Color(0xFF, 0xFF, 0xFF);

  /** Muted text — footers, empty-state hints and chart axis labels (≈ Grau 1). */
  public static final Color COLOR_LIGHT_GRAY = new Color(0xCC, 0xCC, 0xCC);

  /**
   * Muted key/meta-label gray (design token Grau 2 {@code #646464}). Field labels in the meta and
   * summary blocks are labels, not headings, so they take this neutral gray — never orange.
   */
  public static final Color COLOR_LABEL = new Color(0x64, 0x64, 0x64);

  /**
   * Half-step surface that reads just above the {@link #COLOR_DARK_GRAY} panel, used for table-head
   * fills (design token {@code --color-surface-input} {@code #1C1C1C}) instead of an orange fill.
   */
  public static final Color COLOR_SURFACE_INPUT = new Color(0x1C, 0x1C, 0x1C);

  /**
   * Neutral hairline border / grid color (design token Grau 3 {@code #282828}). Frames every data
   * cell so the table grid is quiet, with orange reserved for the single header-underline accent.
   */
  public static final Color COLOR_HAIRLINE = new Color(0x28, 0x28, 0x28);

  /** Alternating (even) table row background. */
  public static final Color COLOR_TABLE_ROW_ALT = new Color(0x1E, 0x1E, 0x1E);

  /** Embedded Lato Regular — loaded once per JVM from the bundled TTF. */
  private static final BaseFont LATO_REGULAR = loadFont("fonts/Lato-Regular.ttf");

  /** Embedded Lato Bold — loaded once per JVM from the bundled TTF. */
  private static final BaseFont LATO_BOLD = loadFont("fonts/Lato-Bold.ttf");

  /**
   * Footer generation-timestamp format. Always UTC and zone-independent so the footer is an
   * unambiguous audit stamp regardless of the per-request user zone used elsewhere in the document.
   */
  private static final DateTimeFormatter FOOTER_TIMESTAMP =
      DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneOffset.UTC);

  private KrtPdfSupport() {}

  /**
   * One open KRT document: A4 with the standard margins, compression disabled (content assertions
   * read the raw bytes) and the {@link KrtPageBackground} painting every page.
   *
   * @param document the open document to add content to
   * @param writer the writer, for direct-content drawing (separator lines)
   */
  public record KrtDocument(@NotNull Document document, @NotNull PdfWriter writer) {}

  /**
   * Creates and opens an A4 document in KRT corporate design: 40pt side margins, 60pt top/bottom,
   * stream compression off and the dark background + accents + logo on every page.
   *
   * @param target the stream receiving the PDF bytes
   * @return the open document handle; the caller must {@code document().close()} when done
   */
  public static @NotNull KrtDocument open(@NotNull OutputStream target) {
    Document document = new Document(PageSize.A4, 40, 40, 60, 60);
    PdfWriter writer = PdfWriter.getInstance(document, target);
    // Disable stream compression so PDF text content is searchable as plain bytes
    // (required for content-based assertions in tests and basic text extraction).
    writer.setCompressionLevel(0);
    writer.setPageEvent(new KrtPageBackground());
    document.open();
    return new KrtDocument(document, writer);
  }

  /**
   * A Lato-Regular font instance.
   *
   * @param size point size
   * @param color text color
   * @return the font
   */
  public static @NotNull Font regular(float size, @NotNull Color color) {
    return new Font(LATO_REGULAR, size, Font.NORMAL, color);
  }

  /**
   * The embedded Lato-Regular {@link BaseFont}, exposed for direct canvas text drawing (e.g. the
   * axis labels of the three-month report's balance chart) where a {@link Font} wrapper is not
   * enough.
   *
   * @return the Lato-Regular base font
   */
  public static @NotNull BaseFont regularBaseFont() {
    return LATO_REGULAR;
  }

  /**
   * A Lato-Bold font instance (real bold glyphs, not simulated).
   *
   * @param size point size
   * @param color text color
   * @return the font
   */
  public static @NotNull Font bold(float size, @NotNull Color color) {
    return new Font(LATO_BOLD, size, Font.NORMAL, color);
  }

  /**
   * A Lato-Regular font with the italic style flag (skew-simulated by OpenPDF) — used only for
   * empty-state hints.
   *
   * @param size point size
   * @param color text color
   * @return the font
   */
  public static @NotNull Font italic(float size, @NotNull Color color) {
    return new Font(LATO_REGULAR, size, Font.ITALIC, color);
  }

  /**
   * Adds the document title (20pt Lato Bold, orange, uppercase by convention) followed by the
   * orange separator line spanning the content width, then a small spacer.
   *
   * @param krt the open document handle
   * @param title the (already uppercase) title text
   */
  public static void addTitle(@NotNull KrtDocument krt, @NotNull String title) {
    Paragraph paragraph = new Paragraph(title, bold(20, COLOR_ORANGE));
    paragraph.setAlignment(Element.ALIGN_LEFT);
    paragraph.setSpacingAfter(4f);
    krt.document().add(paragraph);

    PdfContentByte cb = krt.writer().getDirectContent();
    float ypos = krt.writer().getVerticalPosition(false);
    cb.setColorStroke(COLOR_ORANGE);
    cb.setLineWidth(0.5f);
    cb.moveTo(40, ypos);
    cb.lineTo(PageSize.A4.getWidth() - 40, ypos);
    cb.stroke();

    krt.document().add(new Paragraph(" ", regular(6, COLOR_WHITE)));
  }

  /**
   * Creates the standard 2-column meta table (1:2 width ratio, full width, 20pt spacing after).
   *
   * @return the empty meta table
   */
  public static @NotNull PdfPTable newMetaTable() {
    PdfPTable table = new PdfPTable(2);
    table.setWidthPercentage(100);
    table.setWidths(new float[] {1f, 2f});
    table.setSpacingAfter(20f);
    return table;
  }

  /**
   * Adds one label/value row to a meta table — muted-gray bold label (a field label, not a heading,
   * so not orange), white value, dark-gray cells without borders.
   *
   * @param table the meta table
   * @param label the (uppercase) label
   * @param value the value text
   */
  public static void addMetaRow(
      @NotNull PdfPTable table, @NotNull String label, @NotNull String value) {
    PdfPCell labelCell = new PdfPCell(new Phrase(label, bold(9, COLOR_LABEL)));
    labelCell.setBackgroundColor(COLOR_DARK_GRAY);
    labelCell.setBorder(Rectangle.NO_BORDER);
    labelCell.setPadding(6f);

    PdfPCell valueCell = new PdfPCell(new Phrase(value, regular(10, COLOR_WHITE)));
    valueCell.setBackgroundColor(COLOR_DARK_GRAY);
    valueCell.setBorder(Rectangle.NO_BORDER);
    valueCell.setPadding(6f);

    table.addCell(labelCell);
    table.addCell(valueCell);
  }

  /**
   * Adds a section header paragraph (12pt Lato Bold, orange, 8pt spacing after).
   *
   * @param krt the open document handle
   * @param text the (uppercase) section title
   */
  public static void addSectionHeader(@NotNull KrtDocument krt, @NotNull String text) {
    Paragraph header = new Paragraph(text, bold(12, COLOR_ORANGE));
    header.setSpacingAfter(8f);
    krt.document().add(header);
  }

  /**
   * Adds a header cell to a data table: white bold uppercase text on the dark half-step surface
   * fill, framed by the neutral hairline grid, with the table's single orange brand accent drawn as
   * a thicker line along the bottom edge. This keeps the header readable and on-brand without the
   * full orange fill that would make orange dominate the page (REQ-UI-002 / REQ-UI-003).
   *
   * @param table the data table
   * @param text the (uppercase) column label
   */
  public static void addTableHeader(@NotNull PdfPTable table, @NotNull String text) {
    PdfPCell cell = new PdfPCell(new Phrase(text, bold(9, COLOR_WHITE)));
    cell.setBackgroundColor(COLOR_SURFACE_INPUT);
    cell.setUseVariableBorders(true);
    cell.setBorderColor(COLOR_HAIRLINE);
    cell.setBorderWidth(0.3f);
    cell.setBorderColorBottom(COLOR_ORANGE);
    cell.setBorderWidthBottom(1.2f);
    cell.setPadding(7f);
    table.addCell(cell);
  }

  /**
   * Adds a body cell to a data table (white 9pt text, neutral gray hairline border).
   *
   * @param table the data table
   * @param text the cell text
   * @param background the row background ({@link #rowBackground(boolean)})
   * @param centered {@code true} centers the text (numeric columns)
   */
  public static void addTableCell(
      @NotNull PdfPTable table, @NotNull String text, @NotNull Color background, boolean centered) {
    addTableCell(table, text, background, centered, COLOR_WHITE);
  }

  /**
   * Adds a body cell with an explicit text color (signed bank amounts use this).
   *
   * @param table the data table
   * @param text the cell text
   * @param background the row background
   * @param centered {@code true} centers the text
   * @param textColor the text color
   */
  public static void addTableCell(
      @NotNull PdfPTable table,
      @NotNull String text,
      @NotNull Color background,
      boolean centered,
      @NotNull Color textColor) {
    PdfPCell cell = new PdfPCell(new Phrase(text, regular(9, textColor)));
    cell.setBackgroundColor(background);
    cell.setBorderColor(COLOR_HAIRLINE);
    cell.setBorderWidth(0.3f);
    cell.setPadding(6f);
    if (centered) {
      cell.setHorizontalAlignment(Element.ALIGN_CENTER);
    }
    table.addCell(cell);
  }

  /**
   * Adds the full-width italic empty-state row to a data table.
   *
   * @param table the data table
   * @param text the localized empty-state text
   * @param colspan number of columns to span
   */
  public static void addEmptyRow(@NotNull PdfPTable table, @NotNull String text, int colspan) {
    PdfPCell cell = new PdfPCell(new Phrase(text, italic(10, COLOR_LIGHT_GRAY)));
    cell.setColspan(colspan);
    cell.setBackgroundColor(COLOR_DARK_GRAY);
    cell.setBorderColor(COLOR_HAIRLINE);
    cell.setBorderWidth(0.3f);
    cell.setPadding(8f);
    table.addCell(cell);
  }

  /**
   * Adds a full-width detail sub-row directly beneath the booking row it belongs to
   * (REQ-BANK-044/-045): the Begr&uuml;ndung and Notiz of a booking, carved out of the main row
   * into an indented secondary line so the row above stays narrow (the account views have little
   * horizontal room). The reason is rendered <em>first</em> — it is the more important field — each
   * value behind a muted bold label. The cell is visually tied to its booking: it drops its top
   * border so it butts against the row above, keeps that row's alternating background band and
   * hangs from a left indent. Call it only for a booking that actually has a reason and/or a note.
   *
   * @param table the data table
   * @param reasonLabel the localized "Begr&uuml;ndung" label
   * @param reason the justification text, or an empty string when the booking has none
   * @param noteLabel the localized "Notiz" label
   * @param note the note text, or an empty string when the booking has none
   * @param staffNoteLabel the localized "Notiz Bankmitarbeiter" label
   * @param staffNote the booking employee's own note (REQ-BANK-054), or an empty string when the
   *     booking has none or the caller must not see it (the Halter-redacted member statement)
   * @param background the parent row's background ({@link #rowBackground(boolean)}) so the sub-row
   *     reads as the same zebra band
   * @param colspan the number of columns to span (the full table width)
   */
  public static void addDetailSubRow(
      @NotNull PdfPTable table,
      @NotNull String reasonLabel,
      @NotNull String reason,
      @NotNull String noteLabel,
      @NotNull String note,
      @NotNull String staffNoteLabel,
      @NotNull String staffNote,
      @NotNull Color background,
      int colspan) {
    Phrase phrase = new Phrase();
    boolean hasReason = !reason.isEmpty();
    boolean hasNote = !note.isEmpty();
    if (hasReason) {
      phrase.add(new Chunk(reasonLabel + ": ", bold(8, COLOR_LABEL)));
      phrase.add(new Chunk(reason, regular(8, COLOR_LIGHT_GRAY)));
    }
    if (hasReason && hasNote) {
      phrase.add(new Chunk("      ", regular(8, COLOR_LIGHT_GRAY)));
    }
    if (hasNote) {
      phrase.add(new Chunk(noteLabel + ": ", bold(8, COLOR_LABEL)));
      phrase.add(new Chunk(note, regular(8, COLOR_LIGHT_GRAY)));
    }
    boolean hasStaffNote = !staffNote.isEmpty();
    if ((hasReason || hasNote) && hasStaffNote) {
      phrase.add(new Chunk("      ", regular(8, COLOR_LIGHT_GRAY)));
    }
    if (hasStaffNote) {
      phrase.add(new Chunk(staffNoteLabel + ": ", bold(8, COLOR_LABEL)));
      phrase.add(new Chunk(staffNote, regular(8, COLOR_LIGHT_GRAY)));
    }
    PdfPCell cell = new PdfPCell(phrase);
    cell.setColspan(colspan);
    cell.setBackgroundColor(background);
    cell.setUseVariableBorders(true);
    cell.setBorderColor(COLOR_HAIRLINE);
    cell.setBorderWidth(0.3f);
    // Drop the top border and its padding so the sub-row butts against — and reads as part of — the
    // booking row above it, rather than as a standalone row.
    cell.setBorderWidthTop(0f);
    cell.setPaddingTop(0f);
    cell.setPaddingBottom(6f);
    cell.setPaddingLeft(18f);
    cell.setPaddingRight(6f);
    table.addCell(cell);
  }

  /**
   * Adds the centered footer line {@code "Generiert von Profit Basetool am <date> <time> UTC"},
   * preceded by a spacer. The generation timestamp is always rendered in UTC — independent of any
   * per-request user zone used for the document's other timestamps — so the footer is an
   * unambiguous audit stamp.
   *
   * @param krt the open document handle
   */
  public static void addFooter(@NotNull KrtDocument krt) {
    krt.document().add(new Paragraph(" ", regular(10, COLOR_WHITE)));
    String generatedAt = FOOTER_TIMESTAMP.format(Instant.now());
    Paragraph footer =
        new Paragraph(
            "Generiert von Profit Basetool am " + generatedAt + " UTC",
            regular(8, COLOR_LIGHT_GRAY));
    footer.setAlignment(Element.ALIGN_CENTER);
    krt.document().add(footer);
  }

  /**
   * The alternating row background for zebra-striped data tables.
   *
   * @param alt {@code true} for the alternate (even) row
   * @return the background color
   */
  public static @NotNull Color rowBackground(boolean alt) {
    return alt ? COLOR_TABLE_ROW_ALT : COLOR_DARK_GRAY;
  }

  /**
   * Loads one bundled TTF as an embedded WinAnsi {@link BaseFont}. A missing font resource is a
   * packaging defect — fail fast with an unchecked exception at class initialization.
   *
   * @param resource the classpath resource path
   * @return the embedded base font
   */
  private static @NotNull BaseFont loadFont(@NotNull String resource) {
    try (InputStream stream = KrtPdfSupport.class.getClassLoader().getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("Bundled font missing from classpath: " + resource);
      }
      return BaseFont.createFont(
          resource, BaseFont.WINANSI, BaseFont.EMBEDDED, true, stream.readAllBytes(), null);
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load bundled font: " + resource, e);
    }
  }

  /**
   * PdfPageEvent painting the KRT corporate-design background on every page: black fill, a single
   * thin orange top-accent bar (the brand marker — the bottom/left bars were dropped so orange
   * stays an accent, not a frame) and the KRT logo bottom-right — identical on pages 2+.
   */
  private static class KrtPageBackground extends PdfPageEventHelper {

    /** Height of the thin orange top-accent bar, in points. */
    private static final float TOP_ACCENT_HEIGHT = 3f;

    @Override
    public void onStartPage(PdfWriter writer, Document document) {
      PdfContentByte canvas = writer.getDirectContentUnder();

      canvas.setColorFill(COLOR_BLACK);
      canvas.rectangle(0, 0, PageSize.A4.getWidth(), PageSize.A4.getHeight());
      canvas.fill();

      canvas.setColorFill(COLOR_ORANGE);
      canvas.rectangle(
          0,
          PageSize.A4.getHeight() - TOP_ACCENT_HEIGHT,
          PageSize.A4.getWidth(),
          TOP_ACCENT_HEIGHT);
      canvas.fill();

      try (InputStream logoStream =
          getClass().getClassLoader().getResourceAsStream("META-INF/resources/logos/krt.png")) {
        if (logoStream != null) {
          Image logo = Image.getInstance(logoStream.readAllBytes());
          float logoHeight = 60f;
          float scale = logoHeight / logo.getHeight();
          float logoWidth = logo.getWidth() * scale;
          float margin = 20f;
          logo.scaleAbsolute(logoWidth, logoHeight);
          logo.setAbsolutePosition(PageSize.A4.getWidth() - logoWidth - margin, margin + 4f);
          canvas.addImage(logo);
        } else {
          log.warn("krt.png not found in classpath");
        }
      } catch (Exception e) {
        log.warn("Failed to render logo on page background", e);
      }
    }
  }
}
