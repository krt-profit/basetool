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

package de.greluc.krt.profit.basetool.backend.platform.internal.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfDocument;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfDocuments;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfTheme;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfDataTable;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfKeyValue;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfTableCell;
import de.greluc.krt.profit.basetool.backend.support.BackendMessages;
import de.greluc.krt.profit.basetool.backend.support.PdfConformance;
import de.greluc.krt.profit.basetool.backend.support.TaggedStructure;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpdf.text.Document;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.PdfWriter;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.verapdf.pdfa.flavours.PDFAFlavour;

/**
 * Pins the tagged layout layer (REQ-API-029): a document built from every block conforms to
 * PDF/A-4, PDF/UA-2 and both WTPDF levels, and its structure tree holds one {@code Document} in the
 * PDF 2.0 namespace with headings in visual order, tables whose header cells carry their scope, a
 * figure with alternate description and bounding box, and decoration only as artifact.
 */
class TaggedKrtPdfDocumentTest {

  private static final KrtPdfDocuments DOCUMENTS =
      new TaggedKrtPdfDocuments(BackendMessages.bundle());

  private static final int BOOKING_ROWS = 80;

  private static final int DETAIL_EVERY = 10;

  private static final int EMPTY_NOTE_EVERY = 7;

  private static byte[] pdf;

  private static TaggedStructure document;

  @BeforeAll
  static void render() throws IOException {
    pdf =
        render(krt -> {
          krt.keyValueTable(
              List.of(
                  new PdfKeyValue("ZEITRAUM", "01.01.2026 – 31.01.2026"),
                  new PdfKeyValue("KONTO", "K-0001 - Kartellkasse")));
          krt.heading("BUCHUNGEN");
          try (PdfDataTable table =
              krt.dataTable(new float[] {1f, 1f, 2f}, List.of("DATUM", "BETRAG", "NOTIZ"))) {
            for (int row = 0; row < BOOKING_ROWS; row++) {
              table.row(
                  List.of(
                      PdfTableCell.text("15.01.2026"),
                      PdfTableCell.centered("+" + row),
                      PdfTableCell.text(row % EMPTY_NOTE_EVERY == 0 ? "" : "Zeile " + row)));
              if (row % DETAIL_EVERY == 0) {
                table.detailRow(
                    List.of(
                        new PdfKeyValue("Begründung", "Spende " + row),
                        new PdfKeyValue("Notiz", ""),
                        new PdfKeyValue("Notiz Bank", "geprüft")));
              }
            }
          }
          krt.heading("HALTER");
          try (PdfDataTable empty =
              krt.dataTable(new float[] {3f, 1.5f}, List.of("HALTER", "BETRAG"))) {
            empty.emptyRow("Keine Bestände");
          }
          krt.note("Dieser Auszug ist eine Zusammenfassung.");
          krt.sectionGap();
          krt.heading("VERLAUF");
          krt.figure(
              515f,
              130f,
              "Kontostand von 0 auf 1.000 aUEC gestiegen",
              canvas -> {
                canvas.setColorStroke(KrtPdfTheme.COLOR_ORANGE);
                canvas.moveTo(0, 0);
                canvas.lineTo(515f, 130f);
                canvas.stroke();
              });
          krt.newPage();
          krt.notice("Keine weiteren Konten.");
          krt.footer();
        });
    document = TaggedStructure.read(pdf);
  }

  @Test
  void theDocumentConformsToPdfA4PdfUa2AndBothWtpdfLevels() throws Exception {
    PdfConformance.assertConformant(pdf);
  }

  @Test
  void theValidatorRejectsContentThatIsNeitherTaggedNorArtifact() throws IOException {
    byte[] untagged;
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Document plain = new Document();
      PdfWriter writer = PdfWriter.getInstance(plain, out);
      writer.setTagged();
      PdfConformanceProfile.beforeOpen(writer);
      plain.open();
      PdfConformanceProfile.afterOpen(writer, "UNGETAGGT", Instant.now());
      plain.add(new Paragraph("Ohne Tag", KrtPdfTheme.regular(10, KrtPdfTheme.COLOR_WHITE)));
      plain.close();
      untagged = out.toByteArray();
    }

    assertThatThrownBy(() -> PdfConformance.assertConformant(untagged))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("ua2 8.2.2-1")
        .hasMessageContaining("wt1a 8.2.2-1")
        .hasMessageContaining("wt1r 8.2.2-1");
  }

  @Test
  void theRootHoldsOneDocumentInThePdf20Namespace() throws IOException {
    assertThat(document.type()).isEqualTo("Document");
    assertThat(document.flatten())
        .allSatisfy(element ->
            assertThat(element.namespace())
                .as("namespace of %s", element.type())
                .isEqualTo(PdfStructureTree.PDF_2_0_NAMESPACE));
    assertThat(TaggedStructure.declaredNamespaces(pdf))
        .containsExactly(PdfStructureTree.PDF_2_0_NAMESPACE);
  }

  @Test
  void theBlocksFollowTheVisualOrder() {
    assertThat(document.kidTypes())
        .containsExactly(
            "H1", "Table", "H2", "Table", "H2", "Table", "P", "H2", "Figure", "P", "P");
  }

  @Test
  void aKeyValueTableHeadsEachRowWithItsLabel() {
    TaggedStructure meta = document.kids().get(1);

    assertThat(meta.kids())
        .hasSize(2)
        .allSatisfy(row -> {
          assertThat(row.kidTypes()).containsExactly("TH", "TD");
          assertThat(row.kids().getFirst().attributes()).containsEntry("Table.Scope", "/Row");
        });
  }

  @Test
  void aDataTableSpanningPagesIsOneTableWithOneHeaderRow() {
    TaggedStructure bookings = document.kids().get(3);
    int detailRows = (BOOKING_ROWS + DETAIL_EVERY - 1) / DETAIL_EVERY;

    assertThat(bookings.kids()).hasSize(1 + BOOKING_ROWS + detailRows);
    TaggedStructure header = bookings.kids().getFirst();
    assertThat(header.kidTypes()).containsExactly("TH", "TH", "TH");
    assertThat(header.kids())
        .allSatisfy(cell -> assertThat(cell.attributes()).containsEntry("Table.Scope", "/Column"));
    assertThat(bookings.kids().subList(1, bookings.kids().size()))
        .allSatisfy(row -> assertThat(row.kidTypes()).doesNotContain("TH"));
    assertThat(bookings.all("TD"))
        .filteredOn(cell -> cell.attributes().containsKey("Table.ColSpan"))
        .hasSize(detailRows)
        .allSatisfy(cell -> assertThat(cell.attributes()).containsEntry("Table.ColSpan", "3"));
  }

  @Test
  void anEmptyCellIsATableCellWithoutContent() {
    TaggedStructure bookings = document.kids().get(3);
    List<TaggedStructure> notes =
        bookings.kids().stream()
            .filter(row -> row.kids().size() == 3 && "TD".equals(row.kids().getFirst().type()))
            .map(row -> row.kids().get(2))
            .toList();

    assertThat(notes).hasSize(BOOKING_ROWS);
    assertThat(notes).filteredOn(TaggedStructure::marked).hasSize(BOOKING_ROWS - 12);
  }

  @Test
  void theEmptyStateRowSpansTheTable() {
    TaggedStructure holders = document.kids().get(5);

    assertThat(holders.kids()).hasSize(2);
    assertThat(holders.kids().get(1).kids().getFirst().attributes())
        .containsEntry("Table.ColSpan", "2");
  }

  @Test
  void theFigureCarriesItsDescriptionAndBoundingBox() {
    TaggedStructure figure = document.all("Figure").getFirst();

    assertThat(figure.alternateDescription())
        .isEqualTo("Kontostand von 0 auf 1.000 aUEC gestiegen");
    assertThat(figure.attributes())
        .containsKey("Layout.BBox")
        .containsEntry("Layout.Placement", "/Block");
    assertThat(figure.marked()).isTrue();
  }

  @Test
  void aRepeatedHeaderIsDrawnAsPaginationArtifact() throws IOException {
    PdfReader reader = new PdfReader(pdf);
    String secondPage;
    try {
      secondPage = new String(reader.getPageContent(2), StandardCharsets.ISO_8859_1);
    } finally {
      reader.close();
    }

    int header = secondPage.indexOf("(DATUM)");
    int artifact = secondPage.lastIndexOf("/Artifact <</Type/Pagination>> BDC", header);
    assertThat(header).isPositive();
    assertThat(artifact).isNotNegative();
    assertThat(secondPage.substring(artifact, header)).doesNotContain("EMC");
    assertThat(pages(pdf).get(1)).doesNotContain("DATUM");
  }

  @Test
  void theTextReadsInLayoutOrder() throws IOException {
    List<String> pages = pages(pdf);
    String text = String.join("\n", pages);

    assertThat(pages.size()).isGreaterThanOrEqualTo(3);
    assertThat(text)
        .containsSubsequence(
            "KONTOAUSZUG",
            "ZEITRAUM",
            "BUCHUNGEN",
            "Zeile 1",
            "Begründung: Spende 70",
            "Zeile 79",
            "HALTER",
            "Keine Bestände",
            "Zusammenfassung",
            "Keine weiteren Konten.",
            "Generiert von Profit Basetool am");
    assertThat(text).doesNotContain("Notiz: ");
  }

  @Test
  void aRowTallerThanAPageContinuesInTheSameCells() throws Exception {
    String longText =
        IntStream.range(0, 1500).mapToObj(word -> "Wort" + word).collect(Collectors.joining(" "));
    byte[] tall =
        render(krt -> {
          try (PdfDataTable table = krt.dataTable(new float[] {1f, 1f}, List.of("FELD", "WERT"))) {
            table.row(List.of(PdfTableCell.text("Lang"), PdfTableCell.text(longText)));
            table.row(List.of(PdfTableCell.text("Kurz"), PdfTableCell.text("Ende")));
          }
        });

    PdfConformance.assertConformant(tall);
    assertThat(pages(tall).size()).isGreaterThanOrEqualTo(2);
    TaggedStructure table = TaggedStructure.read(tall).all("Table").getFirst();
    assertThat(table.kids()).hasSize(3);
    assertThat(String.join("\n", pages(tall))).contains("Wort1499", "Ende");
  }

  @Test
  void aParagraphLongerThanAPageStaysOneParagraph() throws Exception {
    String longText =
        IntStream.range(0, 2500).mapToObj(word -> "Satz" + word).collect(Collectors.joining(" "));
    byte[] tall = render(krt -> krt.note(longText));

    PdfConformance.assertConformant(tall);
    assertThat(pages(tall).size()).isGreaterThanOrEqualTo(2);
    assertThat(TaggedStructure.read(tall).kidTypes()).containsExactly("H1", "P");
  }

  @Test
  void noBlockStartsWhileATableIsOpen() throws IOException {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream();
        KrtPdfDocument krt = DOCUMENTS.open(out, "OFFEN")) {
      PdfDataTable table = krt.dataTable(new float[] {1f}, List.of("SPALTE"));

      assertThatThrownBy(() -> krt.heading("ZU FRÜH")).isInstanceOf(IllegalStateException.class);
      table.close();
      assertThatThrownBy(() -> table.row(List.of(PdfTableCell.text("zu spät"))))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void aRowMustFillEveryColumn() throws IOException {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream();
        KrtPdfDocument krt = DOCUMENTS.open(out, "SPALTEN");
        PdfDataTable table = krt.dataTable(new float[] {1f, 1f}, List.of("A", "B"))) {
      assertThatThrownBy(() -> table.row(List.of(PdfTableCell.text("nur eine"))))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void theValidatorChecksEveryProfile() {
    assertThat(PdfConformance.PROFILES)
        .containsExactly(
            PDFAFlavour.PDFA_4,
            PDFAFlavour.PDFUA_2,
            PDFAFlavour.WTPDF_1_0_ACCESSIBILITY,
            PDFAFlavour.WTPDF_1_0_REUSE);
  }

  private static byte[] render(Consumer<KrtPdfDocument> content) throws IOException {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      try (KrtPdfDocument krt = DOCUMENTS.open(out, "KONTOAUSZUG ÄÖÜ – €")) {
        content.accept(krt);
      }
      return out.toByteArray();
    }
  }

  private static List<String> pages(byte[] bytes) throws IOException {
    PdfReader reader = new PdfReader(bytes);
    try {
      PdfTextExtractor extractor = new PdfTextExtractor(reader);
      return IntStream.rangeClosed(1, reader.getNumberOfPages())
          .mapToObj(page -> {
            try {
              return extractor.getTextFromPage(page);
            } catch (IOException failure) {
              throw new IllegalStateException(failure);
            }
          })
          .toList();
    } finally {
      reader.close();
    }
  }
}
