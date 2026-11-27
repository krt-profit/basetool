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

package de.greluc.krt.profit.basetool.backend.blueprint.internal;

import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.platform.api.AccountPresence;
import de.greluc.krt.profit.basetool.backend.platform.api.NameRetentionParticipant;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces the id of a deleted admin on default blueprints added more than the name retention ago
 * with {@link HandleAnonymisation#SENTINEL} (REQ-DATA-047); the defaults stay.
 */
@Component
@RequiredArgsConstructor
public class DefaultBlueprintActorNameRetention implements NameRetentionParticipant {

  private final DefaultBlueprintRepository defaultBlueprintRepository;
  private final AccountPresence accountPresence;

  @Override
  public @NotNull String area() {
    return "default_blueprints";
  }

  @Override
  @Transactional
  public int removeNamesBefore(@NotNull Instant cutoff) {
    String sentinel = HandleAnonymisation.SENTINEL;
    Map<UUID, String> creators =
        defaultBlueprintRepository.findCreatorsBefore(cutoff, sentinel).stream()
            .filter(DefaultBlueprintActorNameRetention::isUuid)
            .collect(Collectors.toMap(UUID::fromString, Function.identity()));
    Set<UUID> deleted = accountPresence.withoutAccount(creators.keySet());
    if (deleted.isEmpty()) {
      return 0;
    }
    return defaultBlueprintRepository.replaceCreatorsBefore(
        deleted.stream().map(creators::get).toList(), cutoff, sentinel);
  }

  /**
   * Tells whether a stored author id is an account id.
   *
   * @param value the stored {@code created_by}
   * @return {@code true} when it parses as a UUID
   */
  private static boolean isUuid(@NotNull String value) {
    try {
      UUID.fromString(value);
      return true;
    } catch (IllegalArgumentException _) {
      return false;
    }
  }
}
