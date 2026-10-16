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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

  /**
   * One write on a pool; the gateway's schema decides which fields each op carries.
   *
   * @param opId the client's id for the op, echoed in the result, or {@code null}
   * @param op {@code book-in} or {@code book-out}
   * @param unit the org unit whose pool the op writes
   * @param holder a {@code book-in}'s holder, a {@code ref} from the unit's roster
   * @param material a {@code book-in}'s material or item
   * @param location a {@code book-in}'s Lager location
   * @param quality a {@code book-in}'s quality, ignored for an item
   * @param stolen whether a {@code book-in}'s stock is marked stolen
   * @param quantity the amount booked in or out
   * @param note a {@code book-in}'s note, or {@code null}
   * @param mergeScu whether a {@code book-in} of an SCU material merges into the holder's entry
   * @param jobOrders a {@code book-in}'s job-order earmarks, or {@code null}
   * @param missions a {@code book-in}'s mission earmarks, or {@code null}
   * @param entry a {@code book-out}'s entry
   * @param version the entry's version the client last read
   * @param fromJobOrders how much of a {@code book-out} comes out of each job-order earmark
   * @param fromMissions how much of a {@code book-out} comes out of each mission earmark
   */
  @Schema(name = "OrgStockOp")
  public record Op(
      @Nullable @Size(min = 1, max = 64) String opId,
      @NotNull @Pattern(regexp = "^(book-in|book-out)$") String op,
      @NotNull UUID unit,
      @Nullable @Size(min = 1, max = 128) String holder,
      @Nullable @Valid ExchangeItemRef material,
      @Nullable @Valid ExchangeLocationRef location,
      @Nullable @QualityValue Integer quality,
      @Nullable Boolean stolen,
      @NotNull @Valid ExchangeStockChangeSet.Quantity quantity,
      @Nullable @Size(max = 1000) String note,
      @Nullable Boolean mergeScu,
      @Nullable @Size(max = 100) List<@NotNull @Valid JobOrderLine> jobOrders,
      @Nullable @Size(max = 100) List<@NotNull @Valid MissionLine> missions,
      @Nullable UUID entry,
      @Nullable @Min(0) Long version,
      @Nullable @Size(max = 100) List<@NotNull @Valid JobOrderLine> fromJobOrders,
      @Nullable @Size(max = 100) List<@NotNull @Valid MissionLine> fromMissions) {}

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
