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

import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeEdit;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeOrder;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeOrigin;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeRules;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeStoreLine;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import tools.jackson.databind.JsonNode;

/**
 * A refinery order as an exchange write sees it (REQ-XCH-052, REQ-XCH-056): what each op of a
 * change set is decided against, what the journal holds for the undo, and its JSON shape there. A
 * real run reads it from every order the refinery module returns; a dry run derives it from each op
 * by the {@link de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeWriter}
 * contract, so both decide every op alike.
 *
 * @param location the location
 * @param locationName the location's name
 * @param method the refining method, or {@code null}
 * @param startedAt the run's start, or {@code null}
 * @param durationMinutes the run's length, or {@code null}
 * @param status the status
 * @param expenses the refining costs as stored, or {@code null}
 * @param otherExpenses further costs as stored, or {@code null}
 * @param oreSales the raw-ore revenue as stored, or {@code null}
 * @param goods the goods with their row ids
 * @param gameData the game-data counter
 * @param finances the finances counter
 * @param stored whether the order's yield is booked into stock
 * @param origin what a deleted order held beyond the fields a client writes, for its recreation;
 *     {@code null} on every write but a delete
 */
record ExchangeRefineryOrderState(
    @NotNull UUID location,
    @NotNull String locationName,
    @Nullable UUID method,
    @Nullable Instant startedAt,
    @Nullable Long durationMinutes,
    @NotNull RefineryOrderStatus status,
    @Nullable Double expenses,
    @Nullable Double otherExpenses,
    @Nullable Double oreSales,
    @NotNull @Unmodifiable List<Good> goods,
    long gameData,
    long finances,
    boolean stored,
    @Nullable RefineryExchangeOrigin origin) {

  private static final String LOCATION = "location";
  private static final String LOCATION_NAME = "locationName";
  private static final String METHOD = "method";
  private static final String STARTED_AT = "startedAt";
  private static final String DURATION_MINUTES = "durationMinutes";
  private static final String STATUS = "status";
  private static final String EXPENSES = "expenses";
  private static final String OTHER_EXPENSES = "otherExpenses";
  private static final String ORE_SALES = "oreSales";
  private static final String GOODS = "goods";
  private static final String GAME_DATA = "gameData";
  private static final String FINANCES = "finances";
  private static final String STORED = "stored";
  private static final String ORIGIN = "origin";
  private static final String CREATED_AT = "createdAt";
  private static final String CLIENT = "client";
  private static final String ORG_UNIT = "orgUnit";
  private static final String MISSION = "mission";
  private static final String PICKED_UP_AT = "pickedUpAt";
  private static final String PICKED_UP_CLIENT = "pickedUpClient";
  private static final String READY_NOTIFIED_AT = "readyNotifiedAt";

  /**
   * Captures an order as the refinery module returns it.
   *
   * @param order the order
   * @return the state
   */
  static @NotNull ExchangeRefineryOrderState of(@NotNull RefineryExchangeOrder order) {
    return new ExchangeRefineryOrderState(
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
        order.financesVersion(),
        order.storedAt() != null,
        null);
  }

  /**
   * The order a create writes: counters at 0, the run started at its timer or at the write.
   *
   * @param edit the create's parts, with location and status
   * @param place the location's name
   * @param outputs the output material of each input the create names
   * @param now the time of the write
   * @return the state of the created order
   */
  static @NotNull ExchangeRefineryOrderState created(
      @NotNull RefineryExchangeEdit edit,
      @NotNull String place,
      @NotNull Map<UUID, UUID> outputs,
      @NotNull Instant now) {
    RefineryExchangeEdit.Timer timer = edit.timer();
    RefineryExchangeEdit.Finances finances = edit.finances();
    return new ExchangeRefineryOrderState(
        Objects.requireNonNull(edit.locationId(), "locationId"),
        place,
        edit.method() == null ? null : edit.method().id(),
        timer != null && timer.startedAt() != null ? timer.startedAt() : now,
        timer == null ? null : timer.durationMinutes(),
        Objects.requireNonNull(edit.status(), "status"),
        finances == null ? null : RefineryExchangeRules.money(finances.expenses()),
        finances == null ? null : RefineryExchangeRules.money(finances.otherExpenses()),
        finances == null ? null : RefineryExchangeRules.money(finances.oreSales()),
        edit.goods() == null ? List.of() : goods(edit.goods(), outputs),
        0L,
        0L,
        false,
        null);
  }

  /**
   * The order after an edit: each section it writes set and its counter raised by one.
   *
   * @param edit the edit
   * @param place the name of the location the edit names, or {@code null} when it names none
   * @param outputs the output material of each input the edit newly names
   * @return the state after the edit
   */
  @NotNull
  ExchangeRefineryOrderState edited(
      @NotNull RefineryExchangeEdit edit,
      @Nullable String place,
      @NotNull Map<UUID, UUID> outputs) {
    RefineryExchangeEdit.Timer timer = edit.timer();
    RefineryExchangeEdit.Finances finances = edit.finances();
    Map<UUID, UUID> known = new HashMap<>(outputs);
    goods.forEach(good -> {
      if (good.output() != null) {
        known.putIfAbsent(good.input(), good.output());
      }
    });
    return new ExchangeRefineryOrderState(
        edit.locationId() == null ? location : edit.locationId(),
        edit.locationId() == null || place == null ? locationName : place,
        edit.method() == null ? method : edit.method().id(),
        timer == null ? startedAt : timer.startedAt(),
        timer == null ? durationMinutes : timer.durationMinutes(),
        edit.status() == null ? status : edit.status(),
        finances == null ? expenses : RefineryExchangeRules.money(finances.expenses()),
        finances == null ? otherExpenses : RefineryExchangeRules.money(finances.otherExpenses()),
        finances == null ? oreSales : RefineryExchangeRules.money(finances.oreSales()),
        edit.goods() == null ? goods : goods(edit.goods(), known),
        gameData + (edit.touchesGameData() ? 1 : 0),
        this.finances + (finances == null ? 0 : 1),
        stored,
        null);
  }

  /**
   * The order after a cancel.
   *
   * @return the state, cancelled with the game-data counter raised by one
   */
  @NotNull
  ExchangeRefineryOrderState canceled() {
    return closed(RefineryOrderStatus.CANCELED, stored, goods);
  }

  /**
   * The order after a pickup. Store lines store it and write the amounts booked onto each good back
   * into that good's yield, summed, as the refinery's store does (REQ-REFINERY-022).
   *
   * @param lines the pickup's store lines, each of an output of the goods; empty to book nothing
   * @return the state, completed with the game-data counter raised by one
   */
  @NotNull
  ExchangeRefineryOrderState pickedUp(@NotNull List<RefineryExchangeStoreLine> lines) {
    if (lines.isEmpty()) {
      return closed(RefineryOrderStatus.COMPLETED, stored, goods);
    }
    List<Integer> positions = IntStream.range(0, goods.size()).boxed().toList();
    Map<Integer, Booked> booked = new HashMap<>();
    for (RefineryExchangeStoreLine line : lines) {
      Integer at =
          Objects.requireNonNull(
              RefineryExchangeRules.storedGood(
                  positions,
                  i -> goods.get(i).output(),
                  i -> goods.get(i).quality(),
                  line.materialId(),
                  line.quality()),
              "good");
      booked.merge(at, new Booked(line.unit(), line.amount()), Booked::plus);
    }
    List<Good> written =
        positions.stream()
            .map(i -> {
              Booked onto = booked.get(i);
              return onto == null
                  ? goods.get(i)
                  : goods
                      .get(i)
                      .withOutputQuantity(
                          RefineryExchangeRules.storedYield(onto.unit(), onto.amount()));
            })
            .toList();
    return closed(RefineryOrderStatus.COMPLETED, true, written);
  }

  /**
   * Whether an update would leave the order as it is.
   *
   * @param edit the update
   * @return {@code true} when every part it carries equals the order's
   */
  boolean unchangedBy(@NotNull RefineryExchangeEdit edit) {
    RefineryExchangeEdit.Finances money = edit.finances();
    RefineryExchangeEdit.Timer timer = edit.timer();
    return (edit.locationId() == null || edit.locationId().equals(location))
        && (edit.method() == null || Objects.equals(edit.method().id(), method))
        && (edit.status() == null || edit.status() == status)
        && (timer == null
            || (Objects.equals(timer.startedAt(), startedAt)
                && Objects.equals(timer.durationMinutes(), durationMinutes)))
        && (money == null
            || (Objects.equals(RefineryExchangeRules.money(money.expenses()), expenses)
                && Objects.equals(RefineryExchangeRules.money(money.otherExpenses()), otherExpenses)
                && Objects.equals(RefineryExchangeRules.money(money.oreSales()), oreSales)));
  }

  /**
   * The goods as the rows the refinery module writes back under their ids.
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
    return goods.stream()
        .map(Good::output)
        .filter(Objects::nonNull)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Adds what the order held beyond the fields a client writes, as its delete reports it.
   *
   * @param removed the deleted order's origin
   * @return the state of the deleted order
   */
  @NotNull
  ExchangeRefineryOrderState deleted(@NotNull RefineryExchangeOrigin removed) {
    return new ExchangeRefineryOrderState(
        location,
        locationName,
        method,
        startedAt,
        durationMinutes,
        status,
        expenses,
        otherExpenses,
        oreSales,
        goods,
        gameData,
        finances,
        stored,
        removed);
  }

  /**
   * Turns the state into the journal's JSON shape; an unset field is written as {@code null}.
   *
   * @return the state as the journal stores it
   */
  @NotNull
  Map<String, Object> toJournal() {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put(LOCATION, location.toString());
    state.put(LOCATION_NAME, locationName);
    state.put(METHOD, text(method));
    state.put(STARTED_AT, text(startedAt));
    state.put(DURATION_MINUTES, durationMinutes);
    state.put(STATUS, status.name());
    state.put(EXPENSES, expenses);
    state.put(OTHER_EXPENSES, otherExpenses);
    state.put(ORE_SALES, oreSales);
    state.put(GOODS, goods.stream().map(Good::toJournal).toList());
    state.put(GAME_DATA, gameData);
    state.put(FINANCES, finances);
    state.put(STORED, stored);
    if (origin != null) {
      state.put(ORIGIN, originToJournal(origin));
    }
    return state;
  }

  /**
   * Reads a state the journal stores.
   *
   * @param node the state
   * @return the state, or {@code null} when it names no location, known status or counters
   */
  static @Nullable ExchangeRefineryOrderState read(@NotNull JsonNode node) {
    UUID location = ExchangeJournalJson.uuid(node, LOCATION);
    RefineryOrderStatus status = status(ExchangeJournalJson.text(node, STATUS));
    JsonNode gameData = node.get(GAME_DATA);
    JsonNode finances = node.get(FINANCES);
    JsonNode stored = node.get(STORED);
    if (location == null
        || status == null
        || gameData == null
        || !gameData.isNumber()
        || finances == null
        || !finances.isNumber()) {
      return null;
    }
    List<Good> goods = new ArrayList<>();
    JsonNode rows = node.get(GOODS);
    if (rows != null && rows.isArray()) {
      for (JsonNode row : rows) {
        Good good = Good.read(row);
        if (good != null) {
          goods.add(good);
        }
      }
    }
    return new ExchangeRefineryOrderState(
        location,
        Objects.requireNonNullElse(locationName(node), ""),
        ExchangeJournalJson.uuid(node, METHOD),
        instant(node, STARTED_AT),
        longOrNull(node, DURATION_MINUTES),
        status,
        doubleOrNull(node, EXPENSES),
        doubleOrNull(node, OTHER_EXPENSES),
        doubleOrNull(node, ORE_SALES),
        List.copyOf(goods),
        gameData.longValue(),
        finances.longValue(),
        stored != null && stored.asBoolean(),
        originOf(node.get(ORIGIN)));
  }

  /**
   * Reads the location's name of a state the journal stores, for the activity list.
   *
   * @param node the state
   * @return the name, or {@code null} when absent
   */
  static @Nullable String locationName(@NotNull JsonNode node) {
    return ExchangeJournalJson.text(node, LOCATION_NAME);
  }

  /**
   * The order closed with a status, the game-data counter raised by one.
   *
   * @param closedAs the status
   * @param storedAfter whether the yield is booked afterwards
   * @param goodsAfter the goods afterwards
   * @return the state
   */
  private @NotNull ExchangeRefineryOrderState closed(
      @NotNull RefineryOrderStatus closedAs,
      boolean storedAfter,
      @NotNull @Unmodifiable List<Good> goodsAfter) {
    return new ExchangeRefineryOrderState(
        location,
        locationName,
        method,
        startedAt,
        durationMinutes,
        closedAs,
        expenses,
        otherExpenses,
        oreSales,
        goodsAfter,
        gameData + 1,
        finances,
        storedAfter,
        null);
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
                outputs.get(good.inputMaterialId()),
                good.inputQuantity(),
                good.outputQuantity(),
                good.quality()))
        .toList();
  }

  /**
   * Reads a journaled status.
   *
   * @param text the status's name, or {@code null}
   * @return the status, or {@code null} when absent or unknown
   */
  private static @Nullable RefineryOrderStatus status(@Nullable String text) {
    return Arrays.stream(RefineryOrderStatus.values())
        .filter(candidate -> candidate.name().equals(text))
        .findFirst()
        .orElse(null);
  }

  /**
   * Turns a deleted order's origin into the journal's JSON shape; an unset field is written as
   * {@code null}.
   *
   * @param origin the origin
   * @return the origin as the journal stores it
   */
  private static @NotNull Map<String, Object> originToJournal(
      @NotNull RefineryExchangeOrigin origin) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put(CREATED_AT, text(origin.createdAt()));
    state.put(CLIENT, origin.createdClient());
    state.put(ORG_UNIT, text(origin.orgUnitId()));
    state.put(MISSION, text(origin.missionId()));
    state.put(PICKED_UP_AT, text(origin.pickedUpAt()));
    state.put(PICKED_UP_CLIENT, origin.pickedUpClient());
    state.put(READY_NOTIFIED_AT, text(origin.readyNotifiedAt()));
    return state;
  }

  /**
   * Reads a deleted order's origin the journal stores.
   *
   * @param node the origin, or {@code null} for a state without one
   * @return the origin, or {@code null} when the state holds none
   */
  private static @Nullable RefineryExchangeOrigin originOf(@Nullable JsonNode node) {
    if (node == null || !node.isObject()) {
      return null;
    }
    return new RefineryExchangeOrigin(
        instant(node, CREATED_AT),
        ExchangeJournalJson.text(node, CLIENT),
        ExchangeJournalJson.uuid(node, ORG_UNIT),
        ExchangeJournalJson.uuid(node, MISSION),
        instant(node, PICKED_UP_AT),
        ExchangeJournalJson.text(node, PICKED_UP_CLIENT),
        instant(node, READY_NOTIFIED_AT));
  }

  /**
   * Writes a value as the journal's text.
   *
   * @param value the value, or {@code null}
   * @return its text, or {@code null}
   */
  private static @Nullable String text(@Nullable Object value) {
    return value == null ? null : value.toString();
  }

  /**
   * Reads a point in time of a state.
   *
   * @param node the state
   * @param field the field
   * @return the instant, or {@code null} when absent
   */
  private static @Nullable Instant instant(@NotNull JsonNode node, @NotNull String field) {
    String value = ExchangeJournalJson.text(node, field);
    return value == null ? null : Instant.parse(value);
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
   * The amounts a pickup's store lines book onto one good.
   *
   * @param unit the output material's own unit
   * @param amount the amounts together, in that unit
   */
  private record Booked(@NotNull QuantityType unit, double amount) {

    /**
     * Adds another line's amount.
     *
     * @param other the other line's booking
     * @return the bookings together
     */
    @NotNull
    Booked plus(@NotNull Booked other) {
      return new Booked(unit, amount + other.amount());
    }
  }

  /**
   * One good.
   *
   * @param id the row id, or {@code null} for one a dry run adds
   * @param input the input material
   * @param output the output material, or {@code null} in a journal state written without it
   * @param inputQuantity the input amount in units
   * @param outputQuantity the yield in units
   * @param quality the quality
   */
  record Good(
      @Nullable UUID id,
      @NotNull UUID input,
      @Nullable UUID output,
      int inputQuantity,
      int outputQuantity,
      int quality) {

    private static final String ID = "id";
    private static final String INPUT = "input";
    private static final String OUTPUT = "output";
    private static final String INPUT_QUANTITY = "inputQuantity";
    private static final String OUTPUT_QUANTITY = "outputQuantity";
    private static final String QUALITY = "quality";

    /**
     * Captures a good.
     *
     * @param good the good as the refinery module reads it
     * @return the good
     */
    static @NotNull Good of(@NotNull RefineryExchangeOrder.Good good) {
      return new Good(
          good.id(),
          good.inputMaterialId(),
          good.outputMaterialId(),
          good.inputQuantity(),
          good.outputQuantity(),
          good.quality());
    }

    /**
     * The same good with another yield.
     *
     * @param units the yield in units
     * @return the good
     */
    @NotNull
    Good withOutputQuantity(int units) {
      return new Good(id, input, output, inputQuantity, units, quality);
    }

    /**
     * Turns the good into the row the refinery module writes back under its id.
     *
     * @return the row
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
      good.put(ID, text(id));
      good.put(INPUT, input.toString());
      good.put(OUTPUT, text(output));
      good.put(INPUT_QUANTITY, inputQuantity);
      good.put(OUTPUT_QUANTITY, outputQuantity);
      good.put(QUALITY, quality);
      return good;
    }

    /**
     * Reads a good the journal stores.
     *
     * @param node the good
     * @return the good, or {@code null} when a required field is absent or of the wrong kind
     */
    static @Nullable Good read(@NotNull JsonNode node) {
      UUID id = ExchangeJournalJson.uuid(node, ID);
      UUID input = ExchangeJournalJson.uuid(node, INPUT);
      Long inputQuantity = longOrNull(node, INPUT_QUANTITY);
      Long outputQuantity = longOrNull(node, OUTPUT_QUANTITY);
      Long quality = longOrNull(node, QUALITY);
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
          ExchangeJournalJson.uuid(node, OUTPUT),
          Math.toIntExact(inputQuantity),
          Math.toIntExact(outputQuantity),
          Math.toIntExact(quality));
    }
  }
}
