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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangePatchChangeRefDto;
import de.greluc.krt.profit.basetool.backend.patchchange.api.PatchChangeLabels;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Names the patch change behind a feed entry's change, for the {@code REMOVED_ELSEWHERE} refusal of
 * a push against a lot, ship or org-stock entry a patch change moved or wiped, and for the {@code
 * VERSION_CONFLICT} of a push that did not see such a change yet (REQ-PATCH-053, REQ-PATCH-057).
 */
@Component
@RequiredArgsConstructor
class ExchangePatchRemovals {

  /** The change source of a patch change's writes. */
  private static final String PATCH_CHANNEL = "patch";

  private final PatchChangeLabels patchChangeLabels;
  private final ExchangeChangeRepository changeRepository;

  /**
   * Names the patch change behind an entry's latest change in the member's feed.
   *
   * @param member the member whose feed holds the entry
   * @param resource the entry's resource
   * @param key the entry's key
   * @return the patch change with its current label, or {@code null} when the latest change came
   *     from another channel or the feed holds none
   */
  @Nullable
  ExchangePatchChangeRefDto latestPatchChangeOf(
      @NotNull UUID member, @NotNull ExchangeFeedResource resource, @NotNull String key) {
    return changeRepository
        .findLatestForKey(member, resource.name(), key)
        .map(this::patchChangeOf)
        .orElse(null);
  }

  /**
   * Names the patch change behind the latest write of one shared row in its unit's org-stock feed
   * (REQ-PATCH-057).
   *
   * @param orgUnitId the unit whose pool the row lies or lay in
   * @param rowId the row
   * @return the patch change with its current label, or {@code null} when the row's latest write
   *     came from another channel or the feed names the row in none
   */
  @Nullable
  ExchangePatchChangeRefDto latestPatchChangeOfOrgEntry(
      @NotNull UUID orgUnitId, @NotNull UUID rowId) {
    return changeRepository
        .findLatestForOrgEntry(orgUnitId, rowId)
        .map(this::patchChangeOf)
        .orElse(null);
  }

  /**
   * Names the patch change that made a change.
   *
   * @param change a feed entry
   * @return the patch change with its current label, or {@code null} when the change came from
   *     another channel
   */
  @Nullable
  ExchangePatchChangeRefDto patchChangeOf(@NotNull ExchangeChange change) {
    UUID patchChange = change.getSourcePatchChange();
    if (!PATCH_CHANNEL.equals(change.getSourceChannel()) || patchChange == null) {
      return null;
    }
    return ExchangePatchChangeRefDto.of(patchChange, patchChangeLabels.labels(Set.of(patchChange)));
  }
}
