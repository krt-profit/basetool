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

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.openpdf.text.Document;
import org.openpdf.text.pdf.PdfArray;
import org.openpdf.text.pdf.PdfDictionary;
import org.openpdf.text.pdf.PdfICCBased;
import org.openpdf.text.pdf.PdfName;
import org.openpdf.text.pdf.PdfString;
import org.openpdf.text.pdf.PdfWriter;
import org.openpdf.text.xml.xmp.DublinCoreSchema;
import org.openpdf.text.xml.xmp.PdfSchema;
import org.openpdf.text.xml.xmp.XmpArray;
import org.openpdf.text.xml.xmp.XmpBasicSchema;
import org.openpdf.text.xml.xmp.XmpSchema;
import org.openpdf.text.xml.xmp.XmpWriter;

/**
 * Writes the file-level part of every backend PDF: PDF/A-4 (ISO 19005-4:2020) on PDF 2.0 (ISO
 * 32000-2:2020) with XMP identification and metadata, an sRGB output intent, the document language
 * and a title the viewer shows (REQ-API-026, ADR-0409), and the declarations of PDF/UA-2 (ISO
 * 14289-2:2024) and both Well-Tagged PDF 1.0 conformance levels (REQ-API-029, ADR-0410).
 *
 * <p>OpenPDF always writes a trailer {@code Info} dictionary, which PDF/A-4 6.1.3 admits only
 * beside a catalog {@code PieceInfo} and with nothing but {@code ModDate}; the profile therefore
 * records the generation instant as this application's page-piece data (ISO 32000-2 14.5) and
 * reduces {@code Info} to that date.
 */
public final class PdfConformanceProfile {

  /** BCP 47 tag of the language every backend PDF is written in. */
  public static final String LANGUAGE = "de";

  /** The generator named in {@code xmp:CreatorTool} and as claimant of the PDF Declarations. */
  public static final String CREATOR_TOOL = "Profit Basetool";

  /** PDF Declaration of the WTPDF 1.0 conformance level for accessibility (WTPDF 6.1.3). */
  public static final String WTPDF_ACCESSIBILITY =
      "http://pdfa.org/declarations/wtpdf#accessibility1.0";

  /** PDF Declaration of the WTPDF 1.0 conformance level for reuse (WTPDF 6.1.2). */
  public static final String WTPDF_REUSE = "http://pdfa.org/declarations/wtpdf#reuse1.0";

  /** Namespace of the PDF/A identification schema (ISO 19005-4 6.7.3). */
  private static final String PDFAID_XMLNS = "xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\"";

  /** Namespace of the PDF/UA identification schema (ISO 14289-2 Table 1). */
  private static final String PDFUAID_XMLNS = "xmlns:pdfuaid=\"http://www.aiim.org/pdfua/ns/id/\"";

  /** Namespace of the PDF Declarations schema, as WTPDF 6.1 and veraPDF spell it. */
  private static final String PDFD_XMLNS = "xmlns:pdfd=\"http://pdfa.org/declarations/\"";

  /** Page-piece key under which this application keeps its private data. */
  private static final PdfName APPLICATION = new PdfName("ProfitBasetool");

  private static final PdfName PIECE_INFO = new PdfName("PieceInfo");

  private static final PdfName LAST_MODIFIED = new PdfName("LastModified");

  /** ICC registry name and output condition of the sRGB characterisation. */
  private static final String SRGB_CONDITION = "sRGB IEC61966-2.1";

  private static final String ICC_REGISTRY = "http://www.color.org";

  /** The JDK's sRGB display profile, the output intent of the DeviceRGB colours every page uses. */
  private static final ICC_Profile SRGB = ICC_Profile.getInstance(ColorSpace.CS_sRGB);

  /** PDF 2.0 date (ISO 32000-2 7.9.4) in UT: {@code D:YYYYMMDDHHmmSSZ}, no trailing apostrophe. */
  private static final DateTimeFormatter PDF_DATE =
      DateTimeFormatter.ofPattern("'D:'yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);

  /** The {@code pdfd:claimDate} form of the PDF Declarations specification. */
  private static final DateTimeFormatter CLAIM_DATE =
      DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

  private PdfConformanceProfile() {}

  /**
   * Sets what must be fixed before the document opens: the PDF 2.0 header and the viewer showing
   * the document title instead of the file name.
   *
   * @param writer the writer of the unopened document
   */
  public static void beforeOpen(@NotNull PdfWriter writer) {
    writer.setPdfVersion(PdfWriter.VERSION_2_0);
    writer.setViewerPreferences(PdfWriter.DisplayDocTitle);
  }

  /**
   * Adds the catalog entries, the output intent and the XMP packet, and reduces the {@code Info}
   * dictionary to its modification date.
   *
   * @param writer the writer of the open document, tagged since before it opened
   * @param title the document title, as the reader sees it
   * @param generatedAt the generation instant, used for every date the file carries
   * @throws IllegalArgumentException when the writer is not tagged, since PDF/UA-2 requires it
   */
  public static void afterOpen(
      @NotNull PdfWriter writer, @NotNull String title, @NotNull Instant generatedAt) {
    if (!writer.isTagged()) {
      throw new IllegalArgumentException("A PDF/UA-2 file needs a tagged writer");
    }
    Instant stamp = generatedAt.truncatedTo(ChronoUnit.SECONDS);
    PdfString pdfDate = new PdfString(PDF_DATE.format(stamp));
    try {
      PdfDictionary catalog = writer.getExtraCatalog();
      catalog.put(PdfName.LANG, new PdfString(LANGUAGE));
      catalog.put(PdfName.OUTPUTINTENTS, new PdfArray(outputIntent(writer)));
      PdfDictionary piece = new PdfDictionary();
      piece.put(LAST_MODIFIED, pdfDate);
      PdfDictionary pieceInfo = new PdfDictionary();
      pieceInfo.put(APPLICATION, piece);
      catalog.put(PIECE_INFO, pieceInfo);
      writer.setXmpMetadata(xmp(title, stamp));
    } catch (IOException failure) {
      throw new UncheckedIOException("PDF conformance profile could not be written", failure);
    }
    PdfDictionary info = writer.getInfo();
    List.copyOf(info.getKeys()).forEach(info::remove);
    info.put(PdfName.MODDATE, pdfDate);
  }

  private static @NotNull PdfDictionary outputIntent(@NotNull PdfWriter writer) throws IOException {
    PdfDictionary intent = new PdfDictionary(PdfName.OUTPUTINTENT);
    intent.put(PdfName.S, PdfName.GTS_PDFA1);
    intent.put(PdfName.OUTPUTCONDITIONIDENTIFIER, new PdfString(SRGB_CONDITION));
    intent.put(PdfName.OUTPUTCONDITION, new PdfString(SRGB_CONDITION));
    intent.put(PdfName.REGISTRYNAME, new PdfString(ICC_REGISTRY));
    intent.put(
        PdfName.DESTOUTPUTPROFILE,
        writer
            .addToBody(new PdfICCBased(SRGB, writer.getCompressionLevel()))
            .getIndirectReference());
    return intent;
  }

  private static byte @NotNull [] xmp(@NotNull String title, @NotNull Instant stamp)
      throws IOException {
    DublinCoreSchema dublinCore = new DublinCoreSchema();
    dublinCore.addTitle(title);
    XmpArray language = new XmpArray(XmpArray.UNORDERED);
    language.add(LANGUAGE);
    dublinCore.setProperty(DublinCoreSchema.LANGUAGE, language);

    XmpBasicSchema basic = new XmpBasicSchema();
    basic.addCreatorTool(CREATOR_TOOL);
    String date = stamp.toString();
    basic.addCreateDate(date);
    basic.addModDate(date);
    basic.addMetaDataDate(date);

    PdfSchema pdf = new PdfSchema();
    pdf.addProducer(Document.getVersion());

    XmpSchema archive = new Identification(PDFAID_XMLNS);
    archive.setProperty("pdfaid:part", "4");
    archive.setProperty("pdfaid:rev", "2020");

    XmpSchema accessibility = new Identification(PDFUAID_XMLNS);
    accessibility.setProperty("pdfuaid:part", "2");
    accessibility.setProperty("pdfuaid:rev", "2024");

    ByteArrayOutputStream packet = new ByteArrayOutputStream();
    try (XmpWriter writer = new XmpWriter(packet)) {
      writer.addRdfDescription(dublinCore);
      writer.addRdfDescription(basic);
      writer.addRdfDescription(pdf);
      writer.addRdfDescription(archive);
      writer.addRdfDescription(accessibility);
      writer.addRdfDescription(
          new Declarations(List.of(WTPDF_ACCESSIBILITY, WTPDF_REUSE), CLAIM_DATE.format(stamp)));
    }
    return packet.toByteArray();
  }

  /** An identification schema of an ISO profile: {@code part} and {@code rev} only. */
  private static final class Identification extends XmpSchema {

    private static final long serialVersionUID = 1L;

    private Identification(@NotNull String xmlns) {
      super(xmlns);
    }
  }

  /**
   * The PDF Declarations of the document: one {@code pdfd:declarations} bag whose resources name
   * the declared profile and who claims it when.
   */
  private static final class Declarations extends XmpSchema {

    private static final long serialVersionUID = 1L;

    private Declarations(@NotNull List<String> conformsTo, @NotNull String claimDate) {
      super(PDFD_XMLNS);
      String claim =
          "<pdfd:claimData><rdf:Bag><rdf:li rdf:parseType=\"Resource\"><pdfd:claimBy>"
              + escape(CREATOR_TOOL)
              + "</pdfd:claimBy><pdfd:claimDate>"
              + escape(claimDate)
              + "</pdfd:claimDate></rdf:li></rdf:Bag></pdfd:claimData>";
      String bag =
          conformsTo.stream()
              .map(uri ->
                  "<rdf:li rdf:parseType=\"Resource\"><pdfd:conformsTo>"
                      + escape(uri)
                      + "</pdfd:conformsTo>"
                      + claim
                      + "</rdf:li>")
              .collect(Collectors.joining("", "<rdf:Bag>", "</rdf:Bag>"));
      // A structured value: stored as markup, bypassing the text escaping of setProperty.
      put("pdfd:declarations", bag);
    }
  }
}
