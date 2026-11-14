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

import de.greluc.krt.profit.basetool.backend.patchchange.api.PatchChangeExchangeReader;
import de.greluc.krt.profit.basetool.backend.patchchange.api.PatchChangeExchangeView;
import de.greluc.krt.profit.basetool.backend.patchchange.api.WipeGroup;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds what an exchange client reads of the patch change (REQ-PATCH-050, REQ-PATCH-051): the
 * latest patch change with every wipe group and its catalogue types, the exceptions and the further
 * options, and the member's home-location prompt with the web's own preview.
 */
@Component
@RequiredArgsConstructor
class PatchChangeExchangeViews implements PatchChangeExchangeReader {

  private final PatchChangeRepository repository;
  private final HomeLocationPrompts prompts;
  private final WipeExceptionCatalogue exceptionCatalogue;

  @Override
  @Transactional(readOnly = true)
  public @NotNull PatchChangeExchangeView viewFor(@NotNull UUID member) {
    return new PatchChangeExchangeView(
        repository.findFirstByOrderByLiveAtDesc().map(this::current).orElse(null),
        homeLocation(prompts.promptFor(member)));
  }

  /**
   * Describes the latest patch change with its options.
   *
   * @param record the patch change
   * @return its view
   */
  private @NotNull PatchChangeExchangeView.Current current(@NotNull PatchChange record) {
    PatchChangeOptions options = record.options();
    return new PatchChangeExchangeView.Current(
        record.getId(),
        record.getLabel(),
        record.getKind(),
        record.getLiveAt(),
        Arrays.stream(WipeGroup.values())
            .map(group -> wipeGroup(group, options.wipeGroups().contains(group)))
            .toList(),
        wipeExceptions(options),
        options.cancelRefineryOrders(),
        options.currencyWipe(),
        options.homeLocationPrompt());
  }

  /**
   * Describes one wipe group with its catalogue types.
   *
   * @param group the group
   * @param selected whether the patch change selected it
   * @return its view
   */
  private static @NotNull PatchChangeExchangeView.GroupEntry wipeGroup(
      @NotNull WipeGroup group, boolean selected) {
    return new PatchChangeExchangeView.GroupEntry(group, selected, WipeGroupTypes.typesOf(group));
  }

  /**
   * Names every exception as stored, ordered by name; an exception whose catalogue entry was
   * deleted since keeps its stored key, comes last and carries no name.
   *
   * @param options the patch change's options
   * @return the exceptions
   */
  private @NotNull List<PatchChangeExchangeView.ExceptionEntry> wipeExceptions(
      @NotNull PatchChangeOptions options) {
    if (options.wipeExceptions().isEmpty()) {
      return List.of();
    }
    WipeExceptionCatalogue.Names names = exceptionCatalogue.names(options.wipeExceptions());
    return options.wipeExceptions().stream()
        .map(exception ->
            new PatchChangeExchangeView.ExceptionEntry(
                exception.target(), exception.id(), names.of(exception), exception.mode()))
        .sorted(
            Comparator.comparing(
                    PatchChangeExchangeView.ExceptionEntry::name,
                    Comparator.nullsLast(Comparator.<String>naturalOrder()))
                .thenComparing(PatchChangeExchangeView.ExceptionEntry::id))
        .toList();
  }

  /**
   * Describes the member's prompt.
   *
   * @param prompt the prompt the member's own page reads
   * @return its view
   */
  private static @NotNull PatchChangeExchangeView.HomeLocation homeLocation(
      @NotNull HomeLocationPromptDto prompt) {
    return new PatchChangeExchangeView.HomeLocation(
        prompt.state(),
        prompt.patchChangeId(),
        prompt.label(),
        prompt.liveAt(),
        prompt.chosen() == null ? null : place(prompt.chosen()),
        prompt.chosenAt(),
        prompt.locations().stream().map(PatchChangeExchangeViews::place).toList(),
        prompt.preview().stream().map(PatchChangeExchangeViews::preview).toList());
  }

  /**
   * Describes a home location.
   *
   * @param option the option
   * @return the place
   */
  private static @NotNull PatchChangeExchangeView.Place place(
      @NotNull HomeLocationOptionDto option) {
    return new PatchChangeExchangeView.Place(option.id(), option.name(), option.system());
  }

  /**
   * Describes one location's preview.
   *
   * @param entry the preview the member's own page reads
   * @return its view
   */
  private static @NotNull PatchChangeExchangeView.Preview preview(
      @NotNull HomeLocationPreviewDto entry) {
    return new PatchChangeExchangeView.Preview(
        entry.locationId() == null || entry.locationName() == null
            ? null
            : new PatchChangeExchangeView.Place(entry.locationId(), entry.locationName(), null),
        entry.rows(),
        entry.pools().stream()
            .map(pool ->
                new PatchChangeExchangeView.PoolRows(
                    pool.orgUnitId(), pool.orgUnitName(), pool.rows()))
            .toList(),
        entry.ships());
  }
}
