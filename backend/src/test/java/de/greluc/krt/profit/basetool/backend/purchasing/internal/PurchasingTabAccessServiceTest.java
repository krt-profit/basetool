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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingTabAccess.PurchasingTabGrants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The purchasing tabs of the layout read follow the gates of the partner list, the delegate list
 * and the report (REQ-UI-026, REQ-PUR-021, REQ-PUR-023).
 */
@ExtendWith(MockitoExtension.class)
class PurchasingTabAccessServiceTest {

  @Mock private PurchasingSettingsService settingsService;
  @Mock private PurchasingAccessPolicy accessPolicy;

  private PurchasingTabAccessService service;

  @BeforeEach
  void setUp() {
    service = new PurchasingTabAccessService(settingsService, accessPolicy);
  }

  @Test
  void aSwitchedOffSurfaceOffersNoTabAndAsksNoPolicy() {
    when(settingsService.isEnabled()).thenReturn(false);

    assertThat(service.current()).isEqualTo(PurchasingTabGrants.NONE);

    verifyNoInteractions(accessPolicy);
  }

  @Test
  void aTradeDelegateGetsThePartnersAndTheReportButNotTheDelegates() {
    when(settingsService.isEnabled()).thenReturn(true);
    when(accessPolicy.canMaintainPartners()).thenReturn(true);
    when(accessPolicy.canAppointDelegates()).thenReturn(false);

    assertThat(service.current()).isEqualTo(new PurchasingTabGrants(true, false, true));

    verify(accessPolicy, never()).canReadReport();
  }

  @Test
  void theGoverningLeadershipGetsEveryTab() {
    when(settingsService.isEnabled()).thenReturn(true);
    when(accessPolicy.canMaintainPartners()).thenReturn(true);
    when(accessPolicy.canAppointDelegates()).thenReturn(true);

    assertThat(service.current()).isEqualTo(new PurchasingTabGrants(true, true, true));
  }

  @Test
  void bankStaffOrAUnitManagerGetsOnlyTheReport() {
    when(settingsService.isEnabled()).thenReturn(true);
    when(accessPolicy.canMaintainPartners()).thenReturn(false);
    when(accessPolicy.canAppointDelegates()).thenReturn(false);
    when(accessPolicy.canReadReport()).thenReturn(true);

    assertThat(service.current()).isEqualTo(new PurchasingTabGrants(false, false, true));
  }

  @Test
  void aPlainMemberGetsNoTab() {
    when(settingsService.isEnabled()).thenReturn(true);
    when(accessPolicy.canMaintainPartners()).thenReturn(false);
    when(accessPolicy.canAppointDelegates()).thenReturn(false);
    when(accessPolicy.canReadReport()).thenReturn(false);

    assertThat(service.current()).isEqualTo(PurchasingTabGrants.NONE);
  }
}
