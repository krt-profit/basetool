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

import de.greluc.krt.profit.basetool.backend.catalogue.api.FrequencyType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.FrequencyTypeDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.FrequencyTypeRepository;
import de.greluc.krt.profit.basetool.backend.config.CacheConfig;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.List;
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
 * CRUD and drag-and-drop reorder for the {@code frequency_type} reference table (radio-channel
 * categories). Soft-deletes via {@code active=false}; reads are cached in {@link
 * CacheConfig#FREQUENCY_TYPES_CACHE}, which every mutator evicts.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FrequencyTypeService {

  private final FrequencyTypeRepository frequencyTypeRepository;
  private final FrequencyTypeMapper frequencyTypeMapper;

  /**
   * Returns paged frequency type list.
   *
   * @param active optional active-filter; null returns both active and inactive
   * @param pageable page request
   * @return paged frequency type read models
   */
  @Cacheable(cacheNames = CacheConfig.FREQUENCY_TYPES_CACHE)
  public Page<FrequencyTypeDto> getAllFrequencyTypes(Boolean active, @NotNull Pageable pageable) {
    return frequencyTypeRepository
        .findAllByActive(active, pageable)
        .map(frequencyTypeMapper::toDto);
  }

  /**
   * Returns the frequency type.
   *
   * @param id frequency type primary key
   * @return the frequency type read model
   * @throws NotFoundException when no match
   */
  @Cacheable(cacheNames = CacheConfig.FREQUENCY_TYPES_CACHE)
  public FrequencyTypeDto getFrequencyType(@NotNull UUID id) {
    return frequencyTypeMapper.toDto(requireFrequencyType(id));
  }

  /**
   * Loads the managed frequency type entity a mutator edits; it never comes from the cache.
   *
   * @param id frequency type primary key
   * @return the managed entity
   */
  private FrequencyType requireFrequencyType(UUID id) {
    return Entities.require(frequencyTypeRepository.findById(id), "FrequencyType not found");
  }

  /**
   * Persists a new frequency type. The backend assigns the next sort index automatically.
   *
   * @param frequencyType transient entity
   * @return the persisted frequency type
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.FREQUENCY_TYPES_CACHE, allEntries = true)
  public FrequencyType createFrequencyType(@NotNull FrequencyType frequencyType) {
    return frequencyTypeRepository.save(frequencyType);
  }

  /**
   * Updates name, description and active flag. The {@code sort_index} is preserved — use {@link
   * #reorderFrequencyTypes} to change the order.
   *
   * @param id frequency type primary key
   * @param frequencyType transient entity carrying the new values
   * @return the persisted frequency type
   * @throws NotFoundException when no match
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.FREQUENCY_TYPES_CACHE, allEntries = true)
  public FrequencyType updateFrequencyType(@NotNull UUID id, @NotNull FrequencyType frequencyType) {
    FrequencyType existing = requireFrequencyType(id);
    existing.setName(frequencyType.getName());
    existing.setDescription(frequencyType.getDescription());
    existing.setActive(frequencyType.isActive());
    return frequencyTypeRepository.save(existing);
  }

  /**
   * Soft-deletes a frequency type by flipping {@code active=false}.
   *
   * @param id frequency type primary key
   * @throws NotFoundException when no match
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.FREQUENCY_TYPES_CACHE, allEntries = true)
  public void deleteFrequencyType(@NotNull UUID id) {
    if (!frequencyTypeRepository.existsById(id)) {
      throw new NotFoundException("FrequencyType not found");
    }
    FrequencyType existing = requireFrequencyType(id);
    existing.setActive(false);
    frequencyTypeRepository.save(existing);
  }

  /**
   * Reverses a soft-delete by flipping {@code active=true}.
   *
   * @param id frequency type primary key
   * @throws NotFoundException when no match
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.FREQUENCY_TYPES_CACHE, allEntries = true)
  public void activateFrequencyType(@NotNull UUID id) {
    FrequencyType existing = requireFrequencyType(id);
    existing.setActive(true);
    frequencyTypeRepository.save(existing);
  }

  /**
   * Persists a new ordering of frequency types. The position of each id in the supplied list is
   * written as the row's new {@code sort_index}. Missing ids are silently skipped (defensive — the
   * admin UI submits the full current list).
   *
   * @param ids ids in the desired new order
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.FREQUENCY_TYPES_CACHE, allEntries = true)
  public void reorderFrequencyTypes(@NotNull List<UUID> ids) {
    for (int i = 0; i < ids.size(); i++) {
      int index = i;
      frequencyTypeRepository
          .findById(ids.get(i))
          .ifPresent(
              freq -> {
                freq.setSortIndex(index);
                frequencyTypeRepository.save(freq);
              });
    }
  }
}
