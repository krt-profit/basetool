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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RefiningMethod;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RefiningMethodDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RefiningMethodMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RefiningMethodRepository;
import de.greluc.krt.profit.basetool.backend.config.CacheConfig;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
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
 * Cached CRUD service for the {@code refining_method} reference table, whose data is owned by
 * {@link UexRefinerySyncService}. Writes evict the whole cache.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefiningMethodService {

  private final RefiningMethodRepository refiningMethodRepository;
  private final RefiningMethodMapper refiningMethodMapper;
  private final AuditRecorder auditRecorder;

  /**
   * Returns cached page of refining methods.
   *
   * @param pageable page request
   * @return cached page of refining methods
   */
  @Cacheable(cacheNames = CacheConfig.REFINING_METHODS_CACHE)
  public Page<RefiningMethodDto> getAllRefiningMethods(@NotNull Pageable pageable) {
    return refiningMethodRepository.findAll(pageable).map(refiningMethodMapper::toDto);
  }

  /**
   * Returns the refining method.
   *
   * @param id refining method primary key
   * @return the refining method
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when no match
   */
  @Cacheable(cacheNames = CacheConfig.REFINING_METHODS_CACHE)
  public RefiningMethodDto getRefiningMethod(@NotNull UUID id) {
    return refiningMethodMapper.toDto(requireRefiningMethod(id));
  }

  /**
   * Loads the managed refining method entity a mutator edits; it never comes from the cache.
   *
   * @param id refining method primary key
   * @return the managed entity
   */
  private RefiningMethod requireRefiningMethod(UUID id) {
    return Entities.require(refiningMethodRepository.findById(id), "RefiningMethod not found");
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
    auditRecorder.record(
        AuditEventType.REFINERY_METHOD_CREATED,
        saved.getId(),
        saved.getName(),
        null,
        "name=" + saved.getName());
    return saved;
  }

  /**
   * Updates the name and description of a refining method. The UEX-imported ratings
   * (yield/cost/speed) are not changed here, since {@link UexRefinerySyncService} owns them.
   *
   * @param id refining method primary key
   * @param refiningMethodDetails transient entity carrying the new values
   * @return the persisted entity
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.REFINING_METHODS_CACHE, allEntries = true)
  public RefiningMethod updateRefiningMethod(
      @NotNull UUID id, @NotNull RefiningMethod refiningMethodDetails) {
    RefiningMethod refiningMethod = requireRefiningMethod(id);

    String previousName = refiningMethod.getName();
    refiningMethod.setName(refiningMethodDetails.getName());
    refiningMethod.setDescription(refiningMethodDetails.getDescription());

    RefiningMethod saved = refiningMethodRepository.save(refiningMethod);
    auditRecorder.record(
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
    RefiningMethod refiningMethod = requireRefiningMethod(id);
    String name = refiningMethod.getName();
    refiningMethodRepository.delete(refiningMethod);
    auditRecorder.record(AuditEventType.REFINERY_METHOD_DELETED, id, name, null, "name=" + name);
  }
}
