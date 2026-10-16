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
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A client's batch of org-stock writes, as the ingest gateway relays it after checking it against
 * {@code change-set.schema.json#/$defs/orgStockChangeSet} (REQ-XCH-045).
 *
 * @param ops the writes, at most 500
 * @param dryRun whether to report what would happen without writing
 */
public record ExchangeOrgStockChangeSet(
    @NotNull @Size(min = 1, max = 500) List<@NotNull @Valid Op> ops, boolean dryRun) {

  /** The op that books stock into a pool. */
  public static final String BOOK_IN = "book-in";

  /** The op that books stock out of an entry. */
  public static final String BOOK_OUT = "book-out";

  /** The op that sells stock of an entry. */
  public static final String SELL = "sell";

  /** The op that moves stock of an entry to another location. */
  public static final String MOVE_LOCATION = "move-location";

  /** The op that moves stock of an entry to another holder. */
  public static final String MOVE_HOLDER = "move-holder";

  /** The op that moves stock of an entry into another pool of its holder. */
  public static final String MOVE_POOL = "move-pool";

  /** The op that sets or clears an entry's note. */
  public static final String NOTE = "note";

  /** The op that sets or removes the stolen marker of an entry or a part of it. */
  public static final String STOLEN = "stolen";

  /**
   * One write on a pool; the gateway's schema decides which fields each op carries.
   *
   * @param opId the client's id for the op, echoed in the result, or {@code null}
   * @param op {@code book-in}, {@code book-out}, {@code sell}, {@code move-location}, {@code
   *     move-holder}, {@code move-pool}, {@code note} or {@code stolen}
   * @param unit the org unit whose pool the op writes
   * @param holder a {@code book-in}'s holder or a {@code move-holder}'s new holder, a {@code ref}
   *     from the roster of the receiving unit
   * @param material a {@code book-in}'s material or item
   * @param location a {@code book-in}'s Lager location or a {@code move-location}'s new one
   * @param quality a {@code book-in}'s quality, ignored for an item
   * @param stolen whether a {@code book-in}'s stock is marked stolen, or the marker a {@code
   *     stolen} op sets
   * @param quantity the amount the op books, sells, moves or marks; absent on a {@code note} and on
   *     a {@code stolen} op that marks the whole entry
   * @param note a {@code book-in}'s note, or a {@code note} op's new note; {@code null} for none
   * @param mergeScu whether a {@code book-in} or a move of an SCU material merges into the
   *     receiving holder's entry
   * @param jobOrders a {@code book-in}'s job-order earmarks, or {@code null}
   * @param missions a {@code book-in}'s mission earmarks, or {@code null}
   * @param entry the entry every op but {@code book-in} writes
   * @param version the entry's version the client last read
   * @param fromJobOrders how much of a book-out, sale or move comes out of each job-order earmark
   * @param fromMissions how much of a book-out, sale or move comes out of each mission earmark
   * @param terminal a {@code sell}'s terminal
   * @param proceeds the aUEC a {@code sell} brought in
   * @param toUnit the unit whose pool a {@code move-holder} or {@code move-pool} moves into
   */
  @Schema(name = "OrgStockOp")
  public record Op(
      @Nullable @Size(min = 1, max = 64) String opId,
      @NotNull
          @Pattern(
              regexp = "^(book-in|book-out|sell|move-location|move-holder|move-pool|note|stolen)$")
          String op,
      @NotNull UUID unit,
      @Nullable @Size(min = 1, max = 128) String holder,
      @Nullable @Valid ExchangeItemRef material,
      @Nullable @Valid ExchangeLocationRef location,
      @Nullable @QualityValue Integer quality,
      @Nullable Boolean stolen,
      @Nullable @Valid ExchangeStockChangeSet.Quantity quantity,
      @Nullable @Size(max = 1000) String note,
      @Nullable Boolean mergeScu,
      @Nullable @Size(max = 100) List<@NotNull @Valid JobOrderLine> jobOrders,
      @Nullable @Size(max = 100) List<@NotNull @Valid MissionLine> missions,
      @Nullable UUID entry,
      @Nullable @Min(0) Long version,
      @Nullable @Size(max = 100) List<@NotNull @Valid JobOrderLine> fromJobOrders,
      @Nullable @Size(max = 100) List<@NotNull @Valid MissionLine> fromMissions,
      @Nullable @Size(min = 1, max = 120) String terminal,
      @Nullable @DecimalMin("0") @DecimalMax("999999999999999") BigDecimal proceeds,
      @Nullable UUID toUnit) {}

  /**
   * An amount for one job order.
   *
   * @param jobOrder the job order
   * @param quantity the amount, in the lot's unit
   */
  @Schema(name = "OrgStockJobOrderLine")
  public record JobOrderLine(
      @NotNull UUID jobOrder, @NotNull @Valid ExchangeStockChangeSet.Quantity quantity) {}

  /**
   * An amount for one mission.
   *
   * @param mission the mission
   * @param quantity the amount, in the lot's unit
   */
  @Schema(name = "OrgStockMissionLine")
  public record MissionLine(
      @NotNull UUID mission, @NotNull @Valid ExchangeStockChangeSet.Quantity quantity) {}
}
