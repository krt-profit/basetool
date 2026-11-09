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
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeOrigin;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import tools.jackson.databind.JsonNode;

/**
 * What the journal holds of a refinery order, and its JSON shape (REQ-XCH-056): every field a
 * client writes, the section counters and the location's name for the activity list.
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
   * Captures an order.
   *
   * @param order the order as the refinery module reads it
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
   * The goods as the rows the refinery module writes back under their ids.
   *
   * @return the goods
   */
  @NotNull
  @Unmodifiable
  List<RefineryExchangeEdit.Good> editGoods() {
    return goods.stream().map(Good::toEdit).toList();
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
        Objects.requireNonNullElse(ExchangeJournalJson.text(node, LOCATION_NAME), ""),
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

    private static final String ID = "id";
    private static final String INPUT = "input";
    private static final String INPUT_QUANTITY = "inputQuantity";
    private static final String OUTPUT_QUANTITY = "outputQuantity";
    private static final String QUALITY = "quality";

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
      good.put(ID, id.toString());
      good.put(INPUT, input.toString());
      good.put(INPUT_QUANTITY, inputQuantity);
      good.put(OUTPUT_QUANTITY, outputQuantity);
      good.put(QUALITY, quality);
      return good;
    }

    /**
     * Reads a good the journal stores.
     *
     * @param node the good
     * @return the good, or {@code null} when a field is absent or of the wrong kind
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
          Math.toIntExact(inputQuantity),
          Math.toIntExact(outputQuantity),
          Math.toIntExact(quality));
    }
  }
}
