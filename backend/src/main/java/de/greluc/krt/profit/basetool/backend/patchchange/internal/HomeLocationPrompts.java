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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRelocation;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRelocationCount;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRelocation;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRelocationCount;
import de.greluc.krt.profit.basetool.backend.patchchange.api.MemberRelocationState;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a member's home-location prompt (REQ-PATCH-021, REQ-PATCH-022): the member's state in the
 * latest home-location phase, the curated home locations and, while the member is pending, what the
 * relocation would move from each current location.
 */
@Service
@RequiredArgsConstructor
public class HomeLocationPrompts {

  private final PatchChangeRepository repository;
  private final PatchChangeRelocationRepository relocations;
  private final HomeLocationPhases phases;
  private final HomeLocationOffer offer;
  private final LocationRepository locations;
  private final StockRelocation stock;
  private final ShipRelocation ships;

  /**
   * Reads a member's prompt.
   *
   * @param member the member
   * @return the prompt
   */
  @Transactional(readOnly = true)
  public @NotNull HomeLocationPromptDto promptFor(@NotNull UUID member) {
    List<HomeLocationOptionDto> offered = offer.offered();
    Optional<PatchChange> latest = repository.findFirstByHomeLocationPromptTrueOrderByLiveAtDesc();
    if (latest.isEmpty()) {
      return new HomeLocationPromptDto(
          MemberRelocationState.NOT_ASKED, null, null, null, null, null, offered, List.of());
    }
    PatchChange record = latest.get();
    HomeLocationPhase phase = HomeLocationPhase.of(record);
    Optional<PatchChangeRelocation> done =
        relocations.findById(new PatchChangeRelocation.Key(record.getId(), member));
    if (done.isPresent()) {
      return prompt(
          MemberRelocationState.RELOCATED,
          phase,
          chosen(done.get().getHomeLocationId()),
          done.get(),
          offered,
          List.of());
    }
    if (phases.pendingAmong(phase, List.of(member)).isEmpty()) {
      return prompt(MemberRelocationState.NOT_ASKED, phase, null, null, offered, List.of());
    }
    if (record.getPhaseEndedAt() != null) {
      return prompt(MemberRelocationState.PHASE_ENDED, phase, null, null, offered, List.of());
    }
    return prompt(
        MemberRelocationState.PENDING, phase, null, null, offered, preview(member, phase));
  }

  /**
   * Builds a prompt of a phase.
   *
   * @param state the member's state
   * @param phase the phase
   * @param chosen the chosen location, or {@code null}
   * @param done the member's relocation, or {@code null}
   * @param offered the locations on offer
   * @param preview what moves
   * @return the prompt
   */
  private static @NotNull HomeLocationPromptDto prompt(
      @NotNull MemberRelocationState state,
      @NotNull HomeLocationPhase phase,
      @Nullable HomeLocationOptionDto chosen,
      @Nullable PatchChangeRelocation done,
      @NotNull List<HomeLocationOptionDto> offered,
      @NotNull List<HomeLocationPreviewDto> preview) {
    return new HomeLocationPromptDto(
        state,
        phase.id(),
        phase.label(),
        phase.liveAt(),
        chosen,
        done == null ? null : done.getRelocatedAt(),
        offered,
        preview);
  }

  /**
   * Resolves the chosen location.
   *
   * @param locationId the stored choice, or {@code null} once the location was deleted
   * @return the option, or {@code null}
   */
  private @Nullable HomeLocationOptionDto chosen(@Nullable UUID locationId) {
    if (locationId == null) {
      return null;
    }
    return locations.findById(locationId).map(HomeLocationOffer::toOption).orElse(null);
  }

  /**
   * Merges the stock and ship counts of a member's relocation per current location.
   *
   * @param member the member
   * @param phase the open phase
   * @return one entry per location, sorted by name, ships without a location last
   */
  private @NotNull List<HomeLocationPreviewDto> preview(
      @NotNull UUID member, @NotNull HomeLocationPhase phase) {
    Map<UUID, PreviewBuilder> byLocation = new LinkedHashMap<>();
    for (StockRelocationCount count : stock.countByLocation(member, phase.liveAt())) {
      PreviewBuilder entry =
          byLocation.computeIfAbsent(
              count.locationId(),
              _ -> new PreviewBuilder(count.locationId(), count.locationName()));
      entry.rows += count.rows();
      if (count.orgUnitId() != null && count.orgUnitName() != null) {
        entry.pools.add(
            new HomeLocationPoolRowsDto(count.orgUnitId(), count.orgUnitName(), count.rows()));
      }
    }
    for (ShipRelocationCount count : ships.countByLocation(member, phase.liveAt())) {
      byLocation.computeIfAbsent(
                  count.locationId(),
                  _ -> new PreviewBuilder(count.locationId(), count.locationName()))
              .ships +=
          count.ships();
    }
    Collator collator = Collator.getInstance(Locale.ROOT);
    return byLocation.values().stream()
        .map(PreviewBuilder::build)
        .sorted(
            Comparator.comparing(
                HomeLocationPreviewDto::locationName, Comparator.nullsLast(collator::compare)))
        .toList();
  }

  /** Collects one location's preview. */
  @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
  private static final class PreviewBuilder {

    /** The location, or {@code null} for ships without one. */
    private final @Nullable UUID locationId;

    /** The location's name, or {@code null} for ships without one. */
    private final @Nullable String locationName;

    private final List<HomeLocationPoolRowsDto> pools = new ArrayList<>();
    private long rows;
    private long ships;

    /**
     * Freezes the preview.
     *
     * @return the preview
     */
    private @NotNull HomeLocationPreviewDto build() {
      return new HomeLocationPreviewDto(locationId, locationName, rows, List.copyOf(pools), ships);
    }
  }
}
