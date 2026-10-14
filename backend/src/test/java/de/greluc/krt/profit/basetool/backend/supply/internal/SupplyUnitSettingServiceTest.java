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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** What an admin lets each org unit do in the supply module (REQ-SUP-002). */
@ExtendWith(MockitoExtension.class)
class SupplyUnitSettingServiceTest {

  @Mock private SupplyUnitSettingRepository settingRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private SupplySettingsService settingsService;
  @Mock private SupplyAccessPolicy accessPolicy;
  @Mock private AuditRecorder auditRecorder;

  private SupplyUnitSettingService service;
  private Squadron raising;
  private Squadron plain;

  @BeforeEach
  void setUp() {
    service =
        new SupplyUnitSettingService(
            settingRepository, orgUnitRepository, settingsService, accessPolicy, auditRecorder);
    raising = unit("Zulu");
    plain = unit("alpha");
  }

  private static Squadron unit(String name) {
    Squadron unit = new Squadron();
    unit.setId(UUID.randomUUID());
    unit.setName(name);
    unit.setShorthand(name.substring(0, 3).toUpperCase(java.util.Locale.ROOT));
    return unit;
  }

  private SupplyUnitSetting setting(UUID id, boolean raise, boolean accept, long version) {
    SupplyUnitSetting setting = new SupplyUnitSetting();
    setting.setId(id);
    setting.setMayRaiseNeeds(raise);
    setting.setAcceptsDonations(accept);
    setting.setVersion(version);
    return setting;
  }

  @Test
  void theConfiguredListHoldsOnlyUnitsWithAFlagSortedByName() {
    when(settingRepository.findAll()).thenReturn(List.of(setting(raising.getId(), true, false, 1)));
    when(orgUnitRepository.findAll()).thenReturn(List.of(raising, plain));
    when(accessPolicy.managedUnitIds()).thenReturn(Set.of(raising.getId()));
    when(accessPolicy.isAdmin()).thenReturn(false);

    List<SupplyUnitDto> units = service.listConfigured();

    assertThat(units).extracting(SupplyUnitDto::name).containsExactly("Zulu");
    assertThat(units.getFirst().mayRaiseNeeds()).isTrue();
    assertThat(units.getFirst().acceptsDonations()).isFalse();
    assertThat(units.getFirst().canManage()).isTrue();
  }

  @Test
  void theAdminListHoldsEveryActiveUnit() {
    plain.setActive(false);
    when(settingRepository.findAll()).thenReturn(List.of());
    when(orgUnitRepository.findAll()).thenReturn(List.of(raising, plain));
    when(accessPolicy.managedUnitIds()).thenReturn(Set.of());
    when(accessPolicy.isAdmin()).thenReturn(true);

    List<SupplyUnitDto> units = service.listAll();

    assertThat(units).extracting(SupplyUnitDto::name).containsExactly("Zulu");
    assertThat(units.getFirst().canManage()).isTrue();
    assertThat(units.getFirst().version()).isNull();
  }

  @Test
  void aFirstSettingIsCreatedAndAudited() {
    when(orgUnitRepository.findById(raising.getId())).thenReturn(Optional.of(raising));
    when(settingRepository.findById(raising.getId())).thenReturn(Optional.empty());
    when(settingRepository.saveAndFlush(any(SupplyUnitSetting.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    SupplyUnitDto dto =
        service.update(raising.getId(), new UpdateSupplyUnitRequest(true, true, null));

    assertThat(dto.mayRaiseNeeds()).isTrue();
    assertThat(dto.acceptsDonations()).isTrue();
    verify(auditRecorder)
        .record(
            eq(AuditEventType.SUPPLY_UNIT_SETTING_CHANGED),
            eq(raising.getId()),
            eq("Zulu"),
            eq(null),
            any());
  }

  @Test
  void anExistingSettingNeedsItsEchoedVersion() {
    SupplyUnitSetting stored = setting(raising.getId(), false, false, 4);
    when(orgUnitRepository.findById(raising.getId())).thenReturn(Optional.of(raising));
    when(settingRepository.findById(raising.getId())).thenReturn(Optional.of(stored));

    assertThatThrownBy(
            () -> service.update(raising.getId(), new UpdateSupplyUnitRequest(true, false, 3L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertThatThrownBy(
            () -> service.update(raising.getId(), new UpdateSupplyUnitRequest(true, false, null)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    verify(settingRepository, never()).saveAndFlush(any());
  }

  @Test
  void anExistingSettingIsChangedWithTheMatchingVersion() {
    SupplyUnitSetting stored = setting(raising.getId(), false, false, 4);
    when(orgUnitRepository.findById(raising.getId())).thenReturn(Optional.of(raising));
    when(settingRepository.findById(raising.getId())).thenReturn(Optional.of(stored));
    when(settingRepository.saveAndFlush(stored)).thenReturn(stored);

    service.update(raising.getId(), new UpdateSupplyUnitRequest(false, true, 4L));

    assertThat(stored.isAcceptsDonations()).isTrue();
    assertThat(stored.isMayRaiseNeeds()).isFalse();
  }
}
