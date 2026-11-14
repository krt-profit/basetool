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

package de.greluc.krt.profit.basetool.backend.patchchange.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * What an exchange client reads of the patch change for its member (REQ-PATCH-050): the latest
 * patch change with its options, and the member's state in the latest home-location phase.
 *
 * @param current the latest patch change, or {@code null} before the first
 * @param homeLocation the member's home-location prompt
 */
public record PatchChangeExchangeView(
    @Nullable Current current, @NotNull HomeLocation homeLocation) {

  /**
   * The latest patch change and its options as triggered (REQ-PATCH-002).
   *
   * @param id the patch change
   * @param label its current label
   * @param kind the patch kind
   * @param liveAt the live time, the cutoff of every effect
   * @param wipeGroups every wipe group in declaration order, with its catalogue types and whether
   *     the patch change selected it
   * @param wipeExceptions every wipe exception as stored, also one whose catalogue entry was deleted
   * @param cancelRefineryOrders whether running refinery orders started before the live time ended
   * @param currencyWipe whether the currency wipe ran
   * @param homeLocationPrompt whether the members were asked for a new home location
   */
  public record Current(
      @NotNull UUID id,
      @NotNull String label,
      @NotNull PatchKind kind,
      @NotNull Instant liveAt,
      @NotNull @Unmodifiable List<GroupEntry> wipeGroups,
      @NotNull @Unmodifiable List<ExceptionEntry> wipeExceptions,
      boolean cancelRefineryOrders,
      boolean currencyWipe,
      boolean homeLocationPrompt) {}

  /**
   * One wipe group (REQ-PATCH-010).
   *
   * @param group the group
   * @param selected whether the patch change wiped it
   * @param types the catalogue types it claims, none for the other items
   */
  public record GroupEntry(
      @NotNull WipeGroup group,
      boolean selected,
      @NotNull @Unmodifiable List<WipeGroupType> types) {}

  /**
   * One wipe exception (REQ-PATCH-011).
   *
   * @param target the catalogue the entry belongs to
   * @param id the game item's or material's id, the exception's stored key
   * @param name its current catalogue name, or {@code null} once the entry was deleted
   * @param mode whether the entry was kept or wiped
   */
  public record ExceptionEntry(
      @NotNull WipeExceptionTarget target,
      @NotNull UUID id,
      @Nullable String name,
      @NotNull WipeExceptionMode mode) {}

  /**
   * The member's state in the latest home-location phase (REQ-PATCH-021, REQ-PATCH-022).
   *
   * @param state the member's state in the phase
   * @param patchChangeId the phase's patch change, or {@code null} when no phase ever asked
   * @param label its label, or {@code null} when no phase ever asked
   * @param liveAt its live time, or {@code null} when no phase ever asked
   * @param chosen the chosen home location, or {@code null} before the choice or once deleted
   * @param chosenAt when the member chose and relocated, or {@code null} before the choice
   * @param offered the curated home locations on offer, by name
   * @param preview what the relocation would move per current location; empty unless pending
   */
  public record HomeLocation(
      @NotNull MemberRelocationState state,
      @Nullable UUID patchChangeId,
      @Nullable String label,
      @Nullable Instant liveAt,
      @Nullable Place chosen,
      @Nullable Instant chosenAt,
      @NotNull @Unmodifiable List<Place> offered,
      @NotNull @Unmodifiable List<Preview> preview) {}

  /**
   * A location as the prompt names it.
   *
   * @param id the location
   * @param name its curated name
   * @param system the star system of its city or station, or {@code null} when unknown
   */
  public record Place(@NotNull UUID id, @NotNull String name, @Nullable String system) {}

  /**
   * What the relocation would move from one current location (REQ-PATCH-021).
   *
   * @param location the location, or {@code null} for ships without one
   * @param rows the stock rows there from before the live time, own and pool rows together
   * @param pools the share of those rows held for each org unit's pool
   * @param ships the ships there from before the live time
   */
  public record Preview(
      @Nullable Place location,
      long rows,
      @NotNull @Unmodifiable List<PoolRows> pools,
      long ships) {}

  /**
   * How many of a location's rows the member holds for one org unit's pool.
   *
   * @param orgUnitId the org unit
   * @param orgUnitName its name
   * @param rows how many rows
   */
  public record PoolRows(@NotNull UUID orgUnitId, @NotNull String orgUnitName, long rows) {}
}
