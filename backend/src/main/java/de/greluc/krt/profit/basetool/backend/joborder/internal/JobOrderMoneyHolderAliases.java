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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Keeps the original holder references pointing directly to their current survivor. */
@Component
@RequiredArgsConstructor
class JobOrderMoneyHolderAliases {

  private final JobOrderMoneyHolderRepository holders;

  /** Canonical holders to unite under the order write guard. */
  record Pair(JobOrderMoneyHolder source, JobOrderMoneyHolder survivor) {}

  void merge(List<Pair> pairs) {
    if (pairs.isEmpty()) {
      return;
    }
    Map<UUID, UUID> targets =
        pairs.stream()
            .collect(
                Collectors.toMap(
                    pair -> Objects.requireNonNull(pair.source().getId()),
                    pair -> Objects.requireNonNull(pair.survivor().getId())));
    for (JobOrderMoneyHolder alias : holders.findAllByMergedIntoIdIn(targets.keySet())) {
      alias.retireInto(Objects.requireNonNull(targets.get(alias.getMergedIntoId())));
    }
    for (Pair pair : pairs) {
      pair.source().retireInto(Objects.requireNonNull(pair.survivor().getId()));
      pair.survivor().recordChange();
    }
  }
}
