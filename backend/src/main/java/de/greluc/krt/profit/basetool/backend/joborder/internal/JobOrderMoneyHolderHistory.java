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
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Resolves historical holder changes through current account and erasure state. */
@Component
@RequiredArgsConstructor
class JobOrderMoneyHolderHistory {

  private final JobOrderMoneyHolderChangeRepository history;
  private final JobOrderMoneyHolders holders;

  List<JobOrderMoneyHolderChangeView> views(UUID orderId) {
    List<JobOrderMoneyHolderChange> changes =
        history.findAllByOrderIdOrderByRecordedAtAscIdAsc(orderId);
    Map<UUID, String> names =
        holders.namesOf(
            changes.stream()
                .flatMap(change ->
                    Stream.of(change.getActorId(), change.getUserId(), change.getPreviousUserId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList());
    return changes.stream()
        .map(change ->
            new JobOrderMoneyHolderChangeView(
                Objects.requireNonNull(change.getId()),
                change.getKind(),
                change.getHolderId(),
                change.getTargetHolderId(),
                name(change.getPreviousUserId(), change.getPreviousName(), names),
                name(change.getUserId(), change.getName(), names),
                change.getRecordedAt(),
                name(change.getActorId(), null, names)))
        .toList();
  }

  private static @Nullable String name(
      @Nullable UUID userId, @Nullable String recordedName, Map<UUID, String> names) {
    return userId == null ? recordedName : names.get(userId);
  }
}
