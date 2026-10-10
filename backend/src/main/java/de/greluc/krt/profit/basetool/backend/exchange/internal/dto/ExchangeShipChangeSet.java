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

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * A client's batch of ship changes, as the ingest gateway relays it after checking it against
 * {@code change-set.schema.json#/$defs/shipChangeSet} (REQ-XCH-017).
 *
 * @param ops the ops, applied in order
 * @param dryRun whether to plan and report without writing
 */
public record ExchangeShipChangeSet(
    @NotNull @Size(min = 1, max = 500) List<@NotNull @Valid Op> ops, boolean dryRun) {

  /** The op that links an installation's ship to a server ship. */
  public static final String LINK = "link";

  /** The op that creates or updates a ship. */
  public static final String UPSERT = "upsert";

  /** The op that removes a ship. */
  public static final String REMOVE = "remove";

  /**
   * One ship op; which fields it needs depends on {@code op}.
   *
   * @param opId the client's id for the op, echoed in the result, or {@code null}
   * @param op {@code link}, {@code upsert} or {@code remove}
   * @param externalId the installation's id for the ship; required for {@code link} and {@code
   *     upsert}
   * @param shipId the server ship; required for {@code link} and {@code remove}, and for an {@code
   *     upsert} of a ship the server knows
   * @param version the ship's version the client last saw; required with {@code shipId} on {@code
   *     upsert} and {@code remove}
   * @param shipType the ship type; required for {@code upsert}
   * @param name the member's name for the ship, or {@code null} for none
   * @param insurance the insurance; required for {@code upsert}
   * @param location the location, or {@code null} for none
   * @param fitted whether the ship is fitted, or {@code null} to keep it
   * @param override whether an {@code upsert} may bring back a ship removed elsewhere, after asking
   *     the member
   */
  @Schema(name = "ExchangeShipOp")
  public record Op(
      @Nullable @Size(min = 1, max = 64) String opId,
      @NotNull @Pattern(regexp = "^(link|upsert|remove)$") String op,
      @Nullable @Size(min = 1, max = 128) String externalId,
      @Nullable @Size(min = 1, max = 128) String shipId,
      @Nullable @Min(0) Long version,
      @Nullable @Valid ExchangeItemRef shipType,
      @Nullable @Size(min = 1, max = 255) String name,
      @Nullable @Valid Insurance insurance,
      @Nullable @Valid ExchangeLocationRef location,
      @Nullable Boolean fitted,
      @Nullable Boolean override) {}

  /**
   * A ship's insurance.
   *
   * @param kind {@code LTI} or {@code MONTHS}
   * @param months the months, required for {@code MONTHS}
   */
  public record Insurance(
      @NotNull @Pattern(regexp = "^(LTI|MONTHS)$") String kind,
      @Nullable @Min(0) @Max(120) Integer months) {}
}
