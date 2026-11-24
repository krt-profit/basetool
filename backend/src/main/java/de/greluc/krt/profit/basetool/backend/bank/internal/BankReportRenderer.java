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
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfDocument;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfDocuments;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfDataTable;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfKeyValue;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfTableCell;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Renders detached Bank snapshots as tagged PDFs: tables drawn chunk by chunk and a bounded chart
 * envelope, so the working set does not grow with the ledger (REQ-BANK-014/015, REQ-API-029).
 */
@Component
@RequiredArgsConstructor
public class BankReportRenderer {
  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
  private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("dd.MM.yy");

  /** Columns of the statement with holders: date, type, holder, counterparty, amount, balance. */
  private static final float[] STATEMENT_WIDTHS = {1.5f, 1.2f, 1.4f, 1.8f, 1.2f, 1.3f};

  /** Columns of the statement without holders: date, type, counterparty, amount, balance. */
  private static final float[] REDACTED_STATEMENT_WIDTHS = {1.6f, 1.3f, 1.9f, 1.3f, 1.4f};

  /** Columns of a report's booking table: date, type, holder, counterparty, amount. */
  private static final float[] REPORT_WIDTHS = {1.5f, 1.2f, 1.5f, 1.9f, 1.2f};

  private final ObjectMapper mapper;
  private final MessageSource messageSource;
  private final KrtPdfDocuments pdfDocuments;

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
    try (OutputStream output = Files.newOutputStream(spool.file("report.pdf"));
        KrtPdfDocument pdf = pdfDocuments.open(output, label("pdf.bank.statement.title"))) {
      List<PdfKeyValue> meta = new ArrayList<>();
      meta.add(
          new PdfKeyValue(
              label("pdf.bank.statement.account"),
              section.account().accountNo() + " - " + section.account().name()));
      meta.addAll(period(stamp, from, to));
      meta.add(
          new PdfKeyValue(
              label("pdf.bank.statement.openingBalance"), BankPdfFormat.amount(section.opening())));
      meta.add(
          new PdfKeyValue(
              label("pdf.bank.statement.closingBalance"), BankPdfFormat.amount(section.closing())));
      pdf.keyValueTable(meta);
      pdf.heading(label("pdf.bank.statement.bookings"));
      bookings(pdf, spool, section, stamp, redactHolders, true);
      pdf.footer();
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
    try (OutputStream output = Files.newOutputStream(spool.file("report.pdf"));
        KrtPdfDocument pdf = pdfDocuments.open(output, label("pdf.bank.report.title"))) {
      pdf.keyValueTable(period(stamp, from, to));
      if (snapshot.accounts() == 0) {
        pdf.notice(label("pdf.bank.report.empty"));
      }
      boolean[] first = {true};
      BankReportSpool.read(
          spool.file("sections"),
          mapper,
          BankReportSpool.Section.class,
          section -> {
            if (!first[0]) {
              pdf.newPage();
            }
            first[0] = false;
            account(pdf, spool, section, from, to, stamp, date);
          });
      if (snapshot.accounts() != 0) {
        pdf.newPage();
      }
      pdf.heading(label("pdf.bank.report.holders"));
      holders(pdf, spool, snapshot.holders());
      pdf.footer();
    } catch (Exception failure) {
      throw new ReportGenerationException("Bank management PDF rendering failed", failure);
    }
  }

  /**
   * Draws one account of the management report: heading, summary, balance chart and bookings.
   *
   * @param pdf the open document
   * @param spool the snapshot storage
   * @param section the account's snapshot section
   * @param from window start
   * @param to window end
   * @param stamp the timestamp format in the caller's zone
   * @param date the chart's date format in the caller's zone
   */
  private void account(
      KrtPdfDocument pdf,
      BankReportSpool spool,
      BankReportSpool.Section section,
      Instant from,
      Instant to,
      DateTimeFormatter stamp,
      DateTimeFormatter date) {
    String account = section.account().accountNo() + " - " + section.account().name();
    pdf.sectionGap();
    pdf.heading(label("pdf.bank.statement.account") + " " + account);
    pdf.keyValueTable(
        List.of(
            new PdfKeyValue(
                label("pdf.bank.report.opening"), BankPdfFormat.amount(section.opening())),
            new PdfKeyValue(
                label("pdf.bank.report.inflow"), BankPdfFormat.signedAmount(section.inflow())),
            new PdfKeyValue(
                label("pdf.bank.report.outflow"), BankPdfFormat.signedAmount(section.outflow())),
            new PdfKeyValue(
                label("pdf.bank.report.closing"), BankPdfFormat.amount(section.closing()))));
    pdf.heading(label("pdf.bank.report.chart"));
    BankBalanceEnvelope envelope = new BankBalanceEnvelope(from, to, section.opening());
    BankReportSpool.read(
        spool.file(section.rowsFile()),
        mapper,
        BankReportSpool.Lines.class,
        chunk -> chunk.lines().forEach(line -> envelope.add(line.createdAt(), line.amount())));
    String fromLabel = date.format(from);
    String toLabel = date.format(to);
    pdf.figure(
        BankBalanceChart.WIDTH,
        BankBalanceChart.HEIGHT,
        label(
            "pdf.bank.report.chart.alt",
            account,
            fromLabel,
            toLabel,
            BankPdfFormat.amount(section.opening()),
            BankPdfFormat.amount(section.closing()),
            BankPdfFormat.amount(envelope.getMinimum()),
            BankPdfFormat.amount(envelope.getMaximum())),
        canvas -> BankBalanceChart.draw(canvas, envelope, from, to, fromLabel, toLabel));
    pdf.sectionGap();
    bookings(pdf, spool, section, stamp, false, false);
  }

  private @NotNull List<PdfKeyValue> period(DateTimeFormatter stamp, Instant from, Instant to) {
    return List.of(
        new PdfKeyValue(
            label("pdf.bank.statement.period"), stamp.format(from) + " - " + stamp.format(to)),
        new PdfKeyValue(label("pdf.bank.statement.generated"), stamp.format(Instant.now())));
  }

  private void bookings(
      KrtPdfDocument pdf,
      BankReportSpool spool,
      BankReportSpool.Section section,
      DateTimeFormatter stamp,
      boolean redactHolders,
      boolean withBalance) {
    List<String> headers = new ArrayList<>();
    headers.add(label("pdf.bank.col.date"));
    headers.add(label("pdf.bank.col.type"));
    if (!redactHolders) {
      headers.add(label("pdf.bank.col.holder"));
    }
    headers.add(label("pdf.bank.col.counterparty"));
    headers.add(label("pdf.bank.col.amount"));
    if (withBalance) {
      headers.add(label("pdf.bank.col.balance"));
    }
    float[] widths =
        withBalance ? redactHolders ? REDACTED_STATEMENT_WIDTHS : STATEMENT_WIDTHS : REPORT_WIDTHS;
    try (PdfDataTable table = pdf.dataTable(widths, headers)) {
      if (section.rows() == 0) {
        table.emptyRow(label("pdf.bank.statement.empty"));
        return;
      }
      BigDecimal[] running = {section.opening()};
      BankReportSpool.read(
          spool.file(section.rowsFile()),
          mapper,
          BankReportSpool.Lines.class,
          chunk -> {
            for (BankReportSpool.Line line : chunk.lines()) {
              running[0] = running[0].add(line.amount());
              List<PdfTableCell> cells = new ArrayList<>();
              cells.add(PdfTableCell.text(stamp.format(line.createdAt())));
              cells.add(PdfTableCell.text(label("pdf.bank.type." + line.type().name())));
              if (!redactHolders) {
                cells.add(PdfTableCell.text(line.holder()));
              }
              cells.add(PdfTableCell.text(line.counterparty()));
              cells.add(PdfTableCell.centered(BankPdfFormat.signedAmount(line.amount())));
              if (withBalance) {
                cells.add(PdfTableCell.centered(BankPdfFormat.amount(running[0])));
              }
              table.row(cells);
              table.detailRow(
                  List.of(
                      new PdfKeyValue(
                          label("pdf.bank.sub.justification"), orEmpty(line.justification())),
                      new PdfKeyValue(label("pdf.bank.sub.note"), orEmpty(line.note())),
                      new PdfKeyValue(label("pdf.bank.sub.staffNote"), orEmpty(line.staffNote()))));
            }
          });
    }
  }

  private void holders(KrtPdfDocument pdf, BankReportSpool spool, long count) {
    if (count == 0) {
      BankPdfFormat.distributionTable(pdf, List.of(), this::label);
      return;
    }
    try (PdfDataTable table = BankPdfFormat.holderTable(pdf, this::label)) {
      BankReportSpool.read(
          spool.file("holders"),
          mapper,
          BankReportSpool.Holders.class,
          chunk -> chunk.balances().forEach(balance -> BankPdfFormat.holderRow(table, balance)));
    }
  }

  private static @NotNull String orEmpty(@Nullable String text) {
    return text == null ? "" : text;
  }

  private @NotNull String label(String key) {
    return messageSource.getMessage(key, null, Locale.GERMAN);
  }

  private @NotNull String label(String key, Object... arguments) {
    return messageSource.getMessage(key, arguments, Locale.GERMAN);
  }
}
