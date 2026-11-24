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

package de.greluc.krt.profit.basetool.backend.privacy.api;

import de.greluc.krt.profit.basetool.backend.kernel.ReportGenerationException;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfDocument;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfDocuments;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfDataTable;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfKeyValue;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfTableCell;
import de.greluc.krt.profit.basetool.backend.privacy.api.DataExportService.DataExport;
import de.greluc.krt.profit.basetool.backend.privacy.api.DataExportService.ExportSection;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Renders a data export as the human-readable summary of the Art. 15 answer (REQ-SEC-058); the JSON
 * export is the full disclosure.
 *
 * <p>The PDF lists the master data in full, each section's row count and legal basis, the surfaces
 * out of scope, and a note when third-party names were removed. Labels, including field names, come
 * from the caller's label function.
 */
public final class DataExportPdfFormat {

  /**
   * What an absent value reads as. An en dash rather than an empty cell, so the row still shows
   * that the column exists and is simply not filled in -- which is itself part of the answer to an
   * access request.
   */
  private static final String EMPTY_VALUE = "–";

  /** Relative widths of the section, row-count and legal-basis columns of the inventory. */
  private static final float[] INVENTORY_WIDTHS = {4f, 1.2f, 2f};

  /** Relative widths of the field and value columns of a verbatim section. */
  private static final float[] SECTION_WIDTHS = {1.4f, 3f};

  /** Not instantiable. */
  private DataExportPdfFormat() {}

  /**
   * Sections printed in full; package-private for {@code DataExportPdfFieldLabelCoverageTest},
   * which checks their field names against the {@code pdf.export.field.*} keys.
   */
  static final List<String> VERBATIM_SECTIONS =
      List.of("account", "termsAcceptances", "orgUnitMemberships", "registrationDecisions");

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'UTC'").withZone(ZoneOffset.UTC);

  /**
   * Renders the export.
   *
   * @param documents opens the tagged PDF
   * @param export the assembled export
   * @param label resolves a message key to its German label
   * @return the PDF bytes
   */
  public static byte @NotNull [] render(
      @NotNull KrtPdfDocuments documents,
      @NotNull DataExport export,
      @NotNull UnaryOperator<String> label) {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      try (KrtPdfDocument pdf = documents.open(out, label.apply("pdf.export.title"))) {
        pdf.keyValueTable(
            List.of(
                new PdfKeyValue(label.apply("pdf.export.meta.subject"), export.subjectHandle()),
                new PdfKeyValue(
                    label.apply("pdf.export.meta.generatedAt"),
                    TIMESTAMP.format(export.generatedAt())),
                new PdfKeyValue(
                    label.apply("pdf.export.meta.totalRows"), String.valueOf(export.totalRows()))));

        pdf.note(label.apply("pdf.export.note.summary"));
        if (export.thirdPartyHandlesRemoved()) {
          pdf.note(label.apply("pdf.export.note.thirdParty"));
        }

        pdf.heading(label.apply("pdf.export.inventory"));
        inventory(pdf, export, label);

        for (ExportSection section : export.sections()) {
          if (VERBATIM_SECTIONS.contains(section.key()) && !section.rows().isEmpty()) {
            verbatim(pdf, section, label);
          }
        }

        pdf.heading(label.apply("pdf.export.outOfScope"));
        for (String key : export.outOfScope()) {
          pdf.note(label.apply("pdf.export.outOfScope." + key));
        }
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new ReportGenerationException("Could not render the data-export PDF", e);
    }
  }

  /**
   * Adds the inventory table: one row per section with its row count and legal basis.
   *
   * @param pdf the open document
   * @param export the assembled export
   * @param label resolves a message key to its German label
   */
  private static void inventory(
      @NotNull KrtPdfDocument pdf,
      @NotNull DataExport export,
      @NotNull UnaryOperator<String> label) {
    try (PdfDataTable inventory =
        pdf.dataTable(
            INVENTORY_WIDTHS,
            List.of(
                label.apply("pdf.export.col.section"),
                label.apply("pdf.export.col.rows"),
                label.apply("pdf.export.col.basis")))) {
      for (ExportSection section : export.sections()) {
        inventory.row(
            List.of(
                PdfTableCell.text(label.apply("pdf.export.section." + section.key())),
                PdfTableCell.centered(section.rows().size() + (section.truncated() ? "+" : "")),
                PdfTableCell.text(
                    label.apply(
                        LegalBasis.ART_15_20.name().equals(section.legalBasis())
                            ? "pdf.export.basis.art1520"
                            : "pdf.export.basis.art15"))));
      }
    }
  }

  /**
   * Adds a section printed in full under its heading: one field/value row per projected column.
   *
   * @param pdf the open document
   * @param section the section, with at least one row
   * @param label resolves a message key to its German label
   */
  private static void verbatim(
      @NotNull KrtPdfDocument pdf,
      @NotNull ExportSection section,
      @NotNull UnaryOperator<String> label) {
    pdf.heading(label.apply("pdf.export.section." + section.key()));
    try (PdfDataTable table =
        pdf.dataTable(
            SECTION_WIDTHS,
            List.of(label.apply("pdf.export.col.field"), label.apply("pdf.export.col.value")))) {
      for (Map<String, Object> row : section.rows()) {
        for (Map.Entry<String, Object> cell : row.entrySet()) {
          table.row(
              List.of(
                  PdfTableCell.text(label.apply("pdf.export.field." + cell.getKey())),
                  PdfTableCell.text(renderValue(cell.getValue()))));
        }
      }
    }
  }

  /**
   * Formats one cell value, rendering {@code null} as a dash.
   *
   * @param value the projected value, possibly {@code null}
   * @return the text for the cell
   */
  private static @NotNull String renderValue(@Nullable Object value) {
    return value == null ? EMPTY_VALUE : String.valueOf(value);
  }
}
