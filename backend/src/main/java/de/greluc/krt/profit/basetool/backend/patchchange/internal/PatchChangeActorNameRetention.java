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

import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.platform.api.AccountPresence;
import de.greluc.krt.profit.basetool.backend.platform.api.NameRetentionParticipant;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces the id of a deleted admin who triggered a patch change or ended its home-location phase
 * more than the name retention ago with {@link HandleAnonymisation#SENTINEL_ID} (REQ-DATA-047); the
 * patch change stays.
 */
@Component
@RequiredArgsConstructor
public class PatchChangeActorNameRetention implements NameRetentionParticipant {

  private final PatchChangeRepository patchChangeRepository;
  private final AccountPresence accountPresence;

  @Override
  public @NotNull String area() {
    return "patch_changes";
  }

  @Override
  @Transactional
  public int removeNamesBefore(@NotNull Instant cutoff) {
    UUID sentinel = HandleAnonymisation.SENTINEL_ID;
    Set<UUID> deleted =
        accountPresence.withoutAccount(patchChangeRepository.findActorsBefore(cutoff, sentinel));
    if (deleted.isEmpty()) {
      return 0;
    }
    return patchChangeRepository.replaceActorsBefore(deleted, cutoff, sentinel);
  }
}
