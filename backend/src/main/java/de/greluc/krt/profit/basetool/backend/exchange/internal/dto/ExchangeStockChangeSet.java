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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import de.greluc.krt.profit.basetool.backend.kernel.QualityValue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A client's batch of stock changes, as the ingest gateway relays it after checking it against
 * {@code change-set.schema.json#/$defs/stockChangeSet} (REQ-XCH-016).
 *
 * @param ops the changes, at most 500
 * @param dryRun whether to report what would happen without writing
 */
public record ExchangeStockChangeSet(
    @NotNull @Size(min = 1, max = 500) List<@NotNull @Valid Op> ops, boolean dryRun) {

  /** The op that sets a lot to a quantity. */
  public static final String SET_QUANTITY = "set-quantity";

  /** The op that moves part of a lot's personal stock into a unit's pool. */
  public static final String SHARE = "share";

  /** The op that moves part of the member's own stock in a unit's pool back to personal. */
  public static final String UNSHARE = "unshare";

  /**
   * One op on a lot: a {@code set-quantity} sets it to a quantity, a {@code share} moves part of
   * its personal stock into the pool of {@code orgUnit}, an {@code unshare} moves part of the
   * member's own stock in that pool back to personal.
   *
   * @param opId the client's id for the op, echoed in the result, or {@code null}
   * @param op {@code set-quantity}, {@code share} or {@code unshare}
   * @param material the material or item
   * @param location the Lager location
   * @param quality the quality of a material lot, ignored for an item
   * @param stolen whether the lot is marked stolen
   * @param quantity the quantity the lot should hold, or the amount a share or unshare moves
   * @param expectedQuantity the quantity the client last saw, 0 for a new lot; for a share, the
   *     lot's personal amount in its last synced {@code split}; for an unshare, that pool's amount
   * @param override whether a {@code set-quantity} may refill a lot emptied elsewhere, after asking
   *     the member
   * @param orgUnit the unit whose pool a {@code share} fills or an {@code unshare} empties;
   *     required there, ignored otherwise
   */
  @Schema(name = "Op")
  public record Op(
      @Nullable @Size(min = 1, max = 64) String opId,
      @NotNull @Pattern(regexp = "^(set-quantity|share|unshare)$") String op,
      @NotNull @Valid ExchangeItemRef material,
      @NotNull @Valid ExchangeLocationRef location,
      @NotNull @QualityValue Integer quality,
      @NotNull Boolean stolen,
      @NotNull @Valid Quantity quantity,
      @NotNull @Valid Quantity expectedQuantity,
      @Nullable Boolean override,
      @Nullable @Valid UnitRef orgUnit) {}

  /**
   * The org unit a share or unshare names.
   *
   * @param id the unit's id
   */
  @Schema(name = "StockOpUnit")
  public record UnitRef(@NotNull UUID id) {}

  /**
   * An amount with its unit.
   *
   * @param amount the amount, from 0 to 10<sup>9</sup> like the gateway's {@code
   *     quantity.schema.json}
   * @param unit {@code SCU} or {@code PIECE}
   */
  public record Quantity(
      @NotNull @DecimalMin("0") @DecimalMax("1000000000") BigDecimal amount,
      @NotNull @Pattern(regexp = "^(SCU|PIECE)$") String unit) {}
}
