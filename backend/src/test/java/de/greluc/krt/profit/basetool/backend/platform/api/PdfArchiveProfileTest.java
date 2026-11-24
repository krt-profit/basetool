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

package de.greluc.krt.profit.basetool.backend.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.support.PdfA4Conformance;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpdf.text.Document;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfArray;
import org.openpdf.text.pdf.PdfDictionary;
import org.openpdf.text.pdf.PdfName;
import org.openpdf.text.pdf.PdfObject;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.PdfWriter;

/**
 * Pins the file format of every backend PDF (REQ-API-026): PDF/A-4 as veraPDF validates it, plus
 * the ISO 32000-2 points that matter to a reader — the PDF 2.0 header, an {@code Info} dictionary
 * of nothing but a PDF 2.0 {@code ModDate}, XMP with title, language and the PDF/A-4
 * identification, the catalog language, an sRGB output intent, the title shown by the viewer and
 * only embedded fonts.
 */
class PdfArchiveProfileTest {

  private static byte[] pdf;

  @BeforeAll
  static void render() throws IOException {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      KrtPdfSupport.KrtDocument krt = KrtPdfSupport.open(out, "KONTOAUSZUG ÄÖÜ – €");
      PdfPTable meta = KrtPdfSupport.newMetaTable();
      KrtPdfSupport.addMetaRow(meta, "ZEITRAUM", "01.01.2026 – 31.01.2026");
      krt.document().add(meta);
      KrtPdfSupport.addSectionHeader(krt, "BUCHUNGEN");
      PdfPTable table = new PdfPTable(2);
      KrtPdfSupport.addTableHeader(table, "DATUM");
      KrtPdfSupport.addTableHeader(table, "BETRAG");
      KrtPdfSupport.addTableCell(table, "15.01.2026", KrtPdfSupport.rowBackground(false), false);
      KrtPdfSupport.addTableCell(table, "+1.000", KrtPdfSupport.rowBackground(false), true);
      KrtPdfSupport.addDetailSubRow(
          table,
          "Begründung",
          "Spende",
          "Notiz",
          "",
          "Notiz Bank",
          "",
          KrtPdfSupport.COLOR_CANVAS,
          2);
      KrtPdfSupport.addEmptyRow(table, "Keine Buchungen", 2);
      krt.document().add(table);
      krt.document().add(KrtPdfSupport.spacer(12));
      KrtPdfSupport.addNote(krt, "Hinweis");
      KrtPdfSupport.addFooter(krt);
      krt.document().close();
      pdf = out.toByteArray();
    }
  }

  @Test
  void aKrtDocumentIsPdfA4() throws Exception {
    PdfA4Conformance.assertConformant(pdf);
  }

  @Test
  void theValidatorRejectsAPlainOpenPdfFile() throws IOException {
    byte[] plain;
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Document document = new Document();
      PdfWriter.getInstance(document, out);
      document.open();
      document.add(new Paragraph("Helvetica, not embedded"));
      document.close();
      plain = out.toByteArray();
    }

    assertThatThrownBy(() -> PdfA4Conformance.assertConformant(plain))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("6.1.3-4")
        .hasMessageContaining("6.1.3-5")
        .hasMessageContaining("6.7.2.1-1")
        .hasMessageContaining("6.2.10.4.1-1")
        .hasMessageContaining("6.2.4.3-4");
  }

  @Test
  void theHeaderIsPdf20() {
    assertThat(new String(pdf, 0, 8, StandardCharsets.US_ASCII)).isEqualTo("%PDF-2.0");
  }

  @Test
  void theInfoDictionaryHoldsOnlyAPdf20ModificationDate() throws IOException {
    PdfReader reader = new PdfReader(pdf);
    try {
      PdfDictionary info = reader.getTrailer().getAsDict(PdfName.INFO);
      assertThat(info.getKeys()).containsExactly(PdfName.MODDATE);
      String modDate = info.getAsString(PdfName.MODDATE).toString();
      assertThat(modDate).matches("D:\\d{14}Z");
      PdfDictionary piece =
          reader
              .getCatalog()
              .getAsDict(new PdfName("PieceInfo"))
              .getAsDict(new PdfName("ProfitBasetool"));
      assertThat(piece.getAsString(new PdfName("LastModified")).toString()).isEqualTo(modDate);
    } finally {
      reader.close();
    }
  }

  @Test
  void theXmpNamesTitleLanguageGeneratorAndPdfA4() throws IOException {
    PdfReader reader = new PdfReader(pdf);
    try {
      String xmp = new String(reader.getMetadata(), StandardCharsets.UTF_8);
      assertThat(xmp)
          .containsPattern(
              "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\"\\s*>KONTOAUSZUG ÄÖÜ – €</rdf:li>")
          .contains("<dc:format>application/pdf</dc:format>")
          .contains("<rdf:li>" + PdfArchiveProfile.LANGUAGE + "</rdf:li>")
          .contains("<xmp:CreatorTool>" + PdfArchiveProfile.CREATOR_TOOL + "</xmp:CreatorTool>")
          .contains("<pdfaid:part>4</pdfaid:part>")
          .contains("<pdfaid:rev>2020</pdfaid:rev>")
          .doesNotContain("pdfaid:conformance");
    } finally {
      reader.close();
    }
  }

  @Test
  void theCatalogCarriesLanguageOutputIntentAndTheShownTitle() throws IOException {
    PdfReader reader = new PdfReader(pdf);
    try {
      PdfDictionary catalog = reader.getCatalog();
      assertThat(catalog.getAsString(PdfName.LANG).toString())
          .isEqualTo(PdfArchiveProfile.LANGUAGE);
      PdfDictionary intent = catalog.getAsArray(PdfName.OUTPUTINTENTS).getAsDict(0);
      assertThat(intent.getAsName(PdfName.S)).isEqualTo(PdfName.GTS_PDFA1);
      assertThat(intent.get(PdfName.DESTOUTPUTPROFILE)).isNotNull();
      assertThat(catalog.getAsDict(PdfName.VIEWERPREFERENCES).get(PdfName.DISPLAYDOCTITLE))
          .hasToString("true");
    } finally {
      reader.close();
    }
  }

  @Test
  void everyFontIsEmbedded() throws IOException {
    PdfReader reader = new PdfReader(pdf);
    try {
      List<PdfDictionary> fonts = new ArrayList<>();
      for (int page = 1; page <= reader.getNumberOfPages(); page++) {
        PdfDictionary pageFonts =
            reader.getPageN(page).getAsDict(PdfName.RESOURCES).getAsDict(PdfName.FONT);
        for (PdfName key : pageFonts.getKeys()) {
          fonts.add(pageFonts.getAsDict(key));
        }
      }
      assertThat(fonts).isNotEmpty();
      assertThat(fonts)
          .allSatisfy(font -> {
            PdfDictionary descriptor = font.getAsDict(PdfName.FONTDESCRIPTOR);
            assertThat(descriptor).as("font %s", font.get(PdfName.BASEFONT)).isNotNull();
            assertThat(descriptor.get(PdfName.FONTFILE2))
                .as("embedded program of %s", font.get(PdfName.BASEFONT))
                .isNotNull();
          });
    } finally {
      reader.close();
    }
  }

  @Test
  void theOutputIntentEmbedsOneIccProfile() throws IOException {
    PdfReader reader = new PdfReader(pdf);
    try {
      PdfArray intents = reader.getCatalog().getAsArray(PdfName.OUTPUTINTENTS);
      assertThat(intents.size()).isEqualTo(1);
      PdfObject profile =
          PdfReader.getPdfObject(intents.getAsDict(0).get(PdfName.DESTOUTPUTPROFILE));
      assertThat(profile.isStream()).isTrue();
    } finally {
      reader.close();
    }
  }
}
