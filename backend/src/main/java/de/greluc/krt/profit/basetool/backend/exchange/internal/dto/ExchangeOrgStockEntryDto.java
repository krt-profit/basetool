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

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * One holder's shared Lager row in an org-stock lot, as the web's entry row shows it to a
 * Logistician of the pool (REQ-XCH-043); absent optional fields are left out.
 *
 * @param id the row id
 * @param version the row's optimistic-lock version
 * @param holder the holder, named or withheld
 * @param quantity the row's amount in the lot's unit
 * @param note the row's note, or {@code null} when it has none or the holder withholds their name
 * @param jobOrders the row's job-order earmarks
 * @param missions the row's mission earmarks
 * @param free the part of the row not earmarked, per dimension
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeOrgStockEntryDto(
    @NotNull String id,
    long version,
    @NotNull Holder holder,
    @NotNull ExchangeQuantityDto quantity,
    @Nullable String note,
    @NotNull @Unmodifiable List<JobOrderEarmark> jobOrders,
    @NotNull @Unmodifiable List<MissionEarmark> missions,
    @NotNull ExchangeEarmarkedDto free) {

  /**
   * The holder of a row: the name the web shows and a per-client pseudonym, or neither when the
   * holder withholds their name (REQ-XCH-044).
   *
   * @param withheld whether the holder withholds their name
   * @param name the shown name, or {@code null} when withheld
   * @param ref the per-client pseudonym, or {@code null} when withheld
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Holder(boolean withheld, @Nullable String name, @Nullable String ref) {

    /** The holder of a member who withholds their name. */
    public static final Holder WITHHELD = new Holder(true, null, null);
  }

  /**
   * One job-order earmark of a row.
   *
   * @param id the order id
   * @param number the order's number, or {@code null} when the order is unknown
   * @param quantity the earmarked amount in the lot's unit
   * @param delivered whether the earmarked amount was delivered to the order
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record JobOrderEarmark(
      @NotNull String id,
      @Nullable Integer number,
      @NotNull ExchangeQuantityDto quantity,
      boolean delivered) {}

  /**
   * One mission earmark of a row.
   *
   * @param id the mission id
   * @param name the mission's name, or {@code null} when the mission is unknown
   * @param quantity the earmarked amount in the lot's unit
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record MissionEarmark(
      @NotNull String id, @Nullable String name, @NotNull ExchangeQuantityDto quantity) {}
}
