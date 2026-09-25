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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.SystemSettingMapper;
import de.greluc.krt.profit.basetool.backend.model.SystemSetting;
import de.greluc.krt.profit.basetool.backend.model.dto.SystemSettingDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SystemSettingUpdateDto;
import de.greluc.krt.profit.basetool.backend.repository.SystemSettingRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class SystemSettingServiceTest {

  @Mock private SystemSettingRepository systemSettingRepository;

  @Mock private SystemSettingMapper systemSettingMapper;

  @InjectMocks private SystemSettingService systemSettingService;

  private SystemSetting setting;
  private SystemSettingDto settingDto;

  @BeforeEach
  void setUp() {
    setting = new SystemSetting();
    setting.setId("test_key");
    setting.setValue("test_value");
    setting.setVersion(1L);

    settingDto = new SystemSettingDto("test_key", "test_value", 1L);
  }

  @Test
  void getSetting_ShouldReturnDto() {
    when(systemSettingRepository.findById("test_key")).thenReturn(Optional.of(setting));
    when(systemSettingMapper.toDto(setting)).thenReturn(settingDto);

    SystemSettingDto result = systemSettingService.getSetting("test_key");

    assertNotNull(result);
    assertEquals("test_value", result.value());
  }

  @Test
  void getSetting_NotFound_ShouldThrowException() {
    when(systemSettingRepository.findById("unknown_key")).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> systemSettingService.getSetting("unknown_key"));
  }

  @Test
  void updateSetting_ShouldUpdateValue() {
    when(systemSettingRepository.findById("test_key")).thenReturn(Optional.of(setting));
    when(systemSettingRepository.saveAndFlush(any())).thenReturn(setting);
    when(systemSettingMapper.toDto(any()))
        .thenReturn(new SystemSettingDto("test_key", "new_value", 2L));

    SystemSettingUpdateDto updateDto = new SystemSettingUpdateDto("new_value", 1L);
    SystemSettingDto result = systemSettingService.updateSetting("test_key", updateDto);

    assertEquals("new_value", result.value());
    verify(systemSettingRepository).saveAndFlush(setting);
  }

  /**
   * Pins that {@code updateSetting} maps its response DTO from a {@code saveAndFlush}, not a plain
   * {@code save}: the admin-settings form writes the returned {@code @Version} straight back into
   * its hidden input in place (no reload), so a plain {@code save} would hand back the stale
   * pre-flush version and the next consecutive save of the same setting would 409.
   */
  @Test
  void updateSetting_flushesBeforeMappingSoVersionIsFresh() {
    when(systemSettingRepository.findById("test_key")).thenReturn(Optional.of(setting));
    when(systemSettingRepository.saveAndFlush(setting)).thenReturn(setting);
    when(systemSettingMapper.toDto(setting))
        .thenReturn(new SystemSettingDto("test_key", "new_value", 2L));

    systemSettingService.updateSetting("test_key", new SystemSettingUpdateDto("new_value", 1L));

    verify(systemSettingRepository).saveAndFlush(setting);
    verify(systemSettingRepository, never()).save(setting);
  }

  @Test
  void updateSetting_OptimisticLocking_ShouldThrowException() {
    when(systemSettingRepository.findById("test_key")).thenReturn(Optional.of(setting));

    SystemSettingUpdateDto updateDto = new SystemSettingUpdateDto("new_value", 2L);

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> systemSettingService.updateSetting("test_key", updateDto));
    verify(systemSettingRepository, never()).save(any());
  }
}
