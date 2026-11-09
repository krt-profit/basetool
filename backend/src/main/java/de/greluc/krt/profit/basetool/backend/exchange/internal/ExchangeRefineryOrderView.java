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

import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeEdit;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeOrder;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeRules;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderStatus;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * What a refinery-order op is decided against: the order as the earlier ops of the change set left
 * it. A real run reads it from every order the refinery module returns; a dry run derives it from
 * each op by the {@link de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeWriter}
 * contract, so both decide every op alike (REQ-XCH-052).
 *
 * @param status the status
 * @param stored whether the order's yield is booked into stock
 * @param gameDataVersion the game-data counter
 * @param financesVersion the finances counter
 * @param locationId the refinery's location
 * @param methodId the refining method, or {@code null}
 * @param startedAt the run's start, or {@code null}
 * @param durationMinutes the run's length, or {@code null}
 * @param expenses the refining costs as stored, or {@code null}
 * @param otherExpenses further costs as stored, or {@code null}
 * @param oreSales the raw-ore revenue as stored, or {@code null}
 * @param goods the goods
 */
record ExchangeRefineryOrderView(
    @NotNull RefineryOrderStatus status,
    boolean stored,
    long gameDataVersion,
    long financesVersion,
    @NotNull UUID locationId,
    @Nullable UUID methodId,
    @Nullable Instant startedAt,
    @Nullable Long durationMinutes,
    @Nullable Double expenses,
    @Nullable Double otherExpenses,
    @Nullable Double oreSales,
    @NotNull @Unmodifiable List<Good> goods) {

  /**
   * Reads an order as the refinery module returns it.
   *
   * @param order the order
   * @return its view
   */
  static @NotNull ExchangeRefineryOrderView of(@NotNull RefineryExchangeOrder order) {
    return new ExchangeRefineryOrderView(
        order.status(),
        order.storedAt() != null,
        order.gameDataVersion(),
        order.financesVersion(),
        order.location().id(),
        order.method() == null ? null : order.method().id(),
        order.startedAt(),
        order.durationMinutes(),
        order.expenses(),
        order.otherExpenses(),
        order.oreSales(),
        order.goods().stream()
            .map(good ->
                new Good(
                    good.id(),
                    good.inputMaterialId(),
                    good.outputMaterialId(),
                    good.inputQuantity(),
                    good.outputQuantity(),
                    good.quality()))
            .toList());
  }

  /**
   * The order a create writes: counters at 0, the run started at its timer or at the write.
   *
   * @param edit the create's parts, with location and status
   * @param outputs the output material of each input the create names
   * @param now the time of the write
   * @return the view of the created order
   */
  static @NotNull ExchangeRefineryOrderView created(
      @NotNull RefineryExchangeEdit edit, @NotNull Map<UUID, UUID> outputs, @NotNull Instant now) {
    RefineryExchangeEdit.Timer timer = edit.timer();
    RefineryExchangeEdit.Finances finances = edit.finances();
    return new ExchangeRefineryOrderView(
        Objects.requireNonNull(edit.status(), "status"),
        false,
        0L,
        0L,
        Objects.requireNonNull(edit.locationId(), "locationId"),
        edit.method() == null ? null : edit.method().id(),
        timer != null && timer.startedAt() != null ? timer.startedAt() : now,
        timer == null ? null : timer.durationMinutes(),
        finances == null ? null : RefineryExchangeRules.money(finances.expenses()),
        finances == null ? null : RefineryExchangeRules.money(finances.otherExpenses()),
        finances == null ? null : RefineryExchangeRules.money(finances.oreSales()),
        edit.goods() == null ? List.of() : goods(edit.goods(), outputs));
  }

  /**
   * The order after an edit: each section it writes set and its counter raised by one.
   *
   * @param edit the edit
   * @param outputs the output material of each input the edit newly names
   * @return the view after the edit
   */
  @NotNull
  ExchangeRefineryOrderView edited(
      @NotNull RefineryExchangeEdit edit, @NotNull Map<UUID, UUID> outputs) {
    boolean gameData = edit.touchesGameData();
    RefineryExchangeEdit.Timer timer = edit.timer();
    RefineryExchangeEdit.Finances finances = edit.finances();
    Map<UUID, UUID> known = new HashMap<>(outputs);
    goods.forEach(good -> known.putIfAbsent(good.input(), good.output()));
    return new ExchangeRefineryOrderView(
        edit.status() == null ? status : edit.status(),
        stored,
        gameDataVersion + (gameData ? 1 : 0),
        financesVersion + (finances == null ? 0 : 1),
        edit.locationId() == null ? locationId : edit.locationId(),
        edit.method() == null ? methodId : edit.method().id(),
        timer == null ? startedAt : timer.startedAt(),
        timer == null ? durationMinutes : timer.durationMinutes(),
        finances == null ? expenses : RefineryExchangeRules.money(finances.expenses()),
        finances == null ? otherExpenses : RefineryExchangeRules.money(finances.otherExpenses()),
        finances == null ? oreSales : RefineryExchangeRules.money(finances.oreSales()),
        edit.goods() == null ? goods : goods(edit.goods(), known));
  }

  /**
   * The order after a cancel.
   *
   * @return the view, cancelled with the game-data counter raised by one
   */
  @NotNull
  ExchangeRefineryOrderView canceled() {
    return closed(RefineryOrderStatus.CANCELED, stored);
  }

  /**
   * The order after a pickup.
   *
   * @param booksStock whether the pickup carries store lines
   * @return the view, completed with the game-data counter raised by one, stored when it booked
   */
  @NotNull
  ExchangeRefineryOrderView pickedUp(boolean booksStock) {
    return closed(RefineryOrderStatus.COMPLETED, stored || booksStock);
  }

  /**
   * Whether an update would leave the order as it is.
   *
   * @param edit the update
   * @return {@code true} when every part it carries equals the order's
   */
  boolean unchangedBy(@NotNull RefineryExchangeEdit edit) {
    RefineryExchangeEdit.Finances finances = edit.finances();
    RefineryExchangeEdit.Timer timer = edit.timer();
    return (edit.locationId() == null || edit.locationId().equals(locationId))
        && (edit.method() == null || Objects.equals(edit.method().id(), methodId))
        && (edit.status() == null || edit.status() == status)
        && (timer == null
            || (Objects.equals(timer.startedAt(), startedAt)
                && Objects.equals(timer.durationMinutes(), durationMinutes)))
        && (finances == null
            || (Objects.equals(RefineryExchangeRules.money(finances.expenses()), expenses)
                && Objects.equals(
                    RefineryExchangeRules.money(finances.otherExpenses()), otherExpenses)
                && Objects.equals(RefineryExchangeRules.money(finances.oreSales()), oreSales)));
  }

  /**
   * The goods as the rows an edit writes back.
   *
   * @return every good with its row id
   */
  @NotNull
  @Unmodifiable
  List<RefineryExchangeEdit.Good> editGoods() {
    return goods.stream().map(Good::toEdit).toList();
  }

  /**
   * The output materials of the goods.
   *
   * @return the materials a store line may book
   */
  @NotNull
  @Unmodifiable
  Set<UUID> outputs() {
    return goods.stream().map(Good::output).collect(Collectors.toUnmodifiableSet());
  }

  /**
   * The order closed with a status, the game-data counter raised by one.
   *
   * @param closedAs the status
   * @param storedAfter whether the yield is booked afterwards
   * @return the view
   */
  private @NotNull ExchangeRefineryOrderView closed(
      @NotNull RefineryOrderStatus closedAs, boolean storedAfter) {
    return new ExchangeRefineryOrderView(
        closedAs,
        storedAfter,
        gameDataVersion + 1,
        financesVersion,
        locationId,
        methodId,
        startedAt,
        durationMinutes,
        expenses,
        otherExpenses,
        oreSales,
        goods);
  }

  /**
   * The goods an edit sets.
   *
   * @param goods the edit's goods
   * @param outputs the output material of each input
   * @return the goods with their outputs
   */
  private static @NotNull @Unmodifiable List<Good> goods(
      @NotNull List<RefineryExchangeEdit.Good> goods, @NotNull Map<UUID, UUID> outputs) {
    return goods.stream()
        .map(good ->
            new Good(
                good.id(),
                good.inputMaterialId(),
                Objects.requireNonNull(outputs.get(good.inputMaterialId()), "output"),
                good.inputQuantity(),
                good.outputQuantity(),
                good.quality()))
        .toList();
  }

  /**
   * One good.
   *
   * @param id the row id, or {@code null} for one a dry run adds
   * @param input the input material
   * @param output the output material
   * @param inputQuantity the input amount in units
   * @param outputQuantity the yield in units
   * @param quality the quality
   */
  record Good(
      @Nullable UUID id,
      @NotNull UUID input,
      @NotNull UUID output,
      int inputQuantity,
      int outputQuantity,
      int quality) {

    /**
     * The good as the row an edit writes back.
     *
     * @return the row
     */
    @NotNull
    RefineryExchangeEdit.Good toEdit() {
      return new RefineryExchangeEdit.Good(id, input, inputQuantity, outputQuantity, quality);
    }
  }
}
