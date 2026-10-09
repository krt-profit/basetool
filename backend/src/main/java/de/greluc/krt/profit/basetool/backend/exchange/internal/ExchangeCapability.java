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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A capability a registry client may be granted, identified on the wire and in the database by its
 * OAuth scope (REQ-XCH-004).
 */
@RequiredArgsConstructor
public enum ExchangeCapability {

  /** The base capability: service document, installation label and account check. */
  CONNECT("exchange.connect"),

  /** Reading the member's own blueprint set. */
  BLUEPRINTS_READ("exchange.blueprints.read"),

  /** Changing the member's own blueprint set. */
  BLUEPRINTS_WRITE("exchange.blueprints.write"),

  /** Reading the member's own stock. */
  STOCK_READ("exchange.stock.read"),

  /** Changing the member's own stock. */
  STOCK_WRITE("exchange.stock.write"),

  /** Reading the member's own hangar. */
  HANGAR_READ("exchange.hangar.read"),

  /** Changing the member's own hangar. */
  HANGAR_WRITE("exchange.hangar.write"),

  /** Reading the anonymised demand of the member's org units. */
  DEMAND_READ("exchange.demand.read"),

  /** Submitting blueprint drafts for review in the browser. */
  DRAFTS_BLUEPRINTS("exchange.drafts.blueprints"),

  /** Submitting refinery drafts for review in the browser. */
  DRAFTS_REFINERY("exchange.drafts.refinery");

  /** The OAuth scope that carries this capability. */
  @Getter(onMethod_ = @__(@JsonValue))
  @NotNull
  private final String scope;

  /**
   * Resolves a capability from its OAuth scope.
   *
   * @param scope the scope string, compared exactly
   * @return the capability, or empty when no capability carries that scope
   */
  @NotNull
  public static Optional<ExchangeCapability> fromScope(@Nullable String scope) {
    return Arrays.stream(values()).filter(c -> c.scope.equals(scope)).findFirst();
  }

  /**
   * Resolves a capability from its OAuth scope for JSON binding.
   *
   * @param scope the scope string
   * @return the capability
   * @throws IllegalArgumentException when no capability carries that scope
   */
  @NotNull
  @JsonCreator
  public static ExchangeCapability ofScope(@Nullable String scope) {
    return fromScope(scope)
        .orElseThrow(() -> new IllegalArgumentException("Unknown exchange capability: " + scope));
  }
}
