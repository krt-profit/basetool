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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import de.greluc.krt.profit.basetool.backend.identity.api.UserApprovalEventRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.NameRetentionParticipant;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Clears the free-text reason of approval decisions past the name retention (REQ-DATA-047). */
@Component
@RequiredArgsConstructor
public class ApprovalReasonRetention implements NameRetentionParticipant {

  private final UserApprovalEventRepository approvalEventRepository;

  @Override
  public @NotNull String area() {
    return "approval_reasons";
  }

  @Override
  @Transactional
  public int removeNamesBefore(@NotNull Instant cutoff) {
    return approvalEventRepository.clearReasonsBefore(cutoff);
  }
}
