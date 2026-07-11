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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.model.dto.SystemSettingDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SystemSettingUpdateDto;
import de.greluc.krt.profit.basetool.backend.service.SystemSettingService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public/admin REST surface over the {@code system_setting} key-value store. Reads are public (the
 * frontend's home page reads the announcement / aging thresholds without authentication); writes
 * are restricted to ADMIN/OFFICER and carry an optimistic-lock version.
 */
@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SystemSettingController {

  private final SystemSettingService systemSettingService;

  /**
   * Returns every system setting as a DTO.
   *
   * @return every system setting as a DTO
   */
  @GetMapping
  public List<SystemSettingDto> getAllSettings() {
    return systemSettingService.getAllSettings();
  }

  /**
   * Returns the setting DTO.
   *
   * @param key setting key (table primary key)
   * @return the setting DTO
   */
  @GetMapping("/{key}")
  public SystemSettingDto getSetting(@PathVariable String key) {
    return systemSettingService.getSetting(key);
  }

  /**
   * Updates a single setting. Optimistic-lock check is explicit (version in the DTO body).
   *
   * @param key setting key
   * @param dto update payload (value + expected version)
   * @return the persisted DTO
   */
  @PutMapping("/{key}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public SystemSettingDto updateSetting(
      @PathVariable String key, @Valid @RequestBody SystemSettingUpdateDto dto) {
    return systemSettingService.updateSetting(key, dto);
  }
}
