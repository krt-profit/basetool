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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.mapper.BlueprintMapper;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintDto;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.support.LikePatterns;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read service for the admin blueprint page: a paged, filtered view of the synced crafting
 * blueprints with their requirement-group stat graph.
 *
 * <p>Blueprints are global reference data, not org-unit-scoped; DTO mapping happens inside the
 * read-only transaction.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlueprintService {

  private final BlueprintRepository blueprintRepository;
  private final BlueprintMapper blueprintMapper;

  /**
   * Returns one page of active blueprints, optionally filtered by output-item name or Wiki key.
   *
   * @param search case-insensitive substring; blank or {@code null} returns all
   * @param pageable page request with a whitelisted sort
   * @return a page of blueprint DTOs
   */
  public Page<BlueprintDto> getBlueprints(@Nullable String search, @NotNull Pageable pageable) {
    boolean hasSearch = search != null && !search.isBlank();
    Page<Blueprint> page =
        hasSearch
            ? blueprintRepository.searchActive(LikePatterns.escapeNullable(search.trim()), pageable)
            : blueprintRepository.findByScwikiDeletedAtIsNull(pageable);
    return page.map(blueprintMapper::toDto);
  }
}
