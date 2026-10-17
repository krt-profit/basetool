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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeStockChangeSet;
import de.greluc.krt.profit.basetool.backend.inventory.api.RebookCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves a member's personal stock of a lot into a unit's pool for a client's {@code share}, and
 * back for an undo, through the Lager's rebooking (REQ-XCH-016, ADR-0278). The caller holds the
 * member's lot lock.
 */
@Service
@RequiredArgsConstructor
public class ExchangeStockShares {

  /** The refusal of a share into a unit the member is not a direct member of. */
  static final String NOT_A_MEMBER = "NOT_A_MEMBER";

  /** The refusal of a share of more than the personal amount the client last saw. */
  static final String QUANTITY_ABOVE_EXPECTED = "QUANTITY_ABOVE_EXPECTED";

  /** The refusal of a share of more personal stock than the member's personal rows hold. */
  static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";

  /** The refusal of a share that would leave a row below its Materialbörse offer. */
  static final String STOCK_OFFERED = "STOCK_OFFERED";

  private final RebookCommands rebookCommands;
  private final ExchangeJournalService journalService;

  /**
   * Decides a share before the Lager sees it.
   *
   * @param orgUnitId the unit the share names
   * @param memberships the units the member directly belongs to
   * @param personal the lot's personal amount as the earlier ops of the batch leave it
   * @param expected the personal amount the client last saw, rounded
   * @param amount the amount to move, rounded
   * @return the refusal, or {@code null} when the Lager decides the rest
   */
  static @Nullable String refusal(
      @NotNull UUID orgUnitId,
      @NotNull Set<UUID> memberships,
      @NotNull BigDecimal personal,
      @NotNull BigDecimal expected,
      @NotNull BigDecimal amount) {
    if (!memberships.contains(orgUnitId)) {
      return NOT_A_MEMBER;
    }
    if (personal.compareTo(expected) != 0) {
      return ExchangeStockWriteService.VERSION_CONFLICT;
    }
    if (amount.compareTo(expected) > 0) {
      return QUANTITY_ABOVE_EXPECTED;
    }
    return null;
  }

  /**
   * Runs a share through the Lager's rebooking and journals it, or only asks the Lager in a dry
   * run.
   *
   * @param caller the client, installation and member
   * @param batch the change set's id
   * @param move the share
   * @param dryRun whether to write nothing
   * @return the Lager's refusal as a per-op reason, or {@code null} when the share ran or would run
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public @Nullable String run(
      @NotNull ExchangeCaller caller, @NotNull UUID batch, @NotNull Move move, boolean dryRun) {
    RebookCommands.RebookOutcome outcome =
        rebookCommands.rebook(
            new RebookCommands.Rebook(
                caller.member(),
                move.lot(),
                move.orgUnitId(),
                RebookCommands.RebookDirection.TO_POOL,
                move.amount().doubleValue()),
            dryRun);
    RebookCommands.RebookRefusal refusal = outcome.refusal();
    if (refusal != null) {
      return reason(refusal);
    }
    if (!dryRun) {
      Map<String, Object> after = ExchangeStockWriteService.state(move.total(), move.unit());
      after.put("orgUnit", move.orgUnitId().toString());
      after.put("shared", move.amount());
      journalService.record(
          caller,
          batch,
          ExchangeJournalAction.STOCK_SHARE,
          move.key(),
          false,
          ExchangeStockWriteService.state(move.total(), move.unit()),
          after);
    }
    return null;
  }

  /**
   * Moves a lot's shared stock in the named pools back to personal for an undo; all or nothing.
   *
   * @param member the member
   * @param lot the lot
   * @param pools the amount to take back from each pool
   * @return whether every pool gave its amount back; {@code false} writes nothing
   * @throws IllegalStateException when the Lager refuses a move it accepted in the dry run
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean takeBack(
      @NotNull UUID member, @NotNull StockLot lot, @NotNull Map<UUID, BigDecimal> pools) {
    List<RebookCommands.Rebook> rebooks = new ArrayList<>();
    pools.forEach(
        (pool, amount) ->
            rebooks.add(
                new RebookCommands.Rebook(
                    member,
                    lot,
                    pool,
                    RebookCommands.RebookDirection.TO_PERSONAL,
                    amount.doubleValue())));
    for (RebookCommands.Rebook rebook : rebooks) {
      if (rebookCommands.rebook(rebook, true).refusal() != null) {
        return false;
      }
    }
    for (RebookCommands.Rebook rebook : rebooks) {
      if (rebookCommands.rebook(rebook, false).refusal() != null) {
        throw new IllegalStateException("The Lager refused a take-back it had accepted");
      }
    }
    return true;
  }

  /**
   * Maps a Lager refusal of a share to its per-op reason.
   *
   * @param refusal the refusal
   * @return the reason code
   */
  private static @NotNull String reason(@NotNull RebookCommands.RebookRefusal refusal) {
    return switch (refusal) {
      case NOT_A_MEMBER -> NOT_A_MEMBER;
      case UNIT_MISMATCH -> ExchangeStockWriteService.UNIT_MISMATCH;
      case INSUFFICIENT_STOCK -> INSUFFICIENT_STOCK;
      case STOCK_EARMARKED -> ExchangeStockWriteService.STOCK_EARMARKED;
      case STOCK_OFFERED -> STOCK_OFFERED;
    };
  }

  /**
   * Whether an amount is whole where its unit counts pieces.
   *
   * @param quantity the amount
   * @param unit the lot's unit
   * @return {@code false} for a fraction of a piece
   */
  static boolean isWhole(@NotNull ExchangeStockChangeSet.Quantity quantity, @NotNull String unit) {
    return !"PIECE".equals(unit) || quantity.amount().stripTrailingZeros().scale() <= 0;
  }

  /**
   * Picks the rows that count as the lot's personal stock: the personal rows and shared rows
   * outside any unit.
   *
   * @param rows the lot's rows
   * @return the personal rows
   */
  static @NotNull List<StockRow> personalRows(@NotNull List<StockRow> rows) {
    return rows.stream()
        .filter(row -> Boolean.TRUE.equals(row.getPersonal()) || row.getOwningOrgUnit() == null)
        .toList();
  }

  /**
   * One share of a lot.
   *
   * @param lot the lot
   * @param key the lot's key
   * @param unit the lot's unit
   * @param orgUnitId the unit whose pool receives the stock
   * @param amount the amount to move, rounded to the unit
   * @param total the lot's quantity, which the share leaves as it is
   */
  record Move(
      @NotNull StockLot lot,
      @NotNull String key,
      @NotNull String unit,
      @NotNull UUID orgUnitId,
      @NotNull BigDecimal amount,
      @NotNull BigDecimal total) {}
}
