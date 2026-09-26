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

import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.SystemSettingMapper;
import de.greluc.krt.profit.basetool.backend.model.SystemSetting;
import de.greluc.krt.profit.basetool.backend.model.dto.SystemSettingDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SystemSettingUpdateDto;
import de.greluc.krt.profit.basetool.backend.repository.SystemSettingRepository;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Key/value store for runtime-configurable settings (job-order age thresholds, refinery rounding
 * mode, …). Each row carries an optimistic-lock version so two admins editing the same key
 * concurrently can't silently overwrite each other.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SystemSettingService {

  private final SystemSettingRepository systemSettingRepository;
  private final SystemSettingMapper systemSettingMapper;

  /**
   * Returns all settings as DTOs.
   *
   * @return all settings as DTOs
   */
  public List<SystemSettingDto> getAllSettings() {
    return systemSettingRepository.findAll().stream().map(systemSettingMapper::toDto).toList();
  }

  /**
   * Returns the setting DTO.
   *
   * @param key setting key (the table's PK)
   * @return the setting DTO
   * @throws NotFoundException when the key is not present
   */
  public SystemSettingDto getSetting(String key) {
    return Entities.require(
        systemSettingRepository.findById(key).map(systemSettingMapper::toDto),
        () -> "Setting not found: " + key);
  }

  /**
   * Convenience accessor for code paths that only need the raw string value (e.g. parsing the
   * refinery rounding mode in the order pricing).
   *
   * @param key setting key
   * @return the string value, or empty when the key is absent
   */
  public Optional<String> getSettingValue(String key) {
    return systemSettingRepository.findById(key).map(SystemSetting::getValue);
  }

  /**
   * Updates the value of a setting. Optimistic-lock check is explicit (the DTO carries the expected
   * version), not Hibernate's automatic check — the setting is identified by key (not by id) and
   * the form post pre-loads the version separately.
   *
   * @param key setting key
   * @param dto new value + expected version
   * @return the persisted setting DTO
   * @throws NotFoundException when the key is absent
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the supplied
   *     version no longer matches
   */
  @Transactional
  public SystemSettingDto updateSetting(String key, SystemSettingUpdateDto dto) {
    SystemSetting setting =
        Entities.require(systemSettingRepository.findById(key), () -> "Setting not found: " + key);

    OptimisticLock.check(setting.getVersion(), dto.version(), SystemSetting.class, key);

    setting.setValue(dto.value());
    return systemSettingMapper.toDto(systemSettingRepository.saveAndFlush(setting));
  }
}
