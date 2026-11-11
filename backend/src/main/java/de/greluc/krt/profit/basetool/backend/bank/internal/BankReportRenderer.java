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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.kernel.ReportGenerationException;
import de.greluc.krt.profit.basetool.backend.platform.api.KrtPdfSupport;
import java.awt.Color;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.openpdf.text.Element;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfPTable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Renders detached Bank snapshots with chunk-flushed tables and a bounded chart envelope. */
@Component
@RequiredArgsConstructor
public class BankReportRenderer {
  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
  private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("dd.MM.yy");
  private final ObjectMapper mapper;
  private final MessageSource messageSource;

  /**
   * Renders a statement without holding a database transaction or the complete posting set.
   *
   * @param spool the private snapshot and PDF storage
   * @param section account labels, totals and snapshot file
   * @param from inclusive period start
   * @param to inclusive period end
   * @param userZone the caller's zone, or UTC when absent
   * @param redactHolders whether holder and staff-only cells are omitted
   */
  public void statement(
      BankReportSpool spool,
      BankReportSpool.Section section,
      Instant from,
      Instant to,
      @Nullable ZoneId userZone,
      boolean redactHolders) {
    DateTimeFormatter stamp = DATE_TIME.withZone(userZone == null ? ZoneOffset.UTC : userZone);
    try (var output = Files.newOutputStream(spool.file("report.pdf"))) {
      KrtPdfSupport.KrtDocument krt = KrtPdfSupport.open(output);
      try (var _ = krt.document()) {
        KrtPdfSupport.addTitle(krt, label("pdf.bank.statement.title"));
        PdfPTable meta = KrtPdfSupport.newMetaTable();
        KrtPdfSupport.addMetaRow(
            meta,
            label("pdf.bank.statement.account"),
            section.account().accountNo() + " - " + section.account().name());
        period(meta, stamp, from, to);
        KrtPdfSupport.addMetaRow(
            meta,
            label("pdf.bank.statement.openingBalance"),
            BankPdfFormat.amount(section.opening()));
        KrtPdfSupport.addMetaRow(
            meta,
            label("pdf.bank.statement.closingBalance"),
            BankPdfFormat.amount(section.closing()));
        add(krt, meta);
        KrtPdfSupport.addSectionHeader(krt, label("pdf.bank.statement.bookings"));
        bookings(krt, spool, section, stamp, redactHolders, true);
        KrtPdfSupport.addFooter(krt);
      }
    } catch (Exception failure) {
      throw new ReportGenerationException("Bank statement PDF rendering failed", failure);
    }
  }

  /**
   * Renders a management snapshot one account and bounded holder chunk at a time.
   *
   * @param spool private snapshot and PDF storage
   * @param snapshot section and holder counts
   * @param from window start
   * @param to window end
   * @param userZone the caller's zone, or UTC when absent
   */
  public void management(
      BankReportSpool spool,
      BankReportSnapshotReader.Management snapshot,
      Instant from,
      Instant to,
      @Nullable ZoneId userZone) {
    ZoneId zone = userZone == null ? ZoneOffset.UTC : userZone;
    DateTimeFormatter stamp = DATE_TIME.withZone(zone);
    DateTimeFormatter date = DATE_ONLY.withZone(zone);
    try (var output = Files.newOutputStream(spool.file("report.pdf"))) {
      KrtPdfSupport.KrtDocument krt = KrtPdfSupport.open(output);
      try (var _ = krt.document()) {
        KrtPdfSupport.addTitle(krt, label("pdf.bank.report.title"));
        PdfPTable meta = KrtPdfSupport.newMetaTable();
        period(meta, stamp, from, to);
        add(krt, meta);
        if (snapshot.accounts() == 0) {
          add(
              krt,
              new Paragraph(
                  label("pdf.bank.report.empty"),
                  KrtPdfSupport.italic(10, KrtPdfSupport.COLOR_LIGHT_GRAY)));
        }
        boolean[] first = {true};
        BankReportSpool.read(
            spool.file("sections"),
            mapper,
            BankReportSpool.Section.class,
            section -> {
              if (!first[0]) {
                krt.document().newPage();
              }
              first[0] = false;
              add(krt, new Paragraph(" "));
              KrtPdfSupport.addSectionHeader(
                  krt,
                  label("pdf.bank.statement.account")
                      + " "
                      + section.account().accountNo()
                      + " - "
                      + section.account().name());
              PdfPTable summary = KrtPdfSupport.newMetaTable();
              KrtPdfSupport.addMetaRow(
                  summary,
                  label("pdf.bank.report.opening"),
                  BankPdfFormat.amount(section.opening()));
              KrtPdfSupport.addMetaRow(
                  summary,
                  label("pdf.bank.report.inflow"),
                  BankPdfFormat.signedAmount(section.inflow()));
              KrtPdfSupport.addMetaRow(
                  summary,
                  label("pdf.bank.report.outflow"),
                  BankPdfFormat.signedAmount(section.outflow()));
              KrtPdfSupport.addMetaRow(
                  summary,
                  label("pdf.bank.report.closing"),
                  BankPdfFormat.amount(section.closing()));
              add(krt, summary);
              KrtPdfSupport.addSectionHeader(krt, label("pdf.bank.report.chart"));
              BankBalanceEnvelope envelope = new BankBalanceEnvelope(from, to, section.opening());
              BankReportSpool.read(
                  spool.file(section.rowsFile()),
                  mapper,
                  BankReportSpool.Lines.class,
                  chunk ->
                      chunk.lines().forEach(line -> envelope.add(line.createdAt(), line.amount())));
              add(
                  krt,
                  BankBalanceChart.render(
                      krt.writer(), envelope, from, to, date.format(from), date.format(to)));
              add(krt, new Paragraph(" "));
              bookings(krt, spool, section, stamp, false, false);
            });
        if (snapshot.accounts() != 0) {
          krt.document().newPage();
        }
        KrtPdfSupport.addSectionHeader(krt, label("pdf.bank.report.holders"));
        holders(krt, spool, snapshot.holders());
        KrtPdfSupport.addFooter(krt);
      }
    } catch (Exception failure) {
      throw new ReportGenerationException("Bank management PDF rendering failed", failure);
    }
  }

  private void period(PdfPTable meta, DateTimeFormatter stamp, Instant from, Instant to) {
    KrtPdfSupport.addMetaRow(
        meta, label("pdf.bank.statement.period"), stamp.format(from) + " - " + stamp.format(to));
    KrtPdfSupport.addMetaRow(
        meta, label("pdf.bank.statement.generated"), stamp.format(Instant.now()));
  }

  private void bookings(
      KrtPdfSupport.KrtDocument krt,
      BankReportSpool spool,
      BankReportSpool.Section section,
      DateTimeFormatter stamp,
      boolean redactHolders,
      boolean withBalance) {
    int columns = withBalance ? redactHolders ? 5 : 6 : 5;
    PdfPTable table = new PdfPTable(columns);
    table.setWidthPercentage(100);
    table.setWidths(
        withBalance
            ? redactHolders
                ? new float[] {1.6f, 1.3f, 1.9f, 1.3f, 1.4f}
                : new float[] {1.5f, 1.2f, 1.4f, 1.8f, 1.2f, 1.3f}
            : new float[] {1.5f, 1.2f, 1.5f, 1.9f, 1.2f});
    for (String key : List.of("date", "type")) {
      KrtPdfSupport.addTableHeader(table, label("pdf.bank.col." + key));
    }
    if (!redactHolders) {
      KrtPdfSupport.addTableHeader(table, label("pdf.bank.col.holder"));
    }
    KrtPdfSupport.addTableHeader(table, label("pdf.bank.col.counterparty"));
    KrtPdfSupport.addTableHeader(table, label("pdf.bank.col.amount"));
    if (withBalance) {
      KrtPdfSupport.addTableHeader(table, label("pdf.bank.col.balance"));
    }
    table.setHeaderRows(1);
    table.setComplete(false);
    if (section.rows() == 0) {
      KrtPdfSupport.addEmptyRow(table, label("pdf.bank.statement.empty"), columns);
      table.setComplete(true);
      add(krt, table);
      return;
    }
    BigDecimal[] running = {section.opening()};
    long[] rendered = {0};
    BankReportSpool.read(
        spool.file(section.rowsFile()),
        mapper,
        BankReportSpool.Lines.class,
        chunk -> {
          for (BankReportSpool.Line line : chunk.lines()) {
            Color bg = KrtPdfSupport.rowBackground(rendered[0] % 2 != 0);
            running[0] = running[0].add(line.amount());
            KrtPdfSupport.addTableCell(table, stamp.format(line.createdAt()), bg, false);
            KrtPdfSupport.addTableCell(
                table, label("pdf.bank.type." + line.type().name()), bg, false);
            if (!redactHolders) {
              KrtPdfSupport.addTableCell(table, line.holder(), bg, false);
            }
            KrtPdfSupport.addTableCell(table, line.counterparty(), bg, false);
            KrtPdfSupport.addTableCell(table, BankPdfFormat.signedAmount(line.amount()), bg, true);
            if (withBalance) {
              KrtPdfSupport.addTableCell(table, BankPdfFormat.amount(running[0]), bg, true);
            }
            String reason = line.justification() == null ? "" : line.justification();
            String note = line.note() == null ? "" : line.note();
            String staffNote = line.staffNote() == null ? "" : line.staffNote();
            if (!reason.isEmpty() || !note.isEmpty() || !staffNote.isEmpty()) {
              KrtPdfSupport.addDetailSubRow(
                  table,
                  label("pdf.bank.sub.justification"),
                  reason,
                  label("pdf.bank.sub.note"),
                  note,
                  label("pdf.bank.sub.staffNote"),
                  staffNote,
                  bg,
                  columns);
            }
            rendered[0]++;
          }
          table.setComplete(rendered[0] == section.rows());
          add(krt, table);
          if (rendered[0] != section.rows()) {
            table.flushContent();
          }
        });
  }

  private void holders(KrtPdfSupport.KrtDocument krt, BankReportSpool spool, long count) {
    if (count == 0) {
      add(krt, BankPdfFormat.distributionTable(List.of(), this::label));
      return;
    }
    PdfPTable table = new PdfPTable(2);
    table.setWidthPercentage(100);
    table.setWidths(new float[] {3f, 1.5f});
    KrtPdfSupport.addTableHeader(table, label("pdf.bank.col.holder"));
    KrtPdfSupport.addTableHeader(table, label("pdf.bank.col.amount"));
    table.setHeaderRows(1);
    table.setComplete(false);
    long[] rendered = {0};
    BankReportSpool.read(
        spool.file("holders"),
        mapper,
        BankReportSpool.Holders.class,
        chunk -> {
          for (BankHolderBalance balance : chunk.balances()) {
            Color bg = KrtPdfSupport.rowBackground(rendered[0]++ % 2 != 0);
            KrtPdfSupport.addTableCell(table, balance.handle(), bg, false);
            KrtPdfSupport.addTableCell(table, BankPdfFormat.amount(balance.amount()), bg, true);
          }
          table.setComplete(rendered[0] == count);
          add(krt, table);
          if (rendered[0] != count) {
            table.flushContent();
          }
        });
  }

  private static void add(KrtPdfSupport.KrtDocument krt, Element element) {
    try {
      krt.document().add(element);
    } catch (Exception failure) {
      throw new ReportGenerationException("Bank report element rendering failed", failure);
    }
  }

  private @NotNull String label(String key) {
    return messageSource.getMessage(key, null, Locale.GERMAN);
  }
}
