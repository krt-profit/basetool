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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kPatchVersionReader;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Answers the latest known P4K patch version from the applied import runs (REQ-P4K-005). */
@Service
@RequiredArgsConstructor
public class P4kPatchVersionReaderService implements P4kPatchVersionReader {

  private final P4kImportRunRepository runRepository;

  @Override
  @Transactional(readOnly = true)
  public @NotNull Optional<String> latestPatchVersion() {
    return runRepository
        .findFirstByPatchVersionIsNotNullOrderByAppliedAtDesc()
        .map(P4kImportRun::getPatchVersion);
  }
}
