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
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeRules;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns the journal state of a refinery order and sets the order back on an undo (REQ-XCH-056,
 * ADR-0285): a create is removed, an update, goods op or pickup restores the sections it touched,
 * each only while that section's counter shows no write but the client's since; a pickup's marker
 * is cleared with its status.
 */
@Component
@RequiredArgsConstructor
public class ExchangeRefineryOrderJournal implements ExchangeEntryRestorer {

  private final RefineryExchangeWriter writer;
  private final LocationRepository locationRepository;
  private final RefiningMethodRepository refiningMethodRepository;
  private final MaterialRepository materialRepository;
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
   * @return {@code null} when restored, {@code CHANGED_AFTERWARDS} when another write moved a
   *     touched section's counter, {@code GONE} when the member no longer owns the order or its
   *     earlier state names a location, method or material that is no longer there
   */
  @Override
  public @Nullable String restore(
      @NotNull UndoScope scope, @NotNull List<ExchangeJournalEntry> group) {
    UUID orderId = parse(group.getFirst().getEntityKey());
    List<Step> steps = steps(group);
    if (orderId == null || steps == null) {
      return ExchangeUndoService.GONE;
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
    State base = steps.getFirst().before();
    if (base == null) {
      writer.delete(scope.member(), orderId);
      return null;
    }
    boolean gameData = touched.gameData() != null;
    if (gameData && !restorable(base, current.get())) {
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
            gameData ? base.goods().stream().map(Good::toEdit).toList() : null));
    if (group.stream()
        .anyMatch(entry -> entry.getAction() == ExchangeJournalAction.REFINERY_PICKUP)) {
      writer.clearPickup(scope.member(), orderId);
    }
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
      State after = read(entry.getAfterState());
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
      State before = step.before();
      State after = step.after();
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
   * @param current the order now
   * @return {@code true} when its location is the current one or an offered refinery, its method
   *     exists and every input material exists and may be refined
   */
  private boolean restorable(@NotNull State base, @NotNull RefineryExchangeOrder current) {
    Optional<Location> location = locationRepository.findById(base.location());
    boolean placeOk =
        location
            .filter(place ->
                place.getId().equals(current.location().id())
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
  @Nullable
  State read(@Nullable String json) {
    return json == null ? null : State.read(objectMapper.readTree(json));
  }

  /**
   * Reads an order id.
   *
   * @param raw the journal's entity key
   * @return the id, or {@code null} when the key is none
   */
  private static @Nullable UUID parse(@NotNull String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException _) {
      return null;
    }
  }

  /**
   * One journaled write of the order.
   *
   * @param before the order before it, or {@code null} for its create
   * @param after the order after it
   */
  private record Step(@Nullable State before, @NotNull State after) {}

  /**
   * The counters the client's last writes left on the sections they touched.
   *
   * @param gameData the game-data counter, or {@code null} when no write touched game data
   * @param finances the finances counter, or {@code null} when no write touched the finances
   */
  private record Touched(@Nullable Long gameData, @Nullable Long finances) {}

  /**
   * What the journal holds of a refinery order: every field a client writes, the section counters
   * and the location's name for the activity list.
   *
   * @param location the location
   * @param locationName the location's name
   * @param method the refining method, or {@code null}
   * @param startedAt the run's start, or {@code null}
   * @param durationMinutes the run's length, or {@code null}
   * @param status the status
   * @param expenses the refining costs, or {@code null}
   * @param otherExpenses further costs, or {@code null}
   * @param oreSales the raw-ore revenue, or {@code null}
   * @param goods the goods with their row ids
   * @param gameData the game-data counter
   * @param finances the finances counter
   */
  record State(
      @NotNull UUID location,
      @NotNull String locationName,
      @Nullable UUID method,
      @Nullable Instant startedAt,
      @Nullable Long durationMinutes,
      @NotNull String status,
      @Nullable Double expenses,
      @Nullable Double otherExpenses,
      @Nullable Double oreSales,
      @NotNull @Unmodifiable List<Good> goods,
      long gameData,
      long finances) {

    /**
     * Captures an order.
     *
     * @param order the order as the refinery module reads it
     * @return the state
     */
    static @NotNull State of(@NotNull RefineryExchangeOrder order) {
      return new State(
          order.location().id(),
          order.location().name(),
          order.method() == null ? null : order.method().id(),
          order.startedAt(),
          order.durationMinutes(),
          order.status(),
          order.expenses(),
          order.otherExpenses(),
          order.oreSales(),
          order.goods().stream().map(Good::of).toList(),
          order.gameDataVersion(),
          order.financesVersion());
    }

    /**
     * Turns the state into the journal's JSON shape; an unset field is written as {@code null}.
     *
     * @return the state as the journal stores it
     */
    @NotNull
    Map<String, Object> toJournal() {
      Map<String, Object> state = new LinkedHashMap<>();
      state.put("location", location.toString());
      state.put("locationName", locationName);
      state.put("method", method == null ? null : method.toString());
      state.put("startedAt", startedAt == null ? null : startedAt.toString());
      state.put("durationMinutes", durationMinutes);
      state.put("status", status);
      state.put("expenses", expenses);
      state.put("otherExpenses", otherExpenses);
      state.put("oreSales", oreSales);
      state.put("goods", goods.stream().map(Good::toJournal).toList());
      state.put("gameData", gameData);
      state.put("finances", finances);
      return state;
    }

    /**
     * Reads a state the journal stores.
     *
     * @param node the state
     * @return the state, or {@code null} when it names no location, status or counters
     */
    static @Nullable State read(@NotNull JsonNode node) {
      UUID location = ExchangeJournalJson.uuid(node, "location");
      String status = ExchangeJournalJson.text(node, "status");
      JsonNode gameData = node.get("gameData");
      JsonNode finances = node.get("finances");
      if (location == null
          || status == null
          || gameData == null
          || !gameData.isNumber()
          || finances == null
          || !finances.isNumber()) {
        return null;
      }
      String startedAt = ExchangeJournalJson.text(node, "startedAt");
      List<Good> goods = new ArrayList<>();
      JsonNode rows = node.get("goods");
      if (rows != null && rows.isArray()) {
        for (JsonNode row : rows) {
          Good good = Good.read(row);
          if (good != null) {
            goods.add(good);
          }
        }
      }
      return new State(
          location,
          Objects.requireNonNullElse(ExchangeJournalJson.text(node, "locationName"), ""),
          ExchangeJournalJson.uuid(node, "method"),
          startedAt == null ? null : Instant.parse(startedAt),
          longOrNull(node, "durationMinutes"),
          status,
          doubleOrNull(node, "expenses"),
          doubleOrNull(node, "otherExpenses"),
          doubleOrNull(node, "oreSales"),
          List.copyOf(goods),
          gameData.longValue(),
          finances.longValue());
    }
  }

  /**
   * Reads a whole number of a state.
   *
   * @param node the state
   * @param field the field
   * @return the number, or {@code null} when absent or no number
   */
  private static @Nullable Long longOrNull(@NotNull JsonNode node, @NotNull String field) {
    BigDecimal value = ExchangeJournalJson.decimal(node, field);
    return value == null ? null : value.longValue();
  }

  /**
   * Reads an amount of a state.
   *
   * @param node the state
   * @param field the field
   * @return the amount, or {@code null} when absent or no number
   */
  private static @Nullable Double doubleOrNull(@NotNull JsonNode node, @NotNull String field) {
    BigDecimal value = ExchangeJournalJson.decimal(node, field);
    return value == null ? null : value.doubleValue();
  }

  /**
   * One good as the journal holds it.
   *
   * @param id the row id
   * @param input the input material
   * @param inputQuantity the input amount in units
   * @param outputQuantity the yield in units
   * @param quality the quality
   */
  record Good(
      @NotNull UUID id, @NotNull UUID input, int inputQuantity, int outputQuantity, int quality) {

    /**
     * Captures a good.
     *
     * @param good the good as the refinery module reads it
     * @return the journaled good
     */
    static @NotNull Good of(@NotNull RefineryExchangeOrder.Good good) {
      return new Good(
          good.id(),
          good.inputMaterialId(),
          good.inputQuantity(),
          good.outputQuantity(),
          good.quality());
    }

    /**
     * Turns the good into the row the refinery module writes back under its id.
     *
     * @return the good
     */
    @NotNull
    RefineryExchangeEdit.Good toEdit() {
      return new RefineryExchangeEdit.Good(id, input, inputQuantity, outputQuantity, quality);
    }

    /**
     * Turns the good into the journal's JSON shape.
     *
     * @return the good as the journal stores it
     */
    @NotNull
    Map<String, Object> toJournal() {
      Map<String, Object> good = new LinkedHashMap<>();
      good.put("id", id.toString());
      good.put("input", input.toString());
      good.put("inputQuantity", inputQuantity);
      good.put("outputQuantity", outputQuantity);
      good.put("quality", quality);
      return good;
    }

    /**
     * Reads a good the journal stores.
     *
     * @param node the good
     * @return the good, or {@code null} when a field is absent or of the wrong kind
     */
    static @Nullable Good read(@NotNull JsonNode node) {
      UUID id = ExchangeJournalJson.uuid(node, "id");
      UUID input = ExchangeJournalJson.uuid(node, "input");
      Long inputQuantity = longOrNull(node, "inputQuantity");
      Long outputQuantity = longOrNull(node, "outputQuantity");
      Long quality = longOrNull(node, "quality");
      if (id == null
          || input == null
          || inputQuantity == null
          || outputQuantity == null
          || quality == null) {
        return null;
      }
      return new Good(
          id,
          input,
          Math.toIntExact(inputQuantity),
          Math.toIntExact(outputQuantity),
          Math.toIntExact(quality));
    }
  }
}
