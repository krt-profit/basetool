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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.platform.api.UserErasureParticipant;
import de.greluc.krt.profit.basetool.backend.repository.MissionOwnershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands the missions a departing member owns, and their {@code mission_ownership} companions, to
 * the fallback admin for the account deletion.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class MissionOwnerReassignment implements UserErasureParticipant {

  static final int POSITION = 10;

  private final UserRepository userRepository;
  private final MissionRepository missionRepository;
  private final MissionOwnershipRepository missionOwnershipRepository;

  @Override
  public @NotNull Phase phase() {
    return Phase.REASSIGN;
  }

  @Override
  public int position() {
    return POSITION;
  }

  /**
   * Reassigns the member's missions, then their ownership companions, through two bulk updates.
   *
   * @param userId the departing member's {@code app_user.id}
   * @param fallbackAdminId the admin that becomes the owner
   * @return no counts
   */
  @Override
  public @NotNull Map<Erased, Integer> erase(@NotNull UUID userId, @NotNull UUID fallbackAdminId) {
    User departing = userRepository.getReferenceById(userId);
    User admin = userRepository.getReferenceById(fallbackAdminId);
    missionRepository.updateOwner(departing, admin);
    missionOwnershipRepository.updateOwner(departing, admin);
    return Map.of();
  }
}
