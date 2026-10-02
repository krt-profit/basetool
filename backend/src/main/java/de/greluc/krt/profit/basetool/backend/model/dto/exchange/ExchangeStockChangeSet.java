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

package de.greluc.krt.profit.basetool.backend.model.dto.exchange;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
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

  /**
   * One lot's new quantity.
   *
   * @param opId the client's id for the op, echoed in the result, or {@code null}
   * @param op always {@code set-quantity}
   * @param material the material or item
   * @param location the Lager location
   * @param quality the quality of a material lot, ignored for an item
   * @param stolen whether the lot is marked stolen
   * @param quantity the quantity the lot should hold
   * @param expectedQuantity the quantity the client last saw, 0 for a new lot
   * @param override whether the op may refill a lot emptied elsewhere, after asking the member
   */
  @Schema(name = "Op")
  public record Op(
      @Nullable @Size(min = 1, max = 64) String opId,
      @NotNull @Pattern(regexp = "^set-quantity$") String op,
      @NotNull @Valid ExchangeItemRef material,
      @NotNull @Valid ExchangeLocationRef location,
      @NotNull @Min(0) @Max(1000) Integer quality,
      @NotNull Boolean stolen,
      @NotNull @Valid Quantity quantity,
      @NotNull @Valid Quantity expectedQuantity,
      @Nullable Boolean override) {}

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
