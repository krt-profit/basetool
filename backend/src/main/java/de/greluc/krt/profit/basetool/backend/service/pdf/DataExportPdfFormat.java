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

import de.greluc.krt.profit.basetool.backend.exception.ReportGenerationException;
import de.greluc.krt.profit.basetool.backend.service.DataExportService.DataExport;
import de.greluc.krt.profit.basetool.backend.service.DataExportService.ExportSection;
import de.greluc.krt.profit.basetool.backend.support.DataExportSections;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.openpdf.text.pdf.PdfPTable;

/**
 * Renders a data export as the human-readable half of the Art. 15 answer (REQ-SEC-058).
 *
 * <p><b>This is a summary by design, and the JSON is the full disclosure.</b> Decided by
 * {@literal @}greluc on 2026-09-15. An active member's export runs to thousands of warehouse
 * movements and audit rows; rendering every one as a PDF table produces a document nobody reads,
 * which serves the right of access worse than a short document that says exactly what exists and
 * points at the machine-readable file for the detail.
 *
 * <p>So the PDF carries:
 *
 * <ul>
 *   <li>the master data in full — it is short and it is what a person actually wants to see
 *   <li>one row per section with its <b>row count</b> and its <b>legal basis</b>, so the document
 *       is a complete inventory of what is held even where it does not print the rows
 *   <li>the surfaces deliberately out of scope, named
 *   <li>a note when third-party names were removed from free text
 * </ul>
 *
 * <p>The document is German, like every other PDF in this application; its labels are resolved
 * through the caller's label function rather than written inline.
 */
public final class DataExportPdfFormat {

  /**
   * What an absent value reads as. An en dash rather than an empty cell, so the row still shows
   * that the column exists and is simply not filled in -- which is itself part of the answer to an
   * access request.
   */
  private static final String EMPTY_VALUE = "–";

  /** Not instantiable. */
  private DataExportPdfFormat() {}

  /**
   * Sections printed in full because they are short and are the point of the document.
   *
   * <p>Package-private rather than private so {@code DataExportPdfFieldLabelCoverageTest} can hold
   * their projections against the {@code pdf.export.field.*} bundle keys. Every field name in these
   * tables is rendered through the bundle, and a missing key falls back to the key itself — which
   * is how the raw column aliases reached the member in the first place.
   */
  static final List<String> VERBATIM_SECTIONS =
      List.of("account", "termsAcceptances", "orgUnitMemberships", "registrationDecisions");

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'UTC'").withZone(ZoneOffset.UTC);

  /**
   * Renders the export.
   *
   * @param export the assembled export
   * @param label resolves a message key to its German label
   * @return the PDF bytes
   */
  public static byte @NotNull [] render(
      @NotNull DataExport export, @NotNull UnaryOperator<String> label) {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      KrtPdfSupport.KrtDocument krt = KrtPdfSupport.open(out);

      KrtPdfSupport.addTitle(krt, label.apply("pdf.export.title"));

      PdfPTable meta = KrtPdfSupport.newMetaTable();
      KrtPdfSupport.addMetaRow(
          meta, label.apply("pdf.export.meta.subject"), export.subjectHandle());
      KrtPdfSupport.addMetaRow(
          meta, label.apply("pdf.export.meta.generatedAt"), TIMESTAMP.format(export.generatedAt()));
      KrtPdfSupport.addMetaRow(
          meta, label.apply("pdf.export.meta.totalRows"), String.valueOf(export.totalRows()));
      krt.document().add(meta);

      // Why this document is a summary, said in the document. A reader must not have to guess
      // whether the short list means "that is all there is".
      KrtPdfSupport.addNote(krt, label.apply("pdf.export.note.summary"));
      if (export.thirdPartyHandlesRemoved()) {
        KrtPdfSupport.addNote(krt, label.apply("pdf.export.note.thirdParty"));
      }

      // --- the inventory: one line per section, with its count and legal basis ------------
      KrtPdfSupport.addSectionHeader(krt, label.apply("pdf.export.inventory"));
      PdfPTable inventory = new PdfPTable(3);
      inventory.setWidthPercentage(100);
      inventory.setWidths(new float[] {4f, 1.2f, 2f});
      KrtPdfSupport.addTableHeader(inventory, label.apply("pdf.export.col.section"));
      KrtPdfSupport.addTableHeader(inventory, label.apply("pdf.export.col.rows"));
      KrtPdfSupport.addTableHeader(inventory, label.apply("pdf.export.col.basis"));
      boolean alt = false;
      for (ExportSection section : export.sections()) {
        Color bg = KrtPdfSupport.rowBackground(alt);
        alt = !alt;
        KrtPdfSupport.addTableCell(
            inventory, label.apply("pdf.export.section." + section.key()), bg, false);
        KrtPdfSupport.addTableCell(
            inventory, section.rows().size() + (section.truncated() ? "+" : ""), bg, true);
        KrtPdfSupport.addTableCell(
            inventory,
            label.apply(
                DataExportSections.ART_15_20.equals(section.legalBasis())
                    ? "pdf.export.basis.art1520"
                    : "pdf.export.basis.art15"),
            bg,
            false);
      }
      krt.document().add(inventory);

      // --- the short sections, in full -----------------------------------------------------
      for (ExportSection section : export.sections()) {
        if (!VERBATIM_SECTIONS.contains(section.key()) || section.rows().isEmpty()) {
          continue;
        }
        KrtPdfSupport.addSectionHeader(krt, label.apply("pdf.export.section." + section.key()));
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[] {1.4f, 3f});
        KrtPdfSupport.addTableHeader(table, label.apply("pdf.export.col.field"));
        KrtPdfSupport.addTableHeader(table, label.apply("pdf.export.col.value"));
        // The field name is a bundle label, not the projection's alias. It used to be the alias, so
        // a member exercising their right of access read `discord_guild_nickname` and
        // `share_blueprints_globally` -- untranslated user-visible text in a document that answers
        // a legal request, and unintelligible to the person it is addressed to.
        // DataExportPdfFieldLabelCoverageTest fails when a projection yields a column with no key.
        boolean rowAlt = false;
        for (Map<String, Object> row : section.rows()) {
          for (Map.Entry<String, Object> cell : row.entrySet()) {
            Color bg = KrtPdfSupport.rowBackground(rowAlt);
            rowAlt = !rowAlt;
            KrtPdfSupport.addTableCell(
                table, label.apply("pdf.export.field." + cell.getKey()), bg, false);
            KrtPdfSupport.addTableCell(table, renderValue(cell.getValue()), bg, false);
          }
        }
        krt.document().add(table);
      }

      KrtPdfSupport.addSectionHeader(krt, label.apply("pdf.export.outOfScope"));
      for (String key : export.outOfScope()) {
        KrtPdfSupport.addNote(krt, label.apply("pdf.export.outOfScope." + key));
      }

      krt.document().close();
      return out.toByteArray();
    } catch (IOException e) {
      throw new ReportGenerationException("Could not render the data-export PDF", e);
    }
  }

  /**
   * One cell's value as it should read in the document.
   *
   * <p>An empty column renders as a dash and not as the four letters {@code null}. The projections
   * select every column unconditionally, so a member who never filled in their description, Discord
   * id or display name used to receive a document with rows reading {@code description | null} --
   * which invites the reading that the system stores the string rather than nothing. {@code
   * String.valueOf(Object)} is what produced it: the value is statically {@code Object}, so the
   * overload that maps {@code null} to {@code "null"} was the one bound, before {@code
   * addTableCell}'s non-null guard could help.
   *
   * @param value the projected value, possibly {@code null}
   * @return the text for the cell
   */
  private static @NotNull String renderValue(@Nullable Object value) {
    return value == null ? EMPTY_VALUE : String.valueOf(value);
  }
}
