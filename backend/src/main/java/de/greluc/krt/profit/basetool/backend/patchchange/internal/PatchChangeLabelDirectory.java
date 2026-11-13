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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderPatchLabels;
import de.greluc.krt.profit.basetool.backend.patchchange.api.PatchChangeLabels;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers the current labels of patch changes for the exchange feeds (REQ-PATCH-007) and the aUEC
 * order history (REQ-PATCH-044).
 */
@Component
@RequiredArgsConstructor
class PatchChangeLabelDirectory implements PatchChangeLabels, JobOrderPatchLabels {

  private final PatchChangeRepository repository;

  @Override
  @Transactional(readOnly = true)
  public @NotNull Map<UUID, String> labels(@NotNull Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    return repository.findLabels(ids).stream()
        .collect(
            Collectors.toUnmodifiableMap(
                PatchChangeRepository.LabelRow::getId, PatchChangeRepository.LabelRow::getLabel));
  }
}
