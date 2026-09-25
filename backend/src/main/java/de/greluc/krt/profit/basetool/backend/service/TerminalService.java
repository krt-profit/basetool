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

import de.greluc.krt.profit.basetool.backend.config.CacheConfig;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.model.Terminal;
import de.greluc.krt.profit.basetool.backend.repository.TerminalRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached read service plus admin-only visibility, loading-dock and auto-load overrides for the
 * terminal catalog owned by {@link UexUniverseSyncService}.
 *
 * <p>Every mutator and every completed UEX sweep evicts {@link CacheConfig#TERMINALS_CACHE}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TerminalService {

  private final TerminalRepository terminalRepository;

  /**
   * Returns paged terminal list (includes hidden — frontend filters via {@code includeHidden}).
   *
   * @param pageable page request
   * @return paged terminal list (includes hidden — frontend filters via {@code includeHidden})
   */
  @Cacheable(cacheNames = CacheConfig.TERMINALS_CACHE)
  public Page<Terminal> getAllTerminals(Pageable pageable) {
    return terminalRepository.findAll(pageable);
  }

  /**
   * Returns the terminal with the given id (cached).
   *
   * @param id terminal primary key
   * @return the terminal
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no terminal
   *     matches
   */
  @Cacheable(cacheNames = CacheConfig.TERMINALS_CACHE)
  public Terminal getTerminal(UUID id) {
    return Entities.require(terminalRepository.findById(id), "Terminal not found");
  }

  /**
   * Flips the {@code hidden} flag on a terminal.
   *
   * @param id terminal primary key
   * @param hidden new flag value
   * @return the persisted terminal
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.TERMINALS_CACHE, allEntries = true)
  public Terminal updateTerminalVisibility(UUID id, boolean hidden) {
    Terminal terminal = getTerminal(id);
    terminal.setHidden(hidden);
    return terminalRepository.save(terminal);
  }

  /**
   * Pins {@code hasLoadingDock} to the supplied value and marks it as admin-overridden so the next
   * UEX sweep leaves the value column untouched.
   *
   * @param id terminal primary key
   * @param value desired {@code hasLoadingDock} value
   * @return the persisted terminal
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.TERMINALS_CACHE, allEntries = true)
  public Terminal setLoadingDockOverride(UUID id, boolean value) {
    Terminal terminal = getTerminal(id);
    terminal.setHasLoadingDock(value);
    terminal.setHasLoadingDockOverridden(true);
    return terminalRepository.save(terminal);
  }

  /**
   * Releases the admin pin on {@code hasLoadingDock} and immediately restores the value from {@link
   * Terminal#getUexHasLoadingDock()}, which is {@code null} if the terminal was never synced.
   *
   * @param id terminal primary key
   * @return the persisted terminal
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.TERMINALS_CACHE, allEntries = true)
  public Terminal clearLoadingDockOverride(UUID id) {
    Terminal terminal = getTerminal(id);
    terminal.setHasLoadingDockOverridden(false);
    terminal.setHasLoadingDock(terminal.getUexHasLoadingDock());
    return terminalRepository.save(terminal);
  }

  /**
   * Pins {@code isAutoLoad} to the supplied value and marks it as admin-overridden so the next UEX
   * sweep leaves the value column untouched.
   *
   * @param id terminal primary key
   * @param value desired {@code isAutoLoad} value
   * @return the persisted terminal
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.TERMINALS_CACHE, allEntries = true)
  public Terminal setAutoLoadOverride(UUID id, boolean value) {
    Terminal terminal = getTerminal(id);
    terminal.setIsAutoLoad(value);
    terminal.setIsAutoLoadOverridden(true);
    return terminalRepository.save(terminal);
  }

  /**
   * Releases the admin pin on {@code isAutoLoad} and immediately restores the value from {@link
   * Terminal#getUexIsAutoLoad()}, which is {@code null} if the terminal was never synced.
   *
   * @param id terminal primary key
   * @return the persisted terminal
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.TERMINALS_CACHE, allEntries = true)
  public Terminal clearAutoLoadOverride(UUID id) {
    Terminal terminal = getTerminal(id);
    terminal.setIsAutoLoadOverridden(false);
    terminal.setIsAutoLoad(terminal.getUexIsAutoLoad());
    return terminalRepository.save(terminal);
  }
}
