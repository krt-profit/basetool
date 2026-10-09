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

package de.greluc.krt.profit.basetool.backend.promotion.internal;

import de.greluc.krt.profit.basetool.backend.service.MemberEvaluationErasure;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Deletes a departing member's grades for the GDPR user deletion; never opens a transaction. */
@Component
@RequiredArgsConstructor
public class MemberEvaluationErasureService implements MemberEvaluationErasure {

  private final MemberEvaluationRepository memberEvaluationRepository;

  /**
   * Deletes every grade of the member through one bulk delete; requires the caller's transaction.
   *
   * @param userId the departing member's {@code app_user.id}
   * @return the number of grades removed
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int deleteAllEvaluationsOf(@NotNull UUID userId) {
    return memberEvaluationRepository.deleteAllByUserId(userId);
  }
}
