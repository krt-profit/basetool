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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCreatorRetention;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clears the creating member and key of client-created orders with one bulk update that moves no
 * version, so the clearing never collides with a web edit (REQ-OXCH-010).
 */
@Service
@RequiredArgsConstructor
class JobOrderCreatorRetentionService implements JobOrderCreatorRetention {

  private final JobOrderClientCreateRepository clientCreates;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int clearCreators(@NotNull Collection<UUID> orderIds, @NotNull Instant closedBefore) {
    if (orderIds.isEmpty()) {
      return 0;
    }
    return clientCreates.clearCreators(Set.copyOf(orderIds), closedBefore);
  }
}
