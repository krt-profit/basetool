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
 * Read service plus visibility / loading-dock / auto-load overrides for the terminal catalog. The
 * records themselves are owned by {@link UexUniverseSyncService}; this service only exposes the
 * read API and the admin-only flag flips. Read methods are cached against {@link
 * CacheConfig#TERMINALS_CACHE}; every mutator evicts the whole cache, and the periodic {@link
 * UexUniverseSyncService} sweep evicts it on completion (via {@code
 * MasterDataCacheEvictionService}, CACHE-SYNC-EVICT-001), so background-sync writes are visible on
 * the next read; the 12-hour master-data TTL is only the backstop.
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
   * Returns the terminal.
   *
   * @param id terminal primary key
   * @return the terminal
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no terminal
   *     matches
   */
  @Cacheable(cacheNames = CacheConfig.TERMINALS_CACHE)
  public Terminal getTerminal(UUID id) {
    return terminalRepository
        .findById(id)
        .orElseThrow(
            () ->
                new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                    "Terminal not found"));
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
   * Releases the admin pin on {@code hasLoadingDock} and immediately reverts the value column to
   * the last UEX-reported state ({@link Terminal#getUexHasLoadingDock()}), so consumers like the
   * materials-overview filter and the UEX-source chip stop seeing the stale admin-pinned value
   * before the next UEX sweep runs.
   *
   * <p>If the terminal has never been synced yet, {@code uexHasLoadingDock} is {@code null} and the
   * value column is cleared to {@code null} too — that maps to "unknown" in every consumer and is
   * the correct semantics for "I do not have a UEX value yet, fall back to whatever the next sweep
   * tells me".
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
   * Releases the admin pin on {@code isAutoLoad} and immediately reverts the value column to the
   * last UEX-reported state ({@link Terminal#getUexIsAutoLoad()}), so consumers like the
   * materials-overview filter and the UEX-source chip stop seeing the stale admin-pinned value
   * before the next UEX sweep runs.
   *
   * <p>If the terminal has never been synced yet, {@code uexIsAutoLoad} is {@code null} and the
   * value column is cleared to {@code null} too — that maps to "unknown" in every consumer and is
   * the correct semantics for "I do not have a UEX value yet, fall back to whatever the next sweep
   * tells me".
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
