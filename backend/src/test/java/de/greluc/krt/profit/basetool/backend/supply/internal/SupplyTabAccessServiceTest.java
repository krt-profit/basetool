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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.AccessGateService;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedPriceAudience;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyTabAccess.SupplyTabGrants;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The supply tabs of the layout read follow the incoming-donations page and the statistics gate
 * (REQ-UI-026, REQ-SUP-013), through the real {@link SupplyAccessPolicy}.
 */
@ExtendWith(MockitoExtension.class)
class SupplyTabAccessServiceTest {

  private static final UUID MANAGED = UUID.randomUUID();
  private static final UUID OTHER = UUID.randomUUID();

  @Mock private SupplySettingsService settingsService;
  @Mock private SupplyUnitSettingRepository settingRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private RequestScopeResolver requestScopeResolver;
  @Mock private AccessGateService accessGateService;
  @Mock private AuthHelperService authHelperService;
  @Mock private ObjectProvider<NeedPriceAudience> priceAudiences;

  private SupplyTabAccessService service;

  @BeforeEach
  void setUp() {
    SupplyAccessPolicy policy =
        new SupplyAccessPolicy(
            requestScopeResolver,
            accessGateService,
            authHelperService,
            settingsService,
            priceAudiences);
    service =
        new SupplyTabAccessService(settingsService, policy, settingRepository, orgUnitRepository);
  }

  private static SupplyUnitSetting accepting(UUID unitId) {
    SupplyUnitSetting setting = new SupplyUnitSetting();
    setting.setId(unitId);
    setting.setAcceptsDonations(true);
    return setting;
  }

  private static Squadron unit(UUID id, boolean active) {
    Squadron unit = new Squadron();
    unit.setId(id);
    unit.setActive(active);
    return unit;
  }

  private void member(Set<UUID> managed) {
    when(settingsService.isEnabled()).thenReturn(true);
    when(authHelperService.isAdmin()).thenReturn(false);
    when(requestScopeResolver.currentGrantedOrgUnitIds(SupplyAccessPolicy.UNIT_MANAGER_ROLE))
        .thenReturn(managed);
  }

  private void noLeadership() {
    when(requestScopeResolver.currentUserIsOlMember()).thenReturn(false);
    when(settingsService.governingAreaId()).thenReturn(Optional.empty());
  }

  @Test
  void aSwitchedOffSurfaceOffersNoTabAndReadsNothingElse() {
    when(settingsService.isEnabled()).thenReturn(false);

    assertThat(service.current()).isEqualTo(SupplyTabGrants.NONE);

    verifyNoInteractions(settingRepository, orgUnitRepository, requestScopeResolver);
  }

  @Test
  void theManagerOfAnActiveAcceptingUnitGetsBothTabs() {
    member(Set.of(MANAGED));
    noLeadership();
    when(settingRepository.findByAcceptsDonationsTrue()).thenReturn(List.of(accepting(MANAGED)));
    when(orgUnitRepository.findAllById(List.of(MANAGED))).thenReturn(List.of(unit(MANAGED, true)));

    assertThat(service.current()).isEqualTo(new SupplyTabGrants(true, true));
  }

  @Test
  void theManagerOfAUnitThatAcceptsNoDonationsGetsOnlyTheStatistics() {
    member(Set.of(MANAGED));
    noLeadership();
    when(settingRepository.findByAcceptsDonationsTrue()).thenReturn(List.of(accepting(OTHER)));

    assertThat(service.current()).isEqualTo(new SupplyTabGrants(false, true));

    verify(orgUnitRepository, never()).findAllById(any());
  }

  @Test
  void anInactiveAcceptingUnitOffersNoIncomingDonations() {
    member(Set.of(MANAGED));
    noLeadership();
    when(settingRepository.findByAcceptsDonationsTrue()).thenReturn(List.of(accepting(MANAGED)));
    when(orgUnitRepository.findAllById(List.of(MANAGED))).thenReturn(List.of(unit(MANAGED, false)));

    assertThat(service.current().incoming()).isFalse();
  }

  @Test
  void aPlainMemberGetsNeitherTab() {
    member(Set.of());
    noLeadership();
    when(settingRepository.findByAcceptsDonationsTrue()).thenReturn(List.of(accepting(OTHER)));

    assertThat(service.current()).isEqualTo(SupplyTabGrants.NONE);
  }

  @Test
  void anOrganisationsleitungMemberWithoutAUnitGetsOnlyTheStatistics() {
    member(Set.of());
    when(requestScopeResolver.currentUserIsOlMember()).thenReturn(true);
    when(settingRepository.findByAcceptsDonationsTrue()).thenReturn(List.of(accepting(OTHER)));

    assertThat(service.current()).isEqualTo(new SupplyTabGrants(false, true));
  }

  @Test
  void anAdminReachesEveryActiveAcceptingUnit() {
    when(settingsService.isEnabled()).thenReturn(true);
    when(authHelperService.isAdmin()).thenReturn(true);
    when(settingRepository.findByAcceptsDonationsTrue()).thenReturn(List.of(accepting(OTHER)));
    when(orgUnitRepository.findAllById(List.of(OTHER))).thenReturn(List.of(unit(OTHER, true)));

    assertThat(service.current()).isEqualTo(new SupplyTabGrants(true, true));
  }

  @Test
  void withoutAnyAcceptingUnitNobodyGetsIncomingDonations() {
    when(settingsService.isEnabled()).thenReturn(true);
    when(authHelperService.isAdmin()).thenReturn(true);
    when(settingRepository.findByAcceptsDonationsTrue()).thenReturn(List.of());

    assertThat(service.current()).isEqualTo(new SupplyTabGrants(false, true));

    verify(orgUnitRepository, never()).findAllById(any());
  }
}
