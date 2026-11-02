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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers whether a user is an assignee of any job order, for the capability flag that opens the
 * order list to a member without profit access (REQ-ORDERS-040).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobOrderAssigneeLookup {

  private final JobOrderRepository jobOrderRepository;

  /**
   * Checks whether {@code userId} holds an assignee entry on at least one job order.
   *
   * @param userId the user to check; never {@code null}
   * @return {@code true} iff at least one entry exists
   */
  public boolean holdsAnyEntry(@NotNull UUID userId) {
    return jobOrderRepository.existsByAssigneesUserId(userId);
  }
}
