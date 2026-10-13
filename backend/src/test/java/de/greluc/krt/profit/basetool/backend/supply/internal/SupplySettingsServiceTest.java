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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.admin.api.SystemSettings;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Bereich;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyProblemCode;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyProblemException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The supply feature switch and the governing Bereich (REQ-SUP-001). */
@ExtendWith(MockitoExtension.class)
class SupplySettingsServiceTest {

  @Mock private SystemSettings systemSettings;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private AuditRecorder auditRecorder;
  @Mock private AuthHelperService authHelperService;

  private SupplySettingsService service;

  @BeforeEach
  void setUp() {
    service =
        new SupplySettingsService(
            systemSettings, orgUnitRepository, auditRecorder, authHelperService);
  }

  @Test
  void theSurfaceIsOffUntilTheSwitchHoldsTrue() {
    when(systemSettings.getSettingValue("supply.enabled")).thenReturn(Optional.empty());
    assertThat(service.isEnabled()).isFalse();

    when(systemSettings.getSettingValue("supply.enabled")).thenReturn(Optional.of(" true "));
    assertThat(service.isEnabled()).isTrue();

    when(systemSettings.getSettingValue("supply.enabled")).thenReturn(Optional.of("yes"));
    assertThat(service.isEnabled()).isFalse();
  }

  @Test
  void aSwitchedOffSurfaceAnswersWithTheDisabledProblem() {
    when(systemSettings.getSettingValue("supply.enabled")).thenReturn(Optional.of("false"));

    assertThatThrownBy(() -> service.requireEnabled())
        .isInstanceOfSatisfying(
            SupplyProblemException.class,
            e -> assertThat(e.getProblemCode()).isEqualTo(SupplyProblemCode.SUPPLY_DISABLED));
  }

  @Test
  void aMalformedGoverningAreaIsIgnored() {
    when(systemSettings.getSettingValue("supply.governing_area_id"))
        .thenReturn(Optional.of("not-an-id"));
    assertThat(service.governingAreaId()).isEmpty();

    when(systemSettings.getSettingValue("supply.governing_area_id")).thenReturn(Optional.of(" "));
    assertThat(service.governingAreaId()).isEmpty();

    UUID id = UUID.randomUUID();
    when(systemSettings.getSettingValue("supply.governing_area_id"))
        .thenReturn(Optional.of(id.toString()));
    assertThat(service.governingAreaId()).contains(id);
  }

  @Test
  void theGoverningUnitMustBeABereich() {
    Squadron squadron = new Squadron();
    squadron.setId(UUID.randomUUID());
    when(orgUnitRepository.findById(squadron.getId())).thenReturn(Optional.of(squadron));

    assertThatThrownBy(() -> service.update(true, squadron.getId()))
        .isInstanceOf(BadRequestException.class);

    verify(systemSettings, never()).putSettingValue(any(), any());
    verify(auditRecorder, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void anUpdateStoresBothValuesAndAuditsTheChange() {
    Bereich area = new Bereich();
    area.setId(UUID.randomUUID());
    area.setName("Bereich Profit");
    when(orgUnitRepository.findById(area.getId())).thenReturn(Optional.of(area));
    when(systemSettings.getSettingValue("supply.enabled")).thenReturn(Optional.of("false"));
    when(systemSettings.getSettingValue("supply.governing_area_id")).thenReturn(Optional.empty());

    service.update(true, area.getId());

    verify(systemSettings).putSettingValue("supply.enabled", "true");
    verify(systemSettings).putSettingValue("supply.governing_area_id", area.getId().toString());
    verify(auditRecorder)
        .record(eq(AuditEventType.SUPPLY_SETTINGS_CHANGED), eq(null), any(), eq(null), any());
  }

  @Test
  void clearingTheGoverningAreaStoresAnEmptyValue() {
    when(systemSettings.getSettingValue("supply.enabled")).thenReturn(Optional.of("true"));
    when(systemSettings.getSettingValue("supply.governing_area_id")).thenReturn(Optional.empty());

    service.update(false, null);

    verify(systemSettings).putSettingValue("supply.enabled", "false");
    verify(systemSettings).putSettingValue("supply.governing_area_id", "");
  }
}
