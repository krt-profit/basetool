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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.OfferedLocations;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RefiningMethodRepository;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeEdit;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeOrder;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeOrigin;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeReader;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeRestore;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeRules;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Sets a refinery order back on an undo (REQ-XCH-056, ADR-0285): a create is removed, an update,
 * goods op, cancel or pickup restores the sections it touched, each only while that section's
 * counter shows no write but the client's since, a pickup's marker is cleared with its status, a
 * pickup that booked stock is left to the member, and a delete recreates the order under its former
 * ids. The journaled state is {@link ExchangeRefineryOrderState}.
 */
@Component
@RequiredArgsConstructor
public class ExchangeRefineryOrderJournal implements ExchangeEntryRestorer {

  private final RefineryExchangeWriter writer;
  private final RefineryExchangeReader reader;
  private final LocationRepository locationRepository;
  private final RefiningMethodRepository refiningMethodRepository;
  private final MaterialRepository materialRepository;
  private final ExchangeLiveSync liveSync;
  private final ObjectMapper objectMapper;

  @Override
  public @NotNull ExchangeResource resource() {
    return ExchangeResource.REFINERY_ORDER;
  }

  /**
   * Sets the order back to its state before the client's first write in the group, section by
   * section: only the sections the client's writes touched, and only while no other write touched
   * them in between or since.
   *
   * @param scope the member and the client
   * @param group the client's writes to the order, newest first
   * @return {@code null} when restored, {@code STOCK_BOOKED} when a pickup of the group booked the
   *     yield into stock, {@code CHANGED_AFTERWARDS} when another write moved a touched section's
   *     counter, {@code GONE} when the member no longer owns the order or its earlier state names a
   *     location, method or material that is no longer there
   */
  @Override
  public @Nullable String restore(
      @NotNull UndoScope scope, @NotNull List<ExchangeJournalEntry> group) {
    ExchangeJournalEntry newest = group.getFirst();
    UUID orderId = ExchangeIds.parse(newest.getEntityKey());
    boolean deleted = newest.getAction() == ExchangeJournalAction.REFINERY_DELETE;
    List<Step> steps = steps(deleted ? group.subList(1, group.size()) : group);
    if (orderId == null || steps == null) {
      return ExchangeUndoService.GONE;
    }
    if (steps.stream().anyMatch(Step::booksStock)) {
      return ExchangeUndoService.STOCK_BOOKED;
    }
    boolean pickedUp =
        group.stream()
            .anyMatch(entry -> entry.getAction() == ExchangeJournalAction.REFINERY_PICKUP);
    if (deleted) {
      return recreate(scope, orderId, read(newest.getBeforeState()), steps, pickedUp);
    }
    Optional<RefineryExchangeOrder> current = writer.lockOwn(scope.member(), orderId);
    if (current.isEmpty()) {
      return ExchangeUndoService.GONE;
    }
    Touched touched = touched(steps);
    if (touched == null
        || (touched.gameData() != null && touched.gameData() != current.get().gameDataVersion())
        || (touched.finances() != null && touched.finances() != current.get().financesVersion())) {
      return ExchangeUndoService.CHANGED_AFTERWARDS;
    }
    ExchangeRefineryOrderState base = steps.getFirst().before();
    if (base == null) {
      writer.delete(scope.member(), orderId);
      return null;
    }
    boolean gameData = touched.gameData() != null;
    if (gameData && !restorable(base, current.get().location().id())) {
      return ExchangeUndoService.GONE;
    }
    writer.edit(
        scope.member(),
        null,
        orderId,
        new RefineryExchangeEdit(
            gameData ? base.location() : null,
            gameData ? new RefineryExchangeEdit.Method(base.method()) : null,
            gameData
                ? new RefineryExchangeEdit.Timer(base.startedAt(), base.durationMinutes())
                : null,
            gameData ? base.status() : null,
            touched.finances() != null
                ? new RefineryExchangeEdit.Finances(
                    base.expenses(), base.otherExpenses(), base.oreSales())
                : null,
            gameData ? base.editGoods() : null));
    if (pickedUp) {
      writer.clearPickup(scope.member(), orderId);
    }
    return null;
  }

  /**
   * Refreshes the refinery pages of the orders an undo set back after the commit, and tells the
   * detail page of every order the undo removed that it is gone (REQ-XCH-030).
   *
   * @param member the member
   * @param orders the orders the undo set back
   */
  void announce(@NotNull UUID member, @NotNull Set<UUID> orders) {
    if (orders.isEmpty()) {
      return;
    }
    Set<UUID> kept =
        reader.orders(member, orders).stream()
            .map(RefineryExchangeOrder::id)
            .collect(Collectors.toSet());
    liveSync.refineryOrdersChanged(
        kept, Set.of(), orders.stream().filter(order -> !kept.contains(order)).toList());
  }

  /**
   * Recreates an order the client deleted under its former ids: the sections the client's earlier
   * writes touched as they were before the first of them, every other part as it was when deleted,
   * with its client marker, the unit while the member still belongs to it directly and the mission
   * while it exists, and without the pickup marker when the client's writes include its pickup. An
   * order the client also created stays gone.
   *
   * @param scope the member and the client
   * @param orderId the order
   * @param removed the order as the delete found it, or {@code null} when it does not read
   * @param steps the client's earlier writes to the order, oldest first
   * @param pickedUp whether the client's writes include the pickup of the order
   * @return {@code null} when recreated or nothing is to recreate, {@code CHANGED_AFTERWARDS} when
   *     another write moved a touched section before the delete, {@code GONE} when the state does
   *     not read or names a location, method or material that is no longer there
   */
  private @Nullable String recreate(
      @NotNull UndoScope scope,
      @NotNull UUID orderId,
      @Nullable ExchangeRefineryOrderState removed,
      @NotNull List<Step> steps,
      boolean pickedUp) {
    RefineryExchangeOrigin origin = removed == null ? null : removed.origin();
    if (removed == null || origin == null) {
      return ExchangeUndoService.GONE;
    }
    if (!steps.isEmpty() && steps.getFirst().before() == null) {
      return null;
    }
    Touched touched = touched(steps);
    if (touched == null
        || (touched.gameData() != null && touched.gameData() != removed.gameData())
        || (touched.finances() != null && touched.finances() != removed.finances())) {
      return ExchangeUndoService.CHANGED_AFTERWARDS;
    }
    ExchangeRefineryOrderState base =
        steps.isEmpty() ? removed : Objects.requireNonNull(steps.getFirst().before());
    ExchangeRefineryOrderState gameData = touched.gameData() != null ? base : removed;
    ExchangeRefineryOrderState finances = touched.finances() != null ? base : removed;
    if (!restorable(gameData, removed.location())) {
      return ExchangeUndoService.GONE;
    }
    UUID unit = origin.orgUnitId();
    writer.restore(
        scope.member(),
        new RefineryExchangeRestore(
            orderId,
            new RefineryExchangeEdit(
                gameData.location(),
                new RefineryExchangeEdit.Method(gameData.method()),
                new RefineryExchangeEdit.Timer(gameData.startedAt(), gameData.durationMinutes()),
                gameData.status(),
                new RefineryExchangeEdit.Finances(
                    finances.expenses(), finances.otherExpenses(), finances.oreSales()),
                gameData.editGoods()),
            removed.gameData() + 1,
            removed.finances() + 1,
            new RefineryExchangeOrigin(
                origin.createdAt(),
                origin.createdClient(),
                unit != null && scope.memberships().get().contains(unit) ? unit : null,
                origin.missionId(),
                pickedUp ? null : origin.pickedUpAt(),
                pickedUp ? null : origin.pickedUpClient(),
                origin.readyNotifiedAt())));
    return null;
  }

  /**
   * Reads the client's writes to the order and puts them in the order they ran. Writes of one
   * change set share their transaction and time, so the journal's order cannot tell them apart; the
   * counters can, because every write raises at least one of them by one.
   *
   * @param group the client's writes to the order
   * @return the writes, oldest first, or {@code null} when one reads as no state
   */
  private @Nullable List<Step> steps(@NotNull List<ExchangeJournalEntry> group) {
    List<Step> steps = new ArrayList<>(group.size());
    for (ExchangeJournalEntry entry : group) {
      ExchangeRefineryOrderState after = read(entry.getAfterState());
      if (after == null) {
        return null;
      }
      steps.add(new Step(read(entry.getBeforeState()), after));
    }
    steps.sort(
        Comparator.comparing((Step step) -> step.before() != null)
            .thenComparingLong(step -> step.after().gameData() + step.after().finances()));
    return steps;
  }

  /**
   * Follows each section's counter through the client's writes, oldest first.
   *
   * @param steps the client's writes, oldest first
   * @return the counter each touched section must still show, {@code null} for an untouched one; or
   *     {@code null} when another write moved a touched section between two of the client's
   */
  private static @Nullable Touched touched(@NotNull List<Step> steps) {
    Long gameData = null;
    Long finances = null;
    for (Step step : steps) {
      ExchangeRefineryOrderState before = step.before();
      ExchangeRefineryOrderState after = step.after();
      if (before == null || before.gameData() != after.gameData()) {
        if (before != null && gameData != null && before.gameData() != gameData) {
          return null;
        }
        gameData = after.gameData();
      }
      if (before == null || before.finances() != after.finances()) {
        if (before != null && finances != null && before.finances() != finances) {
          return null;
        }
        finances = after.finances();
      }
    }
    return new Touched(gameData, finances);
  }

  /**
   * Whether every catalogue entry the earlier game data names can be written back.
   *
   * @param base the earlier state
   * @param currentLocation the location the order has now, or had when deleted
   * @return {@code true} when its location is the current one or an offered refinery, its method
   *     exists and every input material exists and may be refined
   */
  private boolean restorable(
      @NotNull ExchangeRefineryOrderState base, @NotNull UUID currentLocation) {
    Optional<Location> location = locationRepository.findById(base.location());
    boolean placeOk =
        location
            .filter(place ->
                place.getId().equals(currentLocation)
                    || (OfferedLocations.isOffered(place)
                        && RefineryExchangeRules.hostsRefinery(place)))
            .isPresent();
    boolean methodOk = base.method() == null || refiningMethodRepository.existsById(base.method());
    boolean goodsOk =
        base.goods().stream()
            .allMatch(good ->
                materialRepository
                    .findById(good.input())
                    .filter(RefineryExchangeRules::refinable)
                    .isPresent());
    return placeOk && methodOk && goodsOk;
  }

  /**
   * Reads a journaled order state.
   *
   * @param json the state as the journal stores it, or {@code null}
   * @return the state, or {@code null} for none
   */
  private @Nullable ExchangeRefineryOrderState read(@Nullable String json) {
    return json == null ? null : ExchangeRefineryOrderState.read(objectMapper.readTree(json));
  }

  /**
   * One journaled write of the order.
   *
   * @param before the order before it, or {@code null} for its create
   * @param after the order after it
   */
  private record Step(
      @Nullable ExchangeRefineryOrderState before, @NotNull ExchangeRefineryOrderState after) {

    /**
     * Whether the write booked the order's yield into stock, as a pickup with store lines does.
     *
     * @return {@code true} when the order is stored after it and was not before
     */
    boolean booksStock() {
      return after.stored() && (before == null || !before.stored());
    }
  }

  /**
   * The counters the client's last writes left on the sections they touched.
   *
   * @param gameData the game-data counter, or {@code null} when no write touched game data
   * @param finances the finances counter, or {@code null} when no write touched the finances
   */
  private record Touched(@Nullable Long gameData, @Nullable Long finances) {}
}
