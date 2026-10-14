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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.UserErasureParticipant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Detaches a departing member from their donations (REQ-SUP-020): the donations stay for the books
 * without a donor, and a pledge that was never received is withdrawn, because its Lager row goes
 * with the member.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class SupplyUserErasure implements UserErasureParticipant {

  static final int POSITION = 80;

  private final SupplyDonationRepository donationRepository;

  @Override
  public @NotNull Phase phase() {
    return Phase.UNLINK;
  }

  @Override
  public int position() {
    return POSITION;
  }

  /**
   * Withdraws the member's open pledges and clears the donor of every donation.
   *
   * @param userId the departing member's {@code app_user.id}
   * @param fallbackAdminId unused
   * @return the number of donations detached, reported as {@link Erased#SUPPLY_DONATIONS_DETACHED}
   */
  @Override
  public @NotNull Map<Erased, Integer> erase(@NotNull UUID userId, @NotNull UUID fallbackAdminId) {
    donationRepository.withdrawOpenPledgesOf(userId);
    return Map.of(Erased.SUPPLY_DONATIONS_DETACHED, donationRepository.detachDonor(userId));
  }
}
