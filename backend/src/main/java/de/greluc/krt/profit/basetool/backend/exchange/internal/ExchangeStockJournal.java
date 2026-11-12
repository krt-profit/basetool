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

import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityComposition;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns the journal state of a member's stock lot: what the stock writer and its shares record and
 * what an undo reads back to set the lot's pools and quantity back (REQ-XCH-016, REQ-XCH-022,
 * ADR-0285).
 */
@Component
@RequiredArgsConstructor
public class ExchangeStockJournal {
  private final ObjectMapper objectMapper;

  /**
   * Reads a journaled lot state.
   *
   * @param json the state as the journal stores it, or {@code null}
   * @return the state, or {@code null} for none
   */
  @Nullable
  State read(@Nullable String json) {
    return json == null ? null : State.read(objectMapper, objectMapper.readTree(json));
  }

  /**
   * What the journal holds of a lot: its quantity, and for a share or unshare the pool and the
   * amount moved.
   *
   * @param quantity the lot's quantity, or {@code null} when the state names none
   * @param unit the lot's unit, or {@code null} when the state names none
   * @param orgUnit the pool's unit of a share or unshare, or {@code null}
   * @param shared the amount a share moved into the pool, or {@code null}
   * @param unshared the amount an unshare moved back to personal, or {@code null}
   * @param completeQuantity complete ordered quantity proof, or null when the old state lacks it
   * @param operationStep actual admitted operation index within the containing transaction/batch
   */
  record State(
      @Nullable BigDecimal quantity,
      @Nullable String unit,
      @Nullable UUID orgUnit,
      @Nullable BigDecimal shared,
      @Nullable BigDecimal unshared,
      @Nullable StockQuantityComposition completeQuantity,
      @Nullable Integer operationStep) {

    /** Requires a supplied complete composition to describe the exact stated physical quantity. */
    State {
      if (completeQuantity != null
          && quantity != null
          && completeQuantity.totalQuanta() != quantity.movePointRight(3).longValueExact()) {
        throw new IllegalArgumentException(
            "Complete journal proof must match its physical quantity");
      }
    }

    /**
     * Captures a lot's quantity.
     *
     * @param quantity the quantity
     * @param unit the unit
     * @param completeQuantity complete composition proved by actual locked execution
     * @param operationStep actual admitted operation index within this batch
     * @return the state
     */
    static @NotNull State of(
        @NotNull BigDecimal quantity,
        @NotNull String unit,
        @NotNull StockQuantityComposition completeQuantity,
        int operationStep) {
      return new State(
          quantity,
          unit,
          null,
          null,
          null,
          Objects.requireNonNull(completeQuantity),
          operationStep);
    }

    /**
     * Captures a lot after a share or an unshare, which leaves its quantity as it is.
     *
     * @param quantity the lot's quantity
     * @param unit the lot's unit
     * @param orgUnit the pool's unit
     * @param share {@code true} for a share, {@code false} for an unshare
     * @param amount the amount moved
     * @param completeQuantity complete composition proved by actual locked execution
     * @param operationStep actual admitted operation index within this batch
     * @return the state
     */
    static @NotNull State moved(
        @NotNull BigDecimal quantity,
        @NotNull String unit,
        @NotNull UUID orgUnit,
        boolean share,
        @NotNull BigDecimal amount,
        @NotNull StockQuantityComposition completeQuantity,
        int operationStep) {
      return new State(
          quantity,
          unit,
          orgUnit,
          share ? amount : null,
          share ? null : amount,
          Objects.requireNonNull(completeQuantity),
          operationStep);
    }

    /**
     * Encodes the quantity and unit with every present pool movement and complete proof.
     *
     * @return the state as the journal stores it
     */
    @NotNull
    Map<String, Object> toJournal() {
      Map<String, Object> state = new LinkedHashMap<>();
      state.put("quantity", quantity);
      state.put("unit", unit);
      if (orgUnit != null) {
        state.put("orgUnit", orgUnit.toString());
      }
      if (shared != null) {
        state.put("shared", shared);
      }
      if (unshared != null) {
        state.put("unshared", unshared);
      }
      if (completeQuantity != null) {
        state.put("completeQuantity", completeQuantity);
      }
      if (operationStep != null) {
        state.put("operationStep", operationStep);
      }
      return state;
    }

    /**
     * Reads a state the journal stores, from any version of the writer.
     *
     * @param objectMapper the configured decoder of explicit closed evidence kinds
     * @param node the state
     * @return the state, with absent complete proof remaining distinct from known zero
     */
    static @NotNull State read(@NotNull ObjectMapper objectMapper, @NotNull JsonNode node) {
      return new State(
          ExchangeJournalJson.decimal(node, "quantity"),
          ExchangeJournalJson.text(node, "unit"),
          ExchangeJournalJson.uuid(node, "orgUnit"),
          ExchangeJournalJson.decimal(node, "shared"),
          ExchangeJournalJson.decimal(node, "unshared"),
          node.hasNonNull("completeQuantity")
              ? objectMapper.treeToValue(
                  node.path("completeQuantity"), StockQuantityComposition.class)
              : null,
          readStep(node));
    }

    /** Reads only a nonnegative exact admitted index without guessing old journal order. */
    private static @Nullable Integer readStep(JsonNode node) {
      if (!node.path("operationStep").isIntegralNumber()) {
        return null;
      }
      try {
        int step =
            Objects.requireNonNull(ExchangeJournalJson.decimal(node, "operationStep"))
                .intValueExact();
        return step < 0 ? null : step;
      } catch (ArithmeticException _) {
        return null;
      }
    }
  }
}
