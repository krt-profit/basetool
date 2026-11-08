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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides whether a P4K import is due (REQ-OBS-022, REQ-P4K-047): the UEX or the SC Wiki game
 * version is newer than the patch version of the last APPLY whose version is known, or no such
 * APPLY exists. Versions only; there is no age rule.
 */
@Service
@RequiredArgsConstructor
public class P4kImportDueService {

  /** The SC Wiki attempt statuses that mean the whole source sweep applied. */
  private static final List<SyncRunStatus> COMPLETE =
      List.of(SyncRunStatus.SUCCESS, SyncRunStatus.UNCHANGED);

  private final P4kImportRunRepository runRepository;
  private final ExternalSyncRunRepository syncRunRepository;
  private final GameItemRepository gameItemRepository;

  /**
   * Evaluates the signal against the current runs and sync results.
   *
   * @return the decision and the versions it compared
   */
  @Transactional(readOnly = true)
  @NotNull
  public P4kImportDueDto evaluate() {
    P4kGameVersion uex = P4kGameVersion.highest(gameItemRepository.findDistinctUexGameVersions());
    P4kGameVersion wiki = P4kGameVersion.parse(lastCompleteWikiVersion());
    P4kImportRun basisRun =
        runRepository.findFirstByPatchVersionIsNotNullOrderByAppliedAtDesc().orElse(null);
    P4kGameVersion basis =
        basisRun == null ? null : P4kGameVersion.parse(basisRun.getPatchVersion());
    if (basisRun == null || basis == null) {
      return new P4kImportDueDto(true, text(uex), text(wiki), null, null);
    }
    return new P4kImportDueDto(
        newer(uex, basis) || newer(wiki, basis),
        text(uex),
        text(wiki),
        basis.toString(),
        basisRun.getPatchVersionOrigin());
  }

  /**
   * Reads the game version of the newest complete SC Wiki source sweep.
   *
   * @return its version text, or {@code null} without such a sweep
   */
  @Nullable
  private String lastCompleteWikiVersion() {
    return syncRunRepository
        .findBySourceSystemAndAggregateAndStatusInOrderByFinishedAtDesc(
            SyncSourceSystem.SCWIKI, SyncAggregate.CATALOGUE, COMPLETE, PageRequest.of(0, 1))
        .stream()
        .findFirst()
        .map(ExternalSyncRun::getGameVersion)
        .orElse(null);
  }

  private static boolean newer(@Nullable P4kGameVersion source, @NotNull P4kGameVersion basis) {
    return source != null && source.compareTo(basis) > 0;
  }

  @Nullable
  private static String text(@Nullable P4kGameVersion version) {
    return version == null ? null : version.toString();
  }
}
