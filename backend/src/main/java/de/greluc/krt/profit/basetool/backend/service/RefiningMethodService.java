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
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.RefiningMethod;
import de.greluc.krt.profit.basetool.backend.repository.RefiningMethodRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached CRUD service for the {@code refining_method} reference table.
 *
 * <p>Underlying data is owned by {@link UexRefinerySyncService}; this service adds the
 * cache-evicting CRUD surface used by admins to manually correct names / descriptions when UEX data
 * is wrong. The cache eviction is {@code allEntries=true} because the frontend's refining-method
 * dropdown lists everything in one call.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefiningMethodService {

  private final RefiningMethodRepository refiningMethodRepository;
  private final AuditService auditService;

  /**
   * Returns cached page of refining methods.
   *
   * @param pageable page request
   * @return cached page of refining methods
   */
  @Cacheable(cacheNames = CacheConfig.REFINING_METHODS_CACHE)
  public Page<RefiningMethod> getAllRefiningMethods(@NotNull Pageable pageable) {
    return refiningMethodRepository.findAll(pageable);
  }

  /**
   * Returns the refining method.
   *
   * @param id refining method primary key
   * @return the refining method
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no match
   */
  @Cacheable(cacheNames = CacheConfig.REFINING_METHODS_CACHE)
  public RefiningMethod getRefiningMethod(@NotNull UUID id) {
    return refiningMethodRepository
        .findById(id)
        .orElseThrow(() -> new NotFoundException("RefiningMethod not found"));
  }

  /**
   * Persists a new refining method and evicts the cache.
   *
   * @param refiningMethod transient entity
   * @return the persisted entity
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.REFINING_METHODS_CACHE, allEntries = true)
  public RefiningMethod createRefiningMethod(@NotNull RefiningMethod refiningMethod) {
    RefiningMethod saved = refiningMethodRepository.save(refiningMethod);
    auditService.record(
        AuditEventType.REFINERY_METHOD_CREATED,
        saved.getId(),
        saved.getName(),
        null,
        "name=" + saved.getName());
    return saved;
  }

  /**
   * Updates name and description of an existing refining method. UEX-imported numeric ratings
   * (yield/cost/speed) are NOT mutable here — those come from {@link UexRefinerySyncService} and a
   * manual override would be silently overwritten on the next sync.
   *
   * @param id refining method primary key
   * @param refiningMethodDetails transient entity carrying the new values
   * @return the persisted entity
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.REFINING_METHODS_CACHE, allEntries = true)
  public RefiningMethod updateRefiningMethod(
      @NotNull UUID id, @NotNull RefiningMethod refiningMethodDetails) {
    RefiningMethod refiningMethod = getRefiningMethod(id);

    String previousName = refiningMethod.getName();
    refiningMethod.setName(refiningMethodDetails.getName());
    refiningMethod.setDescription(refiningMethodDetails.getDescription());

    RefiningMethod saved = refiningMethodRepository.save(refiningMethod);
    auditService.record(
        AuditEventType.REFINERY_METHOD_UPDATED,
        saved.getId(),
        saved.getName(),
        null,
        "name=" + previousName + "->" + saved.getName());
    return saved;
  }

  /**
   * Deletes a refining method. The backend rejects the delete when any refinery order still
   * references the method.
   *
   * @param id refining method primary key
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.REFINING_METHODS_CACHE, allEntries = true)
  public void deleteRefiningMethod(@NotNull UUID id) {
    RefiningMethod refiningMethod = getRefiningMethod(id);
    String name = refiningMethod.getName();
    refiningMethodRepository.delete(refiningMethod);
    auditService.record(AuditEventType.REFINERY_METHOD_DELETED, id, name, null, "name=" + name);
  }
}
