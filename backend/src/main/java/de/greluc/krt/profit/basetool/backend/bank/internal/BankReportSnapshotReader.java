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

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.kernel.ReportGenerationException;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Captures bounded immutable Bank export chunks within a repeatable-read database snapshot. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class BankReportSnapshotReader {
  private final BankAccountRepository accountRepository;
  private final BankPostingRepository postingRepository;
  private final BankHolderPostingRepository holderRepository;
  private final EntityManager entityManager;
  private final ObjectMapper mapper;
  private final MessageSource messageSource;

  /**
   * Captures one account statement before any PDF rendering begins.
   *
   * @param spool the private export storage
   * @param accountId the selected account
   * @param from inclusive period start
   * @param to inclusive period end
   * @param redactHolders whether holder names and staff notes are omitted
   * @return detached section metadata with exact totals
   */
  public BankReportSpool.Section statement(
      BankReportSpool spool, UUID accountId, Instant from, Instant to, boolean redactHolders) {
    BankAccount account =
        Entities.require(accountRepository.findById(accountId), "Bank account not found");
    if (from.isAfter(to)) {
      throw new BadRequestException("Statement period start must not be after its end");
    }
    return section(spool, account, from, to, redactHolders, "rows-0");
  }

  /**
   * Captures paged account sections and holder balances in the same database snapshot.
   *
   * @param spool the private export storage
   * @param from inclusive period start
   * @param to inclusive period end
   * @return account and holder counts for the report's empty states
   */
  public Management management(BankReportSpool spool, Instant from, Instant to) {
    long accountCount = 0;
    long holderCount = 0;
    try (var sections = BankReportSpool.writer(spool.file("sections"));
        var holders = BankReportSpool.writer(spool.file("holders"))) {
      int page = 0;
      while (true) {
        List<BankAccount> accounts =
            accountRepository.findAllByOrderByAccountNoAsc(
                PageRequest.of(page++, BankReportSpool.CHUNK_SIZE));
        for (BankAccount account : accounts) {
          BankReportSpool.append(
              sections, mapper, section(spool, account, from, to, false, "rows-" + accountCount++));
        }
        entityManager.clear();
        if (accounts.size() < BankReportSpool.CHUNK_SIZE) {
          break;
        }
      }
      page = 0;
      while (true) {
        List<BankHolderBalance> balances =
            holderRepository.reportHolderTotals(PageRequest.of(page++, BankReportSpool.CHUNK_SIZE));
        if (!balances.isEmpty()) {
          BankReportSpool.append(holders, mapper, new BankReportSpool.Holders(balances));
          holderCount += balances.size();
        }
        if (balances.size() < BankReportSpool.CHUNK_SIZE) {
          break;
        }
      }
    } catch (IOException failure) {
      throw new ReportGenerationException("Bank report snapshot write failed", failure);
    }
    return new Management(accountCount, holderCount);
  }

  private BankReportSpool.Section section(
      BankReportSpool spool,
      BankAccount account,
      Instant from,
      Instant to,
      boolean redactHolders,
      String rowsFile) {
    BigDecimal opening = postingRepository.accountBalanceBefore(account.getId(), from);
    BigDecimal inflow = BigDecimal.ZERO;
    BigDecimal outflow = BigDecimal.ZERO;
    Instant afterAt = null;
    UUID afterId = null;
    long count = 0;
    String anonymised = messageSource.getMessage("general.anonymisedHandle", null, Locale.GERMAN);
    try (var rowsOut = BankReportSpool.writer(spool.file(rowsFile))) {
      while (true) {
        List<BankBookingRow> rows =
            postingRepository.findStatementChunk(
                account.getId(),
                from,
                to,
                afterAt,
                afterId,
                Pageable.ofSize(BankReportSpool.CHUNK_SIZE));
        if (rows.isEmpty()) {
          break;
        }
        List<UUID> holderTransactions =
            redactHolders
                ? List.of()
                : rows.stream()
                    .filter(row -> row.type() != BankTransactionType.WIPE_RESET)
                    .map(BankBookingRow::transactionId)
                    .distinct()
                    .toList();
        Map<UUID, List<BankHolderLeg>> holders =
            holderTransactions.isEmpty()
                ? Map.of()
                : holderRepository.findHolderLegsByTransactionIds(holderTransactions).stream()
                    .collect(Collectors.groupingBy(BankHolderLeg::transactionId));
        List<UUID> transfers =
            rows.stream()
                .filter(row -> row.type() == BankTransactionType.TRANSFER)
                .map(BankBookingRow::transactionId)
                .distinct()
                .toList();
        Map<UUID, List<BankCounterLeg>> counters =
            transfers.isEmpty()
                ? Map.of()
                : postingRepository.findLegsByTransactionIds(transfers).stream()
                    .collect(Collectors.groupingBy(BankCounterLeg::transactionId));
        List<BankReportSpool.Line> lines = new ArrayList<>(rows.size());
        for (BankBookingRow row : rows) {
          String holder =
              holders.getOrDefault(row.transactionId(), List.of()).stream()
                  .filter(leg -> leg.amount().signum() == row.amount().signum())
                  .map(BankHolderLeg::handle)
                  .findFirst()
                  .orElse("");
          holder = HandleAnonymisation.humaniseRequired(holder, anonymised);
          lines.add(
              new BankReportSpool.Line(
                  row.createdAt(),
                  row.type(),
                  row.amount(),
                  row.note(),
                  row.justification(),
                  redactHolders ? null : row.staffNote(),
                  holder,
                  BankPdfFormat.counterpartyCell(row, counters, anonymised)));
          if (row.amount().signum() > 0) {
            inflow = inflow.add(row.amount());
          } else {
            outflow = outflow.add(row.amount());
          }
        }
        BankReportSpool.append(rowsOut, mapper, new BankReportSpool.Lines(lines));
        count += rows.size();
        BankBookingRow last = rows.getLast();
        afterAt = last.createdAt();
        afterId = last.postingId();
        if (rows.size() < BankReportSpool.CHUNK_SIZE) {
          break;
        }
      }
    } catch (IOException failure) {
      throw new ReportGenerationException("Bank statement snapshot write failed", failure);
    }
    return new BankReportSpool.Section(
        new BankReportSpool.Account(account.getId(), account.getAccountNo(), account.getName()),
        rowsFile,
        opening,
        inflow,
        outflow,
        count);
  }

  /**
   * Counts of the bounded account and holder snapshots.
   *
   * @param accounts captured account sections
   * @param holders captured holder balance rows
   */
  public record Management(long accounts, long holders) {}
}
