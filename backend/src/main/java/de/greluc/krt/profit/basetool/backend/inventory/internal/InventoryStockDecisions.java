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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.inventory.api.RebookCommands.RebookRefusal;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The Lager's stock rules per operation, each a pure function over loaded rows that returns a
 * refusal value instead of throwing (REQ-INV-065, ADR-0284). The web services and the published
 * commands are adapters that map the refusal to their own answer.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class InventoryStockDecisions {

  /** The tolerance of an amount comparison, which only absorbs floating-point noise. */
  private static final double EPSILON = AllocationReductions.REDUCTION_EPSILON;

  /** Why an amount cannot be booked out of a row. */
  enum BookOutRefusal {
    /** The amount is more than the row holds. */
    EXCEEDS_STOCK,
    /** The amount is fractional on whole-unit stock and is not the whole row. */
    FRACTIONAL_AMOUNT,
    /** The book-out deducts from mission earmarks of game-item stock, which carries none. */
    MISSION_EARMARK_ON_ITEM_STOCK
  }

  /** Why a row cannot be rebooked between personal and shared. */
  enum PersonalRebookRefusal {
    /** The amount is missing or not positive. */
    AMOUNT_NOT_POSITIVE,
    /** The amount is more than the row holds. */
    EXCEEDS_STOCK,
    /** The amount is fractional on whole-unit stock and is not the whole row. */
    FRACTIONAL_AMOUNT,
    /** A shared row earmarked to a job order or mission would become personal. */
    EARMARKED
  }

  /** Why a part cannot be split off a row into a new row. */
  enum SplitRefusal {
    /** The part is not positive. */
    AMOUNT_NOT_POSITIVE,
    /** The part is more than the row holds. */
    EXCEEDS_STOCK,
    /** The part is fractional on whole-unit stock. */
    FRACTIONAL_AMOUNT,
    /** The rest would fall below what the row offers on the Materialbörse. */
    BELOW_OFFER,
    /** The rest could no longer carry the row's earmarks or donation pledges. */
    EARMARKS_EXCEED_REST
  }

  /**
   * The decision on a lot rebooking: a refusal, or the amount to take from each source row.
   *
   * @param refusal the refusal, or {@code null} when the rebooking may happen
   * @param takes the rows and amounts to move in order, empty on a refusal
   */
  record LotRebook(@Nullable RebookRefusal refusal, @NotNull @Unmodifiable List<Take> takes) {

    /**
     * Copies the takes.
     *
     * @param refusal the refusal, or {@code null}
     * @param takes the takes
     */
    LotRebook {
      takes = List.copyOf(takes);
    }
  }

  /**
   * One source row of a lot rebooking and the amount it gives.
   *
   * @param row the source row
   * @param amount the amount to move off it, positive
   */
  record Take(@NotNull InventoryItem row, double amount) {}

  /**
   * Decides a book-out, sale or transfer of an amount of a row.
   *
   * @param row the loaded row
   * @param amount the amount to take out
   * @param fromMissions whether the book-out names mission earmarks to deduct from
   * @return the refusal, or {@code null} when the amount may be taken
   */
  static @Nullable BookOutRefusal bookOut(
      @NotNull InventoryItem row, double amount, boolean fromMissions) {
    if (amount > amountOf(row)) {
      return BookOutRefusal.EXCEEDS_STOCK;
    }
    if (fractionalPart(row, amount)) {
      return BookOutRefusal.FRACTIONAL_AMOUNT;
    }
    if (row.getGameItem() != null && fromMissions) {
      return BookOutRefusal.MISSION_EARMARK_ON_ITEM_STOCK;
    }
    return null;
  }

  /**
   * Decides the Umbuchung of an amount of a row to the other side of its personal marker.
   *
   * @param row the loaded row
   * @param amount the amount to move, or {@code null} when none was given
   * @return the refusal, or {@code null} when the amount may move
   */
  static @Nullable PersonalRebookRefusal rebookPersonal(
      @NotNull InventoryItem row, @Nullable Double amount) {
    if (amount == null || amount <= 0) {
      return PersonalRebookRefusal.AMOUNT_NOT_POSITIVE;
    }
    if (amount > amountOf(row)) {
      return PersonalRebookRefusal.EXCEEDS_STOCK;
    }
    if (fractionalPart(row, amount)) {
      return PersonalRebookRefusal.FRACTIONAL_AMOUNT;
    }
    if (!Boolean.TRUE.equals(row.getPersonal()) && !mayBecomePersonal(row)) {
      return PersonalRebookRefusal.EARMARKED;
    }
    return null;
  }

  /**
   * Tells whether stock of a row may become personal: only while it carries no job-order or mission
   * earmark.
   *
   * @param row the loaded row
   * @return {@code true} when no job-order or mission earmark holds the row
   */
  private static boolean mayBecomePersonal(@NotNull InventoryItem row) {
    return row.getJobOrderAllocations().isEmpty() && row.getMissionAllocations().isEmpty();
  }

  /**
   * Decides splitting a part off a row into a new row whose rest keeps the row's earmarks, pledges
   * and Materialbörse offer.
   *
   * @param row the loaded row
   * @param amount the part to split off
   * @param offered the amount the row offers on the Materialbörse
   * @return the refusal, or {@code null} when the part may be split off
   */
  static @Nullable SplitRefusal split(@NotNull InventoryItem row, double amount, double offered) {
    double held = amountOf(row);
    if (amount <= 0) {
      return SplitRefusal.AMOUNT_NOT_POSITIVE;
    }
    if (amount > held) {
      return SplitRefusal.EXCEEDS_STOCK;
    }
    if (row.countsWholeUnits() && amount % 1 != 0) {
      return SplitRefusal.FRACTIONAL_AMOUNT;
    }
    double rest = ScuAmounts.roundToScuScale(held - amount);
    if (rest + EPSILON < offered) {
      return SplitRefusal.BELOW_OFFER;
    }
    if (InventoryAllocations.sumJobOrder(row) > rest + EPSILON
        || InventoryAllocations.sumMission(row) > rest + EPSILON
        || InventoryAllocations.sumDonation(row) > rest + EPSILON) {
      return SplitRefusal.EARMARKS_EXCEED_REST;
    }
    return null;
  }

  /**
   * Decides a rebooking of a lot between personal and one pool: enough stock at the sources, then
   * enough of it free of earmarks, then enough above the Materialbörse offers; then takes from the
   * sources in their order.
   *
   * @param sources the rows the rebooking may take from, in the order it takes them
   * @param offered the amount each source offers on the Materialbörse, by row id
   * @param amount the amount to move
   * @param toPool whether the stock moves into the pool
   * @param wholeUnits whether the lot counts whole units
   * @return the refusal, or the amount to take from each source
   */
  static @NotNull LotRebook rebookLot(
      @NotNull List<InventoryItem> sources,
      @NotNull Map<UUID, Double> offered,
      double amount,
      boolean toPool,
      boolean wholeUnits) {
    double held = 0.0;
    double unreserved = 0.0;
    double movable = 0.0;
    for (InventoryItem source : sources) {
      held += amountOf(source);
      unreserved += unreserved(source, toPool, wholeUnits);
      movable += movable(source, offeredOf(offered, source), toPool, wholeUnits);
    }
    if (amount > held + EPSILON) {
      return new LotRebook(RebookRefusal.INSUFFICIENT_STOCK, List.of());
    }
    if (amount > unreserved + EPSILON) {
      return new LotRebook(RebookRefusal.STOCK_EARMARKED, List.of());
    }
    if (amount > movable + EPSILON) {
      return new LotRebook(RebookRefusal.STOCK_OFFERED, List.of());
    }
    List<Take> takes = new ArrayList<>();
    double left = amount;
    for (InventoryItem source : sources) {
      if (left <= EPSILON) {
        break;
      }
      double take = Math.min(left, movable(source, offeredOf(offered, source), toPool, wholeUnits));
      if (take > EPSILON) {
        takes.add(new Take(source, take));
        left = ScuAmounts.roundToScuScale(left - take);
      }
    }
    return new LotRebook(null, takes);
  }

  /**
   * Returns the part of a row no earmark holds. A row earmarked to a job order or mission cannot
   * become personal at all.
   *
   * @param row the row
   * @param toPool whether the stock moves into the pool
   * @param wholeUnits whether the lot counts whole units
   * @return the unreserved amount, never negative
   */
  private static double unreserved(@NotNull InventoryItem row, boolean toPool, boolean wholeUnits) {
    double reserved;
    if (toPool) {
      reserved =
          Math.max(Math.max(row.jobOrderReserved(), row.missionReserved()), row.donationReserved());
    } else if (!mayBecomePersonal(row)) {
      return 0.0;
    } else {
      reserved = row.donationReserved();
    }
    return free(amountOf(row), reserved, wholeUnits);
  }

  /**
   * Returns the part of a row a rebooking may take: unreserved and above its Materialbörse offer.
   *
   * @param row the row
   * @param offered the amount the row offers on the Materialbörse
   * @param toPool whether the stock moves into the pool
   * @param wholeUnits whether the lot counts whole units
   * @return the movable amount, never negative
   */
  private static double movable(
      @NotNull InventoryItem row, double offered, boolean toPool, boolean wholeUnits) {
    return Math.min(unreserved(row, toPool, wholeUnits), free(amountOf(row), offered, wholeUnits));
  }

  /**
   * Returns what is left of an amount above a held part, in whole units where the lot counts them.
   *
   * @param amount the row's amount
   * @param kept the part that must stay
   * @param wholeUnits whether the lot counts whole units
   * @return the free amount, never negative
   */
  private static double free(double amount, double kept, boolean wholeUnits) {
    double free = Math.max(0.0, ScuAmounts.roundToScuScale(amount - kept));
    return wholeUnits ? Math.floor(free + EPSILON) : free;
  }

  /**
   * Returns a source's Materialbörse offer.
   *
   * @param offered the offers by row id
   * @param row the row
   * @return the offered amount, {@code 0} when it offers none
   */
  private static double offeredOf(@NotNull Map<UUID, Double> offered, @NotNull InventoryItem row) {
    return offered.getOrDefault(row.getId(), 0.0);
  }

  /**
   * Tells whether an amount is a fractional part of whole-unit stock; the whole row may move even
   * when its own amount is fractional.
   *
   * @param row the row
   * @param amount the amount
   * @return {@code true} when the amount is refused as fractional
   */
  private static boolean fractionalPart(@NotNull InventoryItem row, double amount) {
    return row.countsWholeUnits() && amount % 1 != 0 && Double.compare(amount, amountOf(row)) != 0;
  }

  /**
   * Returns a row's amount.
   *
   * @param row the row
   * @return the amount, {@code 0} when unset
   */
  static double amountOf(@NotNull InventoryItem row) {
    return row.getAmount() == null ? 0.0 : row.getAmount();
  }
}
