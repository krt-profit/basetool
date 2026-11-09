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
import lombok.RequiredArgsConstructor;
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

  /**
   * Whether an op of the set books stock: a pickup with store lines, which needs {@code
   * exchange.refinery.store} (REQ-XCH-053).
   *
   * @return {@code true} when a pickup carries a store line
   */
  public boolean storesStock() {
    return ops.stream()
        .anyMatch(op -> op.kind() == Kind.PICKUP && op.lines() != null && !op.lines().isEmpty());
  }

  /**
   * One write. Every op but {@code create} names its order by {@code id} or {@code clientKey}; a
   * field the op's kind does not use is ignored.
   *
   * @param opId the client's id for the op, echoed in the result, or {@code null}
   * @param op {@code create}, {@code update}, {@code good-add}, {@code good-update}, {@code
   *     good-remove}, {@code cancel}, {@code delete} or {@code pickup}
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
   * @param lines a {@code pickup}'s store lines, or {@code null} to book nothing
   */
  @Schema(name = "RefineryOrderOp")
  public record Op(
      @Nullable @Size(min = 1, max = 64) String opId,
      @NotNull
          @Pattern(
              regexp = "^(create|update|good-add|good-update|good-remove|cancel|delete|pickup)$")
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
      @Nullable @QualityValue Integer quality,
      @Nullable @Size(min = 1, max = 100) List<@NotNull @Valid StoreLine> lines) {

    /**
     * The op's kind.
     *
     * @return the kind {@link #op} names
     * @throws IllegalArgumentException when it names none; the gateway's schema and the pattern
     *     keep such an op from arriving
     */
    public @org.jetbrains.annotations.NotNull Kind kind() {
      return Kind.of(op);
    }
  }

  /** The kinds of refinery-order op, each with the name a client sends. */
  @RequiredArgsConstructor
  public enum Kind {
    /** Creates an order. */
    CREATE("create"),
    /** Changes the fields it carries. */
    UPDATE("update"),
    /** Adds a good. */
    GOOD_ADD("good-add"),
    /** Changes a good. */
    GOOD_UPDATE("good-update"),
    /** Removes a good. */
    GOOD_REMOVE("good-remove"),
    /** Cancels an order. */
    CANCEL("cancel"),
    /** Removes an order with its goods. */
    DELETE("delete"),
    /** Closes an order as collected in the game, booking stock only with store lines. */
    PICKUP("pickup");

    /** The name a client sends in {@link Op#op}. */
    private final String wire;

    /**
     * Reads a kind by the name a client sends.
     *
     * @param wire the name
     * @return the kind
     * @throws IllegalArgumentException when no kind has that name
     */
    public static @org.jetbrains.annotations.NotNull Kind of(@Nullable String wire) {
      for (Kind kind : values()) {
        if (kind.wire.equals(wire)) {
          return kind;
        }
      }
      throw new IllegalArgumentException("Unknown refinery-order op");
    }
  }

  /**
   * One store line of a pickup, as the web's store dialog has it (REQ-XCH-053).
   *
   * @param material the output material
   * @param location where the stock is booked
   * @param quality the quality from 0 to 1000
   * @param quantity the amount in the material's unit
   * @param receiver the receiving member's ref, or {@code null} for the member
   * @param orgUnit the owning unit of the booked stock, or {@code null} for the receiver's only one
   * @param personal whether the stock is the receiver's private stock
   * @param jobOrder the job order the stock is earmarked to, or {@code null}
   * @param note the note, or {@code null}
   */
  @Schema(name = "RefineryStoreLine")
  public record StoreLine(
      @NotNull @Valid ExchangeItemRef material,
      @NotNull @Valid ExchangeLocationRef location,
      @NotNull @QualityValue Integer quality,
      @NotNull @Valid ExchangeStockChangeSet.Quantity quantity,
      @Nullable @Size(min = 1, max = 128) String receiver,
      @Nullable @Valid UnitRef orgUnit,
      @Nullable Boolean personal,
      @Nullable @Size(min = 1, max = 128) String jobOrder,
      @Nullable @Size(max = 1000) String note) {}

  /**
   * The section counters an op echoes.
   *
   * @param gameData the game-data counter, or {@code null} when the op does not touch that section
   * @param finances the finances counter, or {@code null} when the op does not touch that section
   */
  @Schema(name = "RefineryOrderOpVersions")
  public record Versions(@Nullable @Min(0) Long gameData, @Nullable @Min(0) Long finances) {}

  /**
   * The owning unit of a create or of a store line's stock.
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
