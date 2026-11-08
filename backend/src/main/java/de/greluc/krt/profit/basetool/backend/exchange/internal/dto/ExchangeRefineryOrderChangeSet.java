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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * A client's batch of refinery-order writes, as the ingest gateway relays it after checking it
 * against {@code change-set.schema.json#/$defs/refineryOrderChangeSet} (REQ-XCH-052).
 *
 * @param ops the writes, at most 500
 * @param dryRun whether to report what would happen without writing
 */
public record ExchangeRefineryOrderChangeSet(
    @NotNull @Size(min = 1, max = 500) List<@NotNull @Valid Op> ops, boolean dryRun) {

  /** The op that creates an order. */
  public static final String CREATE = "create";

  /** The op that changes the fields it carries. */
  public static final String UPDATE = "update";

  /** The op that adds a good. */
  public static final String GOOD_ADD = "good-add";

  /** The op that changes a good. */
  public static final String GOOD_UPDATE = "good-update";

  /** The op that removes a good. */
  public static final String GOOD_REMOVE = "good-remove";

  /** The op that cancels an order. */
  public static final String CANCEL = "cancel";

  /** The op that removes an order with its goods. */
  public static final String DELETE = "delete";

  /**
   * One write. Every op but {@code create} names its order by {@code id} or {@code clientKey}; a
   * field the op's kind does not use is ignored.
   *
   * @param opId the client's id for the op, echoed in the result, or {@code null}
   * @param op {@code create}, {@code update}, {@code good-add}, {@code good-update}, {@code
   *     good-remove}, {@code cancel} or {@code delete}
   * @param id the order's Basetool id, or {@code null}
   * @param clientKey the client's own key of the order; required for a create
   * @param versions the counters of the sections the op touches, as the client last saw them
   * @param orgUnit the owning unit of a create
   * @param location the refinery, or {@code null} to leave it
   * @param method the refining method, or {@code null} to leave it
   * @param status {@code OPEN} or {@code IN_PROGRESS}, or {@code null} to leave it
   * @param endsAt when the run ends, or {@code null} to leave the timer
   * @param durationMinutes the run's length, or {@code null} to start the run at the write
   * @param expenses the refining costs, or {@code null} to leave them
   * @param otherExpenses further costs, or {@code null} to leave them
   * @param oreSales the raw-ore revenue, or {@code null} to leave it
   * @param goods the goods a create starts with, or {@code null}
   * @param good the good a {@code good-add} adds
   * @param goodId the good a {@code good-update} or {@code good-remove} names
   * @param input a {@code good-update}'s input material, or {@code null} to leave it
   * @param inputQuantity a {@code good-update}'s input amount, or {@code null} to leave it
   * @param outputQuantity a {@code good-update}'s yield, or {@code null} to leave it
   * @param quality a {@code good-update}'s quality, or {@code null} to leave it
   */
  @Schema(name = "RefineryOrderOp")
  public record Op(
      @Nullable @Size(min = 1, max = 64) String opId,
      @NotNull @Pattern(regexp = "^(create|update|good-add|good-update|good-remove|cancel|delete)$")
          String op,
      @Nullable @Size(min = 1, max = 128) String id,
      @Nullable @Size(min = 1, max = 128) String clientKey,
      @Nullable @Valid Versions versions,
      @Nullable @Valid UnitRef orgUnit,
      @Nullable @Valid ExchangeLocationRef location,
      @Nullable @Valid MethodRef method,
      @Nullable @Pattern(regexp = "^(OPEN|IN_PROGRESS)$") String status,
      @Nullable Instant endsAt,
      @Nullable @Min(0) Long durationMinutes,
      @Nullable @DecimalMin("0") @DecimalMax("1000000000") Double expenses,
      @Nullable @DecimalMin("0") @DecimalMax("1000000000") Double otherExpenses,
      @Nullable @DecimalMin("0") @DecimalMax("1000000000") Double oreSales,
      @Nullable @Size(max = 100) List<@NotNull @Valid Good> goods,
      @Nullable @Valid Good good,
      @Nullable @Size(min = 1, max = 128) String goodId,
      @Nullable @Valid ExchangeItemRef input,
      @Nullable @Min(0) Integer inputQuantity,
      @Nullable @Min(1) Integer outputQuantity,
      @Nullable @QualityValue Integer quality) {}

  /**
   * The section counters an op echoes.
   *
   * @param gameData the game-data counter, or {@code null} when the op does not touch that section
   * @param finances the finances counter, or {@code null} when the op does not touch that section
   */
  @Schema(name = "RefineryOrderOpVersions")
  public record Versions(@Nullable @Min(0) Long gameData, @Nullable @Min(0) Long finances) {}

  /**
   * The owning unit of a create.
   *
   * @param id the unit's id
   */
  @Schema(name = "RefineryOrderOpUnit")
  public record UnitRef(@NotNull @Size(min = 1, max = 128) String id) {}

  /**
   * A refining method, by code first, then by exact name.
   *
   * @param code the method's code, or {@code null}
   * @param name the method's exact name, or {@code null}
   */
  @Schema(name = "RefineryMethodRef")
  public record MethodRef(
      @Nullable @Size(min = 1, max = 255) String code,
      @Nullable @Size(min = 1, max = 200) String name) {}

  /**
   * One good.
   *
   * @param input the raw input material
   * @param inputQuantity the input amount in units, 100 to the SCU
   * @param outputQuantity the yield in units, 100 to the SCU
   * @param quality the quality from 0 to 1000
   */
  @Schema(name = "RefineryOrderOpGood")
  public record Good(
      @NotNull @Valid ExchangeItemRef input,
      @NotNull @Min(0) Integer inputQuantity,
      @NotNull @Min(1) Integer outputQuantity,
      @NotNull @QualityValue Integer quality) {}
}
