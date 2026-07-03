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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.exception.ReportGenerationException;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.BankTransactionType;
import de.greluc.krt.profit.basetool.backend.model.projection.BankBookingRow;
import de.greluc.krt.profit.basetool.backend.model.projection.BankCounterLeg;
import de.greluc.krt.profit.basetool.backend.model.projection.BankHolderLeg;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository;
import de.greluc.krt.profit.basetool.backend.service.pdf.BankPdfFormat;
import de.greluc.krt.profit.basetool.backend.service.pdf.KrtPdfSupport;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.openpdf.text.pdf.PdfPTable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Renders the bank account statement PDF (REQ-BANK-014, epic #556 Phase 3): for a caller-chosen
 * period it shows the opening balance, every posting of the account in chronological order with a
 * running balance and the booking holder (derived from the holder ledger by amount sign, ADR-0039),
 * and the closing balance. Since holders are decoupled from accounts there is no per-account
 * closing holder distribution any more (REQ-BANK-003). Statements are computed on demand from the
 * append-only ledger and never persisted (owner decision on spec question 4); each export writes
 * one {@code STATEMENT_EXPORTED} audit event carrying the period (REQ-BANK-012).
 *
 * <p>Labels come from the backend message bundle and are German by design — the documents are
 * org-internal DAS KARTELL paperwork. The visual layer (Lato, dark background, orange accents) is
 * the shared {@link KrtPdfSupport}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BankStatementReportService {

  /** Timestamp pattern for booking rows and the generated-at line; zone bound per request. */
  private static final DateTimeFormatter DATE_TIME_PATTERN =
      DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

  private final BankAccountRepository bankAccountRepository;
  private final BankPostingRepository bankPostingRepository;
  private final BankHolderPostingRepository bankHolderPostingRepository;
  private final BankAuditService bankAuditService;
  private final MessageSource messageSource;

  /**
   * Generates the full bank-staff statement PDF (with the holder/Halter column) for one account and
   * period and records the export. Equivalent to {@link #generateStatement(UUID, Instant, Instant,
   * ZoneId, boolean)} with {@code redactHolders = false}.
   *
   * @param accountId the account
   * @param from period start (inclusive)
   * @param to period end (inclusive); must not be before {@code from}
   * @param userZone the zone to render timestamps in; {@code null} falls back to UTC
   * @return the PDF bytes
   * @throws NotFoundException when the account is unknown
   * @throws BadRequestException when the period is inverted
   */
  @Transactional
  public byte @NotNull [] generateStatement(
      @NotNull UUID accountId,
      @NotNull Instant from,
      @NotNull Instant to,
      @Nullable ZoneId userZone) {
    return generateStatement(accountId, from, to, userZone, false);
  }

  /**
   * Generates the statement PDF for one account and period and records the export in the audit log.
   * Write transaction on purpose: the audit insert runs {@code MANDATORY} inside it.
   *
   * <p>When {@code redactHolders} is {@code true} only the player-custody ("Halter") column is
   * omitted — the redacted variant the org-unit-aware seam ({@code OrgUnitBankAccessService}) hands
   * to org-unit viewers of an account they may see but do not staff (REQ-BANK-038): they keep the
   * full history (date / type / Quell-/Zielkonto / amount / running balance, plus the
   * Begr&uuml;ndung and Notiz in the per-booking sub-row) — including the counter-account and the
   * counterparty Einzahler/Empf&auml;nger (REQ-BANK-044, owner decision) — but not who physically
   * holds the money. Bank staff pass {@code false} and additionally get the Halter column
   * (REQ-BANK-014).
   *
   * @param accountId the account
   * @param from period start (inclusive)
   * @param to period end (inclusive); must not be before {@code from}
   * @param userZone the zone to render timestamps in; {@code null} falls back to UTC
   * @param redactHolders {@code true} to omit the holder/Halter column (org-unit viewers)
   * @return the PDF bytes
   * @throws NotFoundException when the account is unknown
   * @throws BadRequestException when the period is inverted
   */
  @Transactional
  public byte @NotNull [] generateStatement(
      @NotNull UUID accountId,
      @NotNull Instant from,
      @NotNull Instant to,
      @Nullable ZoneId userZone,
      boolean redactHolders) {
    BankAccount account =
        bankAccountRepository
            .findById(accountId)
            .orElseThrow(() -> new NotFoundException("Bank account not found"));
    if (from.isAfter(to)) {
      throw new BadRequestException("Statement period start must not be after its end");
    }

    BigDecimal opening = bankPostingRepository.accountBalanceBefore(accountId, from);
    List<BankBookingRow> rows = bankPostingRepository.findBookingsInPeriod(accountId, from, to);
    List<UUID> txIds = rows.stream().map(BankBookingRow::transactionId).distinct().toList();
    // The holder column is redacted for org-unit viewers, so the holder-leg query is skipped too.
    Map<UUID, List<BankHolderLeg>> holderLegsByTx =
        (redactHolders || txIds.isEmpty())
            ? Map.of()
            : bankHolderPostingRepository.findHolderLegsByTransactionIds(txIds).stream()
                .collect(Collectors.groupingBy(BankHolderLeg::transactionId));
    // Account legs back the "Quell-/Zielkonto" column's transfer counter-account (REQ-BANK-044).
    // Fetched for both variants: the member-facing redacted statement keeps that column too — only
    // the aUEC-custody Halter is redacted now (REQ-BANK-038 amended), not the
    // counter-account/party.
    Map<UUID, List<BankCounterLeg>> accountLegsByTx =
        txIds.isEmpty()
            ? Map.of()
            : bankPostingRepository.findLegsByTransactionIds(txIds).stream()
                .collect(Collectors.groupingBy(BankCounterLeg::transactionId));

    byte[] pdf =
        buildPdf(
            account,
            from,
            to,
            opening,
            rows,
            holderLegsByTx,
            accountLegsByTx,
            userZone,
            redactHolders);
    bankAuditService.record(
        BankAuditEventType.STATEMENT_EXPORTED,
        accountId,
        null,
        null,
        AuditDetails.of("period", from + ".." + to));
    log.info(
        "Bank statement exported for account {} ({} rows{})",
        account.getAccountNo(),
        rows.size(),
        redactHolders ? ", holders redacted" : "");
    return pdf;
  }

  private byte @NotNull [] buildPdf(
      @NotNull BankAccount account,
      @NotNull Instant from,
      @NotNull Instant to,
      @NotNull BigDecimal opening,
      @NotNull List<BankBookingRow> rows,
      @NotNull Map<UUID, List<BankHolderLeg>> holderLegsByTx,
      @NotNull Map<UUID, List<BankCounterLeg>> accountLegsByTx,
      @Nullable ZoneId userZone,
      boolean redactHolders) {
    ZoneId zone = userZone != null ? userZone : ZoneOffset.UTC;
    DateTimeFormatter stamp = DATE_TIME_PATTERN.withZone(zone);

    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      KrtPdfSupport.KrtDocument krt = KrtPdfSupport.open(baos);

      KrtPdfSupport.addTitle(krt, label("pdf.bank.statement.title"));

      BigDecimal closing =
          rows.stream().map(BankBookingRow::amount).reduce(opening, BigDecimal::add);

      PdfPTable meta = KrtPdfSupport.newMetaTable();
      KrtPdfSupport.addMetaRow(
          meta,
          label("pdf.bank.statement.account"),
          account.getAccountNo() + " — " + account.getName());
      KrtPdfSupport.addMetaRow(
          meta, label("pdf.bank.statement.period"), stamp.format(from) + " – " + stamp.format(to));
      KrtPdfSupport.addMetaRow(
          meta, label("pdf.bank.statement.generated"), stamp.format(Instant.now()));
      KrtPdfSupport.addMetaRow(
          meta, label("pdf.bank.statement.openingBalance"), BankPdfFormat.amount(opening));
      KrtPdfSupport.addMetaRow(
          meta, label("pdf.bank.statement.closingBalance"), BankPdfFormat.amount(closing));
      krt.document().add(meta);

      KrtPdfSupport.addSectionHeader(krt, label("pdf.bank.statement.bookings"));

      // Org-unit viewers get the same history without the player-custody "Halter" column
      // (REQ-BANK-038): only the aUEC-custody holder is redacted. The "Quell-/Zielkonto" column
      // (the
      // transfer counter-account, or the Einzahler/Empfänger of a deposit/withdrawal, REQ-BANK-044)
      // is kept for them too (owner decision). The Begründung + Notiz of each booking move into an
      // indented sub-row beneath the row (REQ-BANK-045), reason first, so the main row stays
      // narrow.
      int columns = redactHolders ? 5 : 6;
      PdfPTable table = new PdfPTable(columns);
      table.setWidthPercentage(100);
      table.setWidths(
          redactHolders
              ? new float[] {1.6f, 1.3f, 1.9f, 1.3f, 1.4f}
              : new float[] {1.5f, 1.2f, 1.4f, 1.8f, 1.2f, 1.3f});
      KrtPdfSupport.addTableHeader(table, label("pdf.bank.col.date"));
      KrtPdfSupport.addTableHeader(table, label("pdf.bank.col.type"));
      if (!redactHolders) {
        KrtPdfSupport.addTableHeader(table, label("pdf.bank.col.holder"));
      }
      KrtPdfSupport.addTableHeader(table, label("pdf.bank.col.counterparty"));
      KrtPdfSupport.addTableHeader(table, label("pdf.bank.col.amount"));
      KrtPdfSupport.addTableHeader(table, label("pdf.bank.col.balance"));

      String reasonLabel = label("pdf.bank.sub.justification");
      String noteLabel = label("pdf.bank.sub.note");
      boolean alt = false;
      BigDecimal running = opening;
      for (BankBookingRow row : rows) {
        running = running.add(row.amount());
        Color bg = KrtPdfSupport.rowBackground(alt);
        KrtPdfSupport.addTableCell(table, stamp.format(row.createdAt()), bg, false);
        KrtPdfSupport.addTableCell(table, label("pdf.bank.type." + row.type().name()), bg, false);
        if (!redactHolders) {
          String holder =
              row.type() == BankTransactionType.WIPE_RESET
                  ? ""
                  : matchHolderHandle(
                      holderLegsByTx.getOrDefault(row.transactionId(), List.of()),
                      row.amount().signum());
          KrtPdfSupport.addTableCell(table, holder, bg, false);
        }
        KrtPdfSupport.addTableCell(table, counterpartyCell(row, accountLegsByTx), bg, false);
        KrtPdfSupport.addTableCell(table, BankPdfFormat.signedAmount(row.amount()), bg, true);
        KrtPdfSupport.addTableCell(table, BankPdfFormat.amount(running), bg, true);
        String reason = row.justification() != null ? row.justification() : "";
        String note = row.note() != null ? row.note() : "";
        if (!reason.isEmpty() || !note.isEmpty()) {
          KrtPdfSupport.addDetailSubRow(table, reasonLabel, reason, noteLabel, note, bg, columns);
        }
        alt = !alt;
      }
      if (rows.isEmpty()) {
        KrtPdfSupport.addEmptyRow(table, label("pdf.bank.statement.empty"), columns);
      }
      krt.document().add(table);

      KrtPdfSupport.addFooter(krt);
      krt.document().close();
      return baos.toByteArray();
    } catch (BadRequestException | NotFoundException e) {
      throw e;
    } catch (Exception e) {
      throw new ReportGenerationException("PDF generation failed", e);
    }
  }

  /**
   * Renders the "Quell-/Zielkonto" cell for a statement row (REQ-BANK-044) — the far side of the
   * booking: for a {@code DEPOSIT}/{@code WITHDRAWAL} the recorded counterparty (Einzahler /
   * Empf&auml;nger) with their org unit in parentheses; for a {@code TRANSFER} the counter
   * account's number (the account leg on the other account); empty for every other type or when
   * nothing was recorded. The type column and the amount sign already convey the direction, so no
   * arrow glyph is rendered (WinAnsi carries no arrow glyph either).
   *
   * @param row the statement row
   * @param accountLegsByTx the page's account legs grouped by transaction (for transfer counter
   *     accounts)
   * @return the cell text, never {@code null}
   */
  private static @NotNull String counterpartyCell(
      @NotNull BankBookingRow row, @NotNull Map<UUID, List<BankCounterLeg>> accountLegsByTx) {
    return switch (row.type()) {
      case DEPOSIT, WITHDRAWAL -> {
        if (row.counterpartyHandle() == null) {
          yield "";
        }
        yield row.counterpartyOrgUnitName() == null
            ? row.counterpartyHandle()
            : row.counterpartyHandle() + " (" + row.counterpartyOrgUnitName() + ")";
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
