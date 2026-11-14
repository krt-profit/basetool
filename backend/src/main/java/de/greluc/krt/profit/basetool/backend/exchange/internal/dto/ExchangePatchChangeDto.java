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
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The patch change as an exchange client reads it for its member, in the shape of the published
 * {@code patch-change.schema.json} (REQ-PATCH-050, REQ-PATCH-051).
 *
 * @param patchChange the latest patch change, or {@code null} before the first
 * @param homeLocation the member's state in the latest home-location phase
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangePatchChangeDto(
    @Nullable Current patchChange, @NotNull HomeLocation homeLocation) {

  /**
   * The latest patch change.
   *
   * @param id its id, the same the feeds name in every entry it caused
   * @param label its current label, e.g. {@code 4.4}
   * @param kind {@code REBUILD} or {@code FULL_WIPE}
   * @param liveAt the live time; only what was created or started before it was touched
   * @param options its options as triggered
   */
  @Schema(name = "ExchangePatchChange")
  public record Current(
      @NotNull UUID id,
      @NotNull String label,
      @NotNull String kind,
      @NotNull Instant liveAt,
      @NotNull Options options) {}

  /**
   * The options of a patch change.
   *
   * @param wipeGroups every wipe group with its catalogue types and whether it was wiped
   * @param wipeExceptions the single items and materials kept or wiped beside the groups
   * @param cancelRefineryOrders whether running refinery orders started before the live time ended
   * @param currencyWipe whether the currency wipe ran
   * @param homeLocationPrompt whether the members were asked for a new home location
   */
  @Schema(name = "ExchangePatchChangeOptions")
  public record Options(
      @NotNull @Unmodifiable List<GroupEntry> wipeGroups,
      @NotNull @Unmodifiable List<ExceptionEntry> wipeExceptions,
      boolean cancelRefineryOrders,
      boolean currencyWipe,
      boolean homeLocationPrompt) {}

  /**
   * One wipe group.
   *
   * @param group the group, e.g. {@code CONSUMABLES}
   * @param selected whether the patch change wiped it
   * @param types the catalogue types it claims; none for {@code OTHER_ITEMS}
   */
  @Schema(name = "ExchangePatchWipeGroup")
  public record GroupEntry(
      @NotNull String group, boolean selected, @NotNull @Unmodifiable List<TypeKey> types) {}

  /**
   * One catalogue type a wipe group claims.
   *
   * @param source the catalogue fact compared, e.g. {@code ITEM_TYPE}
   * @param value the type key, e.g. {@code Food}
   */
  @Schema(name = "ExchangePatchWipeGroupType")
  public record TypeKey(@NotNull String source, @NotNull String value) {}

  /**
   * One wipe exception.
   *
   * @param entry the item or material, named as the stock feed names it
   * @param target {@code GAME_ITEM} or {@code MATERIAL}
   * @param mode {@code KEEP} or {@code WIPE}
   */
  @Schema(name = "ExchangePatchWipeException")
  public record ExceptionEntry(
      @NotNull ExchangeItemRefDto entry, @NotNull String target, @NotNull String mode) {}

  /**
   * The member's state in the latest home-location phase.
   *
   * @param state {@code NOT_ASKED}, {@code PENDING}, {@code RELOCATED} or {@code PHASE_ENDED}
   * @param patchChange the phase's patch change, or {@code null} when no phase ever asked
   * @param liveAt the phase's live time, or {@code null} when no phase ever asked
   * @param chosen the chosen home location, or {@code null} before the choice or once deleted
   * @param chosenAt when the member chose and relocated, or {@code null} before the choice
   * @param locations the curated home locations on offer, by name
   * @param preview what the relocation would move per current location; empty unless pending
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @Schema(name = "ExchangeHomeLocationPrompt")
  public record HomeLocation(
      @NotNull String state,
      @Nullable ExchangePatchChangeRefDto patchChange,
      @Nullable Instant liveAt,
      @Nullable ExchangeHomeLocationDto chosen,
      @Nullable Instant chosenAt,
      @NotNull @Unmodifiable List<ExchangeHomeLocationDto> locations,
      @NotNull @Unmodifiable List<Preview> preview) {}

  /**
   * What the relocation would move from one current location.
   *
   * @param location the location, or {@code null} for ships without one
   * @param rows the stock rows there from before the live time, own and pool rows together
   * @param pools the share of those rows held for each org unit's pool
   * @param ships the ships there from before the live time
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @Schema(name = "ExchangeHomeLocationPreview")
  public record Preview(
      @Nullable ExchangeLocationDto location,
      long rows,
      @NotNull @Unmodifiable List<PoolRows> pools,
      long ships) {}

  /**
   * How many of a location's rows the member holds for one org unit's pool.
   *
   * @param orgUnit the org unit
   * @param rows how many rows
   */
  @Schema(name = "ExchangeHomeLocationPoolRows")
  public record PoolRows(@NotNull OrgUnit orgUnit, long rows) {}

  /**
   * The org unit a pool belongs to.
   *
   * @param id its id
   * @param name its display name
   */
  @Schema(name = "ExchangeHomeLocationPoolUnit")
  public record OrgUnit(@NotNull UUID id, @NotNull String name) {}
}
