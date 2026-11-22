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

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialExternalAliasRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.UserErasureParticipant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes a departing account's id from the material aliases it created, for the account deletion;
 * the aliases keep resolving (REQ-DATA-008).
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class MaterialAliasAuthorUnlink implements UserErasureParticipant {

  static final int POSITION = 40;

  private final MaterialExternalAliasRepository aliasRepository;

  @Override
  public @NotNull Phase phase() {
    return Phase.UNLINK;
  }

  @Override
  public int position() {
    return POSITION;
  }

  /**
   * Clears the author stamp on the account's aliases.
   *
   * @param userId the departing account's {@code app_user.id}
   * @param fallbackAdminId unused
   * @return no counts
   */
  @Override
  public @NotNull Map<Erased, Integer> erase(@NotNull UUID userId, @NotNull UUID fallbackAdminId) {
    aliasRepository.clearAuthor(userId.toString());
    return Map.of();
  }
}
