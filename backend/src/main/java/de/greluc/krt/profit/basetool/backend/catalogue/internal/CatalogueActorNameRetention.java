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

import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.platform.api.AccountPresence;
import de.greluc.krt.profit.basetool.backend.platform.api.NameRetentionParticipant;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces the id of a deleted admin on catalogue imports and review cases older than the name
 * retention with {@link HandleAnonymisation#SENTINEL_ID} (REQ-DATA-047); the imports and cases
 * stay.
 */
@Component
@RequiredArgsConstructor
public class CatalogueActorNameRetention implements NameRetentionParticipant {

  private final P4kImportRunRepository runRepository;
  private final P4kReviewCaseRepository reviewCaseRepository;
  private final AccountPresence accountPresence;

  @Override
  public @NotNull String area() {
    return "catalogue_acts";
  }

  @Override
  @Transactional
  public int removeNamesBefore(@NotNull Instant cutoff) {
    UUID sentinel = HandleAnonymisation.SENTINEL_ID;
    Set<UUID> actors = new HashSet<>(runRepository.findCreatorsAppliedBefore(cutoff, sentinel));
    actors.addAll(reviewCaseRepository.findResolversBefore(cutoff, sentinel));
    actors.addAll(reviewCaseRepository.findRepairersBefore(cutoff, sentinel));
    Set<UUID> deleted = accountPresence.withoutAccount(actors);
    if (deleted.isEmpty()) {
      return 0;
    }
    return runRepository.replaceCreatorsAppliedBefore(deleted, cutoff, sentinel)
        + reviewCaseRepository.replaceActorsBefore(deleted, cutoff, sentinel);
  }
}
