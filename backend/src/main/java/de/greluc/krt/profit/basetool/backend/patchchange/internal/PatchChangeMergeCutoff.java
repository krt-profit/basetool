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

import de.greluc.krt.profit.basetool.backend.inventory.api.StockMergeCutoff;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The no-mixing rule's answer to the Lager (REQ-PATCH-004, ADR-0381): rows created before the
 * latest patch change's live time never merge.
 */
@Component
@RequiredArgsConstructor
class PatchChangeMergeCutoff implements StockMergeCutoff {

  private final PatchChangeRepository repository;

  @Override
  @Transactional(readOnly = true)
  public @NotNull Optional<Instant> cutoff() {
    return repository.findLatestLiveAt();
  }
}
