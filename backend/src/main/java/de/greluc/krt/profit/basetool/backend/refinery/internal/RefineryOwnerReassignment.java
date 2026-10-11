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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.platform.api.UserErasureParticipant;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Hands the refinery orders a departing member owns to the fallback admin for the deletion. */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RefineryOwnerReassignment implements UserErasureParticipant {

  static final int POSITION = 20;

  private final UserRepository userRepository;
  private final RefineryOrderRepository refineryOrderRepository;

  @Override
  public @NotNull Phase phase() {
    return Phase.REASSIGN;
  }

  @Override
  public int position() {
    return POSITION;
  }

  /**
   * Reassigns every refinery order of the member through one bulk update.
   *
   * @param userId the departing member's {@code app_user.id}
   * @param fallbackAdminId the admin that becomes the owner
   * @return the number of orders reassigned, reported as {@link Erased#REFINERY_ORDERS_REASSIGNED}
   */
  @Override
  public @NotNull Map<Erased, Integer> erase(@NotNull UUID userId, @NotNull UUID fallbackAdminId) {
    User departing = userRepository.getReferenceById(userId);
    User admin = userRepository.getReferenceById(fallbackAdminId);
    return Map.of(
        Erased.REFINERY_ORDERS_REASSIGNED, refineryOrderRepository.updateOwner(departing, admin));
  }
}
