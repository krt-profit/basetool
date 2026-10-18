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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
public class ExchangeStockJournal implements ExchangeEntryRestorer {

  private final ExchangeStockWriteService stockWriteService;
  private final ExchangeChangeRepository changeRepository;
  private final ObjectMapper objectMapper;

  @Override
  public @NotNull ExchangeResource resource() {
    return ExchangeResource.STOCK;
  }

  /**
   * Sets the lot's pools back by the net amount the client's shares and unshares moved into each,
   * then sets the lot back to its quantity before the client's first write.
   *
   * @param scope the member and the client
   * @param group the client's writes to the lot, newest first
   * @return {@code null} when restored, {@code CHANGED_AFTERWARDS} when something else changed the
   *     lot, {@code GONE} when a pool no longer holds what was shared into it, stock cannot go back
   *     into a pool the member has left, or the lot's catalogue entry or location is gone
   */
  @Override
  public @Nullable String restore(
      @NotNull UndoScope scope, @NotNull List<ExchangeJournalEntry> group) {
    ExchangeJournalEntry newest = group.getFirst();
    String lotKey = newest.getEntityKey();
    if (ExchangeEntryRestorer.changedAfterwards(changeRepository, scope.member(), newest, lotKey)) {
      return ExchangeUndoService.CHANGED_AFTERWARDS;
    }
    State before = read(group.getLast().getBeforeState());
    if (before == null || before.quantity() == null || before.unit() == null) {
      return ExchangeUndoService.GONE;
    }
    Map<UUID, BigDecimal> moved = new TreeMap<>();
    for (ExchangeJournalEntry write : group) {
      boolean share = write.getAction() == ExchangeJournalAction.STOCK_SHARE;
      if (share || write.getAction() == ExchangeJournalAction.STOCK_UNSHARE) {
        State after = read(write.getAfterState());
        BigDecimal amount = after == null ? null : share ? after.shared() : after.unshared();
        if (after == null || after.orgUnit() == null || amount == null) {
          return ExchangeUndoService.GONE;
        }
        moved.merge(after.orgUnit(), share ? amount : amount.negate(), BigDecimal::add);
      }
    }
    if (!moved.isEmpty()
        && !stockWriteService.restorePools(scope.member(), lotKey, before.unit(), moved)) {
      return ExchangeUndoService.GONE;
    }
    return stockWriteService.restoreLot(scope.member(), lotKey, before.quantity(), before.unit())
        ? null
        : ExchangeUndoService.GONE;
  }

  /**
   * Reads a journaled lot state.
   *
   * @param json the state as the journal stores it, or {@code null}
   * @return the state, or {@code null} for none
   */
  @Nullable
  State read(@Nullable String json) {
    return json == null ? null : State.read(objectMapper.readTree(json));
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
   */
  record State(
      @Nullable BigDecimal quantity,
      @Nullable String unit,
      @Nullable UUID orgUnit,
      @Nullable BigDecimal shared,
      @Nullable BigDecimal unshared) {

    /**
     * Captures a lot's quantity.
     *
     * @param quantity the quantity
     * @param unit the unit
     * @return the state
     */
    static @NotNull State of(@NotNull BigDecimal quantity, @NotNull String unit) {
      return new State(quantity, unit, null, null, null);
    }

    /**
     * Captures a lot after a share or an unshare, which leaves its quantity as it is.
     *
     * @param quantity the lot's quantity
     * @param unit the lot's unit
     * @param orgUnit the pool's unit
     * @param share {@code true} for a share, {@code false} for an unshare
     * @param amount the amount moved
     * @return the state
     */
    static @NotNull State moved(
        @NotNull BigDecimal quantity,
        @NotNull String unit,
        @NotNull UUID orgUnit,
        boolean share,
        @NotNull BigDecimal amount) {
      return new State(quantity, unit, orgUnit, share ? amount : null, share ? null : amount);
    }

    /**
     * Turns the state into the journal's JSON shape: {@code quantity} and {@code unit} always,
     * {@code orgUnit}, {@code shared} and {@code unshared} only when set.
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
      return state;
    }

    /**
     * Reads a state the journal stores, from any version of the writer.
     *
     * @param node the state
     * @return the state; a field that is absent or of the wrong kind reads as none
     */
    static @NotNull State read(@NotNull JsonNode node) {
      return new State(
          ExchangeJournalJson.decimal(node, "quantity"),
          ExchangeJournalJson.text(node, "unit"),
          ExchangeJournalJson.uuid(node, "orgUnit"),
          ExchangeJournalJson.decimal(node, "shared"),
          ExchangeJournalJson.decimal(node, "unshared"));
    }
  }
}
