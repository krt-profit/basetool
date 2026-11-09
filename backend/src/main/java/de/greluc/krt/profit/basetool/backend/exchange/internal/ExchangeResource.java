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

import java.util.Arrays;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A resource the exchange syncs, each with its own change feed (REQ-XCH-013, REQ-XCH-042). */
public enum ExchangeResource {
  /** The member's personal blueprints, keyed by product. */
  BLUEPRINT,

  /** The member's stock, keyed by lot. */
  STOCK,

  /** The member's ships. */
  SHIP,

  /** The shared stock of the member's units, keyed by unit and lot; its writes by entry. */
  ORG_STOCK,

  /** The member's own bank booking requests, keyed by request id; no mass change reaches them. */
  BANK_REQUEST;

  /**
   * The resource's name in a staged mass change ({@code ConnectedAppMassChangeRequestDto}).
   *
   * @return {@code blueprints}, {@code stock}, {@code ships} or {@code org-stock}, or {@code null}
   *     for {@link #BANK_REQUEST}, which no mass change reaches
   */
  @Nullable
  public String massChangeName() {
    return switch (this) {
      case BLUEPRINT -> "blueprints";
      case STOCK -> "stock";
      case SHIP -> "ships";
      case ORG_STOCK -> "org-stock";
      case BANK_REQUEST -> null;
    };
  }

  /**
   * The write capability a client needs to change this resource.
   *
   * @return the capability
   */
  @NotNull
  public ExchangeCapability writeCapability() {
    return switch (this) {
      case BLUEPRINT -> ExchangeCapability.BLUEPRINTS_WRITE;
      case STOCK -> ExchangeCapability.STOCK_WRITE;
      case SHIP -> ExchangeCapability.HANGAR_WRITE;
      case ORG_STOCK -> ExchangeCapability.ORGSTOCK_WRITE;
      case BANK_REQUEST -> ExchangeCapability.BANK_REQUEST;
    };
  }

  /**
   * The value of the {@code resource} tag on the resource's mass-change metric.
   *
   * @return {@code blueprint}, {@code stock}, {@code ship}, {@code org_stock} or {@code
   *     bank_request}
   */
  @NotNull
  public String metricTag() {
    return switch (this) {
      case BLUEPRINT -> "blueprint";
      case STOCK -> "stock";
      case SHIP -> "ship";
      case ORG_STOCK -> "org_stock";
      case BANK_REQUEST -> "bank_request";
    };
  }

  /**
   * Maps a staged mass change's resource name to its resource.
   *
   * @param name the name, as {@link #massChangeName()} spells it
   * @return the resource, or empty for any other value
   */
  @NotNull
  public static Optional<ExchangeResource> fromMassChangeName(@Nullable String name) {
    return name == null
        ? Optional.empty()
        : Arrays.stream(values()).filter(r -> name.equals(r.massChangeName())).findFirst();
  }
}
