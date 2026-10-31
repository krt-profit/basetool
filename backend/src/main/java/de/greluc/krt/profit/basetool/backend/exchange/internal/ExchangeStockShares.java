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

import static de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeOps.round;

import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeStockChangeSet;
import de.greluc.krt.profit.basetool.backend.inventory.api.RebookCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRow;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves a member's stock of a lot between personal and a unit's pool for a client's {@code share}
 * and {@code unshare}, and back for an undo, through the Lager's rebooking (REQ-XCH-016, ADR-0278).
 * The caller holds the member's lot lock.
 */
@Service
@RequiredArgsConstructor
public class ExchangeStockShares {

  /** The refusal of a share into a unit the member is not a direct member of. */
  static final String NOT_A_MEMBER = "NOT_A_MEMBER";

  /** The refusal of a rebooking of more than the amount the client last saw at its source. */
  static final String QUANTITY_ABOVE_EXPECTED = "QUANTITY_ABOVE_EXPECTED";

  /** The refusal of a rebooking of more stock than the member's rows at its source hold. */
  static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";

  /** The refusal of a rebooking that would leave a row below its Materialbörse offer. */
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
    return sourceRefusal(personal, expected, amount);
  }

  /**
   * Compares a rebooking's source with what the client last saw.
   *
   * @param source the source's amount as the earlier ops of the batch leave it, or {@code null}
   *     when an earlier op moved it in a way the batch cannot follow
   * @param expected the amount the client last saw, rounded
   * @param amount the amount to move, rounded
   * @return the refusal, or {@code null} when the Lager decides the rest
   */
  private static @Nullable String sourceRefusal(
      @Nullable BigDecimal source, @NotNull BigDecimal expected, @NotNull BigDecimal amount) {
    if (source == null || source.compareTo(expected) != 0) {
      return ExchangeStockWriteService.VERSION_CONFLICT;
    }
    if (amount.compareTo(expected) > 0) {
      return QUANTITY_ABOVE_EXPECTED;
    }
    return null;
  }

  /**
   * Runs a rebooking through the Lager and journals it, or only asks the Lager in a dry run.
   *
   * @param caller the client, installation and member
   * @param batch the change set's id
   * @param move the share or unshare
   * @param dryRun whether to write nothing
   * @return the Lager's refusal as a per-op reason, or {@code null} when the move ran or would run
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
                move.direction(),
                move.amount().doubleValue()),
            dryRun);
    RebookCommands.RebookRefusal refusal = outcome.refusal();
    if (refusal != null) {
      return reason(refusal);
    }
    if (!dryRun) {
      boolean share = move.direction() == RebookCommands.RebookDirection.TO_POOL;
      journalService.record(
          caller,
          batch,
          share ? ExchangeJournalAction.STOCK_SHARE : ExchangeJournalAction.STOCK_UNSHARE,
          move.key(),
          false,
          ExchangeStockJournal.State.of(move.total(), move.unit()).toJournal(),
          ExchangeStockJournal.State.moved(
                  move.total(), move.unit(), move.orgUnitId(), share, move.amount())
              .toJournal());
    }
    return null;
  }

  /**
   * Sets a lot's pools back for an undo, all or nothing: a positive amount moves the pool's stock
   * back to personal, a negative one moves personal stock back into the pool.
   *
   * @param member the member
   * @param lot the lot
   * @param pools the net amount the client moved into each pool
   * @return whether every pool could be set back; {@code false} writes nothing
   * @throws IllegalStateException when the Lager refuses a move it accepted in the dry run
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean takeBack(
      @NotNull UUID member, @NotNull StockLot lot, @NotNull Map<UUID, BigDecimal> pools) {
    List<RebookCommands.Rebook> toPersonal = new ArrayList<>();
    List<RebookCommands.Rebook> toPool = new ArrayList<>();
    pools.forEach((pool, amount) -> {
      if (amount.signum() > 0) {
        toPersonal.add(
            new RebookCommands.Rebook(
                member,
                lot,
                pool,
                RebookCommands.RebookDirection.TO_PERSONAL,
                amount.doubleValue()));
      } else if (amount.signum() < 0) {
        toPool.add(
            new RebookCommands.Rebook(
                member,
                lot,
                pool,
                RebookCommands.RebookDirection.TO_POOL,
                amount.negate().doubleValue()));
      }
    });
    List<RebookCommands.Rebook> rebooks = new ArrayList<>(toPersonal);
    rebooks.addAll(toPool);
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
   * Maps a Lager refusal of a rebooking to its per-op reason.
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
   * Whether an op moves stock between personal and a pool.
   *
   * @param op the op
   * @return {@code true} for a {@code share} or an {@code unshare}
   */
  static boolean isRebooking(@NotNull ExchangeStockChangeSet.Op op) {
    return ExchangeStockChangeSet.SHARE.equals(op.op())
        || ExchangeStockChangeSet.UNSHARE.equals(op.op());
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
   * Picks the rows the Lager counts as the lot's personal stock.
   *
   * @param rows the lot's rows
   * @return the personal rows
   */
  static @NotNull List<StockRow> personalRows(@NotNull List<StockRow> rows) {
    return rows.stream().filter(StockRow::countsAsPersonal).toList();
  }

  /**
   * Sums the member's shared rows of a lot by the unit whose pool they sit in.
   *
   * @param rows the lot's rows
   * @param unit the lot's unit
   * @return the rounded amount in each pool
   */
  private static @NotNull Map<UUID, BigDecimal> pools(
      @NotNull List<StockRow> rows, @NotNull String unit) {
    Map<UUID, BigDecimal> pools = new HashMap<>();
    for (StockRow row : rows) {
      OrgUnit pool = row.getOwningOrgUnit();
      if (!row.countsAsPersonal() && pool != null) {
        pools.merge(pool.getId(), BigDecimal.valueOf(row.getAmount()), BigDecimal::add);
      }
    }
    pools.replaceAll((_, amount) -> round(amount, unit));
    return pools;
  }

  /**
   * One share or unshare of a lot.
   *
   * @param lot the lot
   * @param key the lot's key
   * @param unit the lot's unit
   * @param orgUnitId the unit whose pool receives or gives the stock
   * @param direction whether the stock moves into the pool or back to personal
   * @param amount the amount to move, rounded to the unit
   * @param total the lot's quantity, which the move leaves as it is
   */
  record Move(
      @NotNull StockLot lot,
      @NotNull String key,
      @NotNull String unit,
      @NotNull UUID orgUnitId,
      @NotNull RebookCommands.RebookDirection direction,
      @NotNull BigDecimal amount,
      @NotNull BigDecimal total) {}

  /**
   * How the plan decided a share or unshare.
   *
   * @param move the move, or {@code null} when the op is skipped
   * @param refusal why the op is refused, or {@code null} for a move or an unchanged op
   */
  record Decision(@Nullable Move move, @Nullable String refusal) {}

  /**
   * The personal and pooled amounts of the batch's lots as the earlier ops of the batch leave them.
   */
  @RequiredArgsConstructor
  static final class Ledger {

    /** The units the member directly belongs to. */
    private final @NotNull Set<UUID> memberships;

    private final Map<String, BigDecimal> personals = new HashMap<>();
    private final Map<String, Map<UUID, BigDecimal>> pools = new HashMap<>();
    private final Set<String> unsettled = new HashSet<>();

    /**
     * Decides a share or unshare and books it into the ledger when it may go to the Lager.
     *
     * @param op the op, which names its org unit
     * @param stock the lot
     * @param key the lot's key
     * @param unit the lot's unit
     * @param rows the lot's locked rows
     * @param total the lot's quantity
     * @param expected the amount the client last saw at the source, rounded
     * @param amount the amount to move, rounded
     * @return the decision
     */
    @NotNull
    Decision decide(
        @NotNull ExchangeStockChangeSet.Op op,
        @NotNull StockLot stock,
        @NotNull String key,
        @NotNull String unit,
        @NotNull List<StockRow> rows,
        @NotNull BigDecimal total,
        @NotNull BigDecimal expected,
        @NotNull BigDecimal amount) {
      UUID orgUnitId = Objects.requireNonNull(op.orgUnit()).id();
      boolean share = ExchangeStockChangeSet.SHARE.equals(op.op());
      BigDecimal personal = personal(key, rows, unit);
      Map<UUID, BigDecimal> lotPools = pools.computeIfAbsent(key, _ -> pools(rows, unit));
      BigDecimal pooled = lotPools.getOrDefault(orgUnitId, BigDecimal.ZERO);
      String refusal =
          share
              ? refusal(orgUnitId, memberships, personal, expected, amount)
              : sourceRefusal(unsettled.contains(key) ? null : pooled, expected, amount);
      if (refusal != null || amount.signum() == 0) {
        return new Decision(null, refusal);
      }
      BigDecimal signed = share ? amount : amount.negate();
      personals.put(key, personal.subtract(signed));
      lotPools.put(orgUnitId, pooled.add(signed));
      RebookCommands.RebookDirection direction =
          share
              ? RebookCommands.RebookDirection.TO_POOL
              : RebookCommands.RebookDirection.TO_PERSONAL;
      return new Decision(new Move(stock, key, unit, orgUnitId, direction, amount, total), null);
    }

    /**
     * Books a set-quantity into the ledger: a book-in adds personal stock, a book-out takes
     * personal stock first and leaves the pools unknown once it reaches into them.
     *
     * @param key the lot's key
     * @param unit the lot's unit
     * @param rows the lot's locked rows
     * @param current the lot's quantity before the op
     * @param target the lot's quantity after it
     */
    void changed(
        @NotNull String key,
        @NotNull String unit,
        @NotNull List<StockRow> rows,
        @NotNull BigDecimal current,
        @NotNull BigDecimal target) {
      BigDecimal personal = personal(key, rows, unit);
      BigDecimal delta = target.subtract(current);
      if (delta.signum() > 0) {
        personals.put(key, personal.add(delta));
        return;
      }
      if (delta.negate().compareTo(personal) > 0) {
        unsettled.add(key);
      }
      personals.put(key, personal.subtract(personal.min(delta.negate())));
    }

    /**
     * Returns a lot's personal amount as the earlier ops of the batch leave it.
     *
     * @param key the lot's key
     * @param rows the lot's locked rows
     * @param unit the lot's unit
     * @return the rounded personal amount
     */
    private @NotNull BigDecimal personal(
        @NotNull String key, @NotNull List<StockRow> rows, @NotNull String unit) {
      return personals.computeIfAbsent(
          key, _ -> round(ExchangeStockWriteService.sum(personalRows(rows)), unit));
    }
  }
}
