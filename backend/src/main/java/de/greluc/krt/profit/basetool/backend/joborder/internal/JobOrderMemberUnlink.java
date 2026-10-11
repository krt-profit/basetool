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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.UserErasureParticipant;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialClaimRepository;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clears a departing member's assignee seats and claim stamps on job orders that stay, for the
 * account deletion; the claims themselves remain.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JobOrderMemberUnlink implements UserErasureParticipant {

  static final int POSITION = 20;

  private final JobOrderRepository jobOrderRepository;
  private final MaterialClaimRepository materialClaimRepository;

  @Override
  public @NotNull Phase phase() {
    return Phase.UNLINK;
  }

  @Override
  public int position() {
    return POSITION;
  }

  /**
   * Removes the member from every job order's assignees, then clears the member as the claiming
   * user of every material claim.
   *
   * @param userId the departing member's {@code app_user.id}
   * @param fallbackAdminId unused
   * @return no counts
   */
  @Override
  public @NotNull Map<Erased, Integer> erase(@NotNull UUID userId, @NotNull UUID fallbackAdminId) {
    jobOrderRepository.removeAssignee(userId);
    materialClaimRepository.unlinkClaimedByUser(userId);
    return Map.of();
  }
}
