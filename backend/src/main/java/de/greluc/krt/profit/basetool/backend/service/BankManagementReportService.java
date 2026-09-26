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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.exception.ReportGenerationException;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.BankTransactionType;
import de.greluc.krt.profit.basetool.backend.model.projection.BankBookingRow;
import de.greluc.krt.profit.basetool.backend.model.projection.BankCounterLeg;
import de.greluc.krt.profit.basetool.backend.model.projection.BankHolderBalance;
import de.greluc.krt.profit.basetool.backend.model.projection.BankHolderLeg;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository;
import de.greluc.krt.profit.basetool.backend.service.pdf.BankBalanceChart;
import de.greluc.krt.profit.basetool.backend.service.pdf.BankPdfFormat;
import de.greluc.krt.profit.basetool.backend.service.pdf.KrtPdfSupport;
import de.greluc.krt.profit.basetool.backend.support.HandleAnonymisation;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfPTable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Renders the management three-month report PDF (REQ-BANK-015): per account a summary block and the
 * itemized bookings of the rolling window, followed by one global holder-balance section.
 *
 * <p>Each export writes a {@code MANAGEMENT_REPORT_EXPORTED} audit event (REQ-BANK-012).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BankManagementReportService {

  /** Timestamp pattern for booking rows and meta lines; zone bound per request. */
  private static final DateTimeFormatter DATE_TIME_PATTERN =
      DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

  /** Date-only pattern for the balance chart's compact x-axis labels; zone bound per request. */
  private static final DateTimeFormatter DATE_ONLY_PATTERN =
      DateTimeFormatter.ofPattern("dd.MM.yy");

  private final BankAccountRepository bankAccountRepository;
  private final BankPostingRepository bankPostingRepository;
  private final BankHolderPostingRepository bankHolderPostingRepository;
  private final BankAuditService bankAuditService;
  private final MessageSource messageSource;

  /**
   * Generates the three-month report over all accounts and records the export in the audit log.
   * Write transaction on purpose: the audit insert runs {@code MANDATORY} inside it.
   *
   * @param userZone the zone to render timestamps in; {@code null} falls back to UTC
   * @return the PDF bytes
   */
  @Transactional
  public byte @NotNull [] generateThreeMonthReport(@Nullable ZoneId userZone) {
    Instant to = Instant.now();
    Instant from = ZonedDateTime.ofInstant(to, ZoneOffset.UTC).minusMonths(3).toInstant();
    List<BankAccount> accounts = bankAccountRepository.findAllByOrderByAccountNoAsc();

    byte[] pdf = buildPdf(accounts, from, to, userZone);
    bankAuditService.record(
        BankAuditEventType.MANAGEMENT_REPORT_EXPORTED,
        null,
        null,
        null,
        "period=" + from + ".." + to + ", accounts=" + accounts.size());
    log.info("Bank three-month report exported ({} accounts)", accounts.size());
    return pdf;
  }

  private byte @NotNull [] buildPdf(
      @NotNull List<BankAccount> accounts,
      @NotNull Instant from,
      @NotNull Instant to,
      @Nullable ZoneId userZone) {
    ZoneId zone = userZone != null ? userZone : ZoneOffset.UTC;
    DateTimeFormatter stamp = DATE_TIME_PATTERN.withZone(zone);
    DateTimeFormatter dateOnly = DATE_ONLY_PATTERN.withZone(zone);

    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      KrtPdfSupport.KrtDocument krt = KrtPdfSupport.open(baos);

      KrtPdfSupport.addTitle(krt, label("pdf.bank.report.title"));

      PdfPTable meta = KrtPdfSupport.newMetaTable();
      KrtPdfSupport.addMetaRow(
          meta, label("pdf.bank.statement.period"), stamp.format(from) + " – " + stamp.format(to));
      KrtPdfSupport.addMetaRow(
          meta, label("pdf.bank.statement.generated"), stamp.format(Instant.now()));
      krt.document().add(meta);

      if (accounts.isEmpty()) {
        Paragraph empty =
            new Paragraph(
                label("pdf.bank.report.empty"),
                KrtPdfSupport.italic(10, KrtPdfSupport.COLOR_LIGHT_GRAY));
        krt.document().add(empty);
      }

      boolean firstAccount = true;
      for (BankAccount account : accounts) {
        if (!firstAccount) {
          krt.document().newPage();
        }
        addAccountSection(krt, account, from, to, stamp, dateOnly);
        firstAccount = false;
      }

      addGlobalHolderSection(krt, accounts.isEmpty());

      KrtPdfSupport.addFooter(krt);
      krt.document().close();
      return baos.toByteArray();
    } catch (Exception e) {
      throw new ReportGenerationException("PDF generation failed", e);
    }
  }

  /**
   * Adds one account's report section: header, summary block, balance chart and the itemized
   * bookings of the window with the per-booking holder.
   *
   * @param krt the open document handle
   * @param account the account to render
   * @param from window start (inclusive)
   * @param to window end (inclusive)
   * @param stamp the zone-bound timestamp formatter
   * @param dateOnly the zone-bound date-only formatter for the chart's x-axis
   */
  private void addAccountSection(
      @NotNull KrtPdfSupport.KrtDocument krt,
      @NotNull BankAccount account,
      @NotNull Instant from,
      @NotNull Instant to,
      @NotNull DateTimeFormatter stamp,
      @NotNull DateTimeFormatter dateOnly) {
    BigDecimal opening = bankPostingRepository.accountBalanceBefore(account.getId(), from);
    List<BankBookingRow> rows =
        bankPostingRepository.findBookingsInPeriod(account.getId(), from, to);
    List<UUID> txIds = rows.stream().map(BankBookingRow::transactionId).distinct().toList();
    final Map<UUID, List<BankHolderLeg>> holderLegsByTx =
        txIds.isEmpty()
            ? Map.of()
            : bankHolderPostingRepository.findHolderLegsByTransactionIds(txIds).stream()
                .collect(Collectors.groupingBy(BankHolderLeg::transactionId));
    final Map<UUID, List<BankCounterLeg>> accountLegsByTx =
        txIds.isEmpty()
            ? Map.of()
            : bankPostingRepository.findLegsByTransactionIds(txIds).stream()
                .collect(Collectors.groupingBy(BankCounterLeg::transactionId));

    BigDecimal inflow = BigDecimal.ZERO;
    BigDecimal outflow = BigDecimal.ZERO;
    for (BankBookingRow row : rows) {
      if (row.amount().signum() > 0) {
        inflow = inflow.add(row.amount());
      } else {
        outflow = outflow.add(row.amount());
      }
    }
    final BigDecimal closing = opening.add(inflow).add(outflow);
    krt.document().add(new Paragraph(" "));
    KrtPdfSupport.addSectionHeader(
        krt,
        label("pdf.bank.statement.account")
            + " "
            + account.getAccountNo()
            + " — "
            + account.getName());

    PdfPTable summary = KrtPdfSupport.newMetaTable();
    KrtPdfSupport.addMetaRow(
        summary, label("pdf.bank.report.opening"), BankPdfFormat.amount(opening));
    KrtPdfSupport.addMetaRow(
        summary, label("pdf.bank.report.inflow"), BankPdfFormat.signedAmount(inflow));
    KrtPdfSupport.addMetaRow(
        summary, label("pdf.bank.report.outflow"), BankPdfFormat.signedAmount(outflow));
    KrtPdfSupport.addMetaRow(
        summary, label("pdf.bank.report.closing"), BankPdfFormat.amount(closing));
    krt.document().add(summary);

    KrtPdfSupport.addSectionHeader(krt, label("pdf.bank.report.chart"));
    krt.document()
        .add(
            BankBalanceChart.render(
                krt.writer(), opening, rows, from, to, dateOnly.format(from), dateOnly.format(to)));
    krt.document().add(new Paragraph(" "));

    PdfPTable table = new PdfPTable(5);
    table.setWidthPercentage(100);
    table.setWidths(new float[] {1.5f, 1.2f, 1.5f, 1.9f, 1.2f});
    KrtPdfSupport.addTableHeader(table, label("pdf.bank.col.date"));
    KrtPdfSupport.addTableHeader(table, label("pdf.bank.col.type"));
    KrtPdfSupport.addTableHeader(table, label("pdf.bank.col.holder"));
    KrtPdfSupport.addTableHeader(table, label("pdf.bank.col.counterparty"));
    KrtPdfSupport.addTableHeader(table, label("pdf.bank.col.amount"));

    String reasonLabel = label("pdf.bank.sub.justification");
    String noteLabel = label("pdf.bank.sub.note");
    String staffNoteLabel = label("pdf.bank.sub.staffNote");
    boolean alt = false;
    for (BankBookingRow row : rows) {
      Color bg = KrtPdfSupport.rowBackground(alt);
      String holder =
          row.type() == BankTransactionType.WIPE_RESET
              ? ""
              : matchHolderHandle(
                  holderLegsByTx.getOrDefault(row.transactionId(), List.of()),
                  row.amount().signum());
      KrtPdfSupport.addTableCell(table, stamp.format(row.createdAt()), bg, false);
      KrtPdfSupport.addTableCell(table, label("pdf.bank.type." + row.type().name()), bg, false);
      KrtPdfSupport.addTableCell(
          table,
          HandleAnonymisation.humanise(holder, label("general.anonymisedHandle")),
          bg,
          false);
      KrtPdfSupport.addTableCell(
          table,
          counterpartyCell(row, accountLegsByTx, label("general.anonymisedHandle")),
          bg,
          false);
      KrtPdfSupport.addTableCell(table, BankPdfFormat.signedAmount(row.amount()), bg, true);
      String reason = row.justification() != null ? row.justification() : "";
      String note = row.note() != null ? row.note() : "";
      String staffNote = row.staffNote() != null ? row.staffNote() : "";
      if (!reason.isEmpty() || !note.isEmpty() || !staffNote.isEmpty()) {
        KrtPdfSupport.addDetailSubRow(
            table, reasonLabel, reason, noteLabel, note, staffNoteLabel, staffNote, bg, 5);
      }
      alt = !alt;
    }
    if (rows.isEmpty()) {
      KrtPdfSupport.addEmptyRow(table, label("pdf.bank.statement.empty"), 5);
    }
    krt.document().add(table);
  }

  /**
   * Renders the "Quell-/Zielkonto" cell of a report row (REQ-BANK-044): the counterparty with org
   * unit for a {@code DEPOSIT}/{@code WITHDRAWAL}, the counter account's number for a {@code
   * TRANSFER}, empty otherwise.
   *
   * @param row the report row
   * @param accountLegsByTx the section's account legs grouped by transaction
   * @return the cell text, never {@code null}
   */
  private static @NotNull String counterpartyCell(
      @NotNull BankBookingRow row,
      @NotNull Map<UUID, List<BankCounterLeg>> accountLegsByTx,
      @NotNull String anonymisedLabel) {
    return switch (row.type()) {
      case DEPOSIT, WITHDRAWAL -> {
        if (row.counterpartyHandle() == null) {
          yield "";
        }
        String handle = HandleAnonymisation.humanise(row.counterpartyHandle(), anonymisedLabel);
        yield row.counterpartyOrgUnitName() == null
            ? handle
            : handle + " (" + row.counterpartyOrgUnitName() + ")";
      }
      case TRANSFER ->
          accountLegsByTx.getOrDefault(row.transactionId(), List.of()).stream()
              .filter(leg -> !leg.postingId().equals(row.postingId()))
              .map(BankCounterLeg::accountNo)
              .findFirst()
              .orElse("");
      default -> "";
    };
  }

  /**
   * Adds the global holder-balance section (ADR-0039): each holder's bank-wide custody total,
   * largest first.
   *
   * @param krt the open document handle
   * @param noAccounts whether the report had no accounts, in which case the section is appended
   *     inline rather than on a fresh page
   */
  private void addGlobalHolderSection(@NotNull KrtPdfSupport.KrtDocument krt, boolean noAccounts) {
    if (!noAccounts) {
      krt.document().newPage();
    }
    KrtPdfSupport.addSectionHeader(krt, label("pdf.bank.report.holders"));
    List<BankHolderBalance> totals =
        bankHolderPostingRepository.holderTotals().stream()
            .filter(h -> h.amount().signum() != 0)
            .sorted(
                Comparator.comparing(BankHolderBalance::amount)
                    .reversed()
                    .thenComparing(BankHolderBalance::handle))
            .toList();
    krt.document().add(BankPdfFormat.distributionTable(totals, this::label));
  }

  /**
   * Picks the handle of the holder leg whose amount sign matches a posting row's sign — the holder
   * paired with that account leg in the same transaction (ADR-0039); empty when none exists.
   *
   * @param holderLegs the transaction's holder legs
   * @param sign the wanted amount sign (+1 / -1)
   * @return the matching holder's handle, or an empty string
   */
  private static @NotNull String matchHolderHandle(
      @NotNull List<BankHolderLeg> holderLegs, int sign) {
    return holderLegs.stream()
        .filter(leg -> leg.amount().signum() == sign)
        .map(BankHolderLeg::handle)
        .findFirst()
        .orElse("");
  }

  /**
   * Resolves one German PDF label from the backend message bundle.
   *
   * @param key the message key
   * @return the resolved label
   */
  private @NotNull String label(@NotNull String key) {
    return messageSource.getMessage(key, null, Locale.GERMAN);
  }
}
