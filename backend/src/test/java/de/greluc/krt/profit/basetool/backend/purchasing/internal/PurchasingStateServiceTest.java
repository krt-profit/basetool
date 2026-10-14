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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The switch and the caller's capabilities as the state read reports them (REQ-PUR-001). */
@ExtendWith(MockitoExtension.class)
class PurchasingStateServiceTest {

  @Mock private PurchasingSettingsService settingsService;
  @Mock private PurchasingAccessPolicy accessPolicy;

  private PurchasingStateService service;

  @BeforeEach
  void setUp() {
    service = new PurchasingStateService(settingsService, accessPolicy);
  }

  @Test
  void aSwitchedOffSurfaceRevealsNothingBeyondTheSwitch() {
    when(settingsService.isEnabled()).thenReturn(false);

    PurchasingStateDto state = service.state();

    assertThat(state)
        .isEqualTo(new PurchasingStateDto(false, false, false, false, false, false, false));
    verifyNoInteractions(accessPolicy);
    verify(settingsService).isEnabled();
  }

  @Test
  void anEnabledSurfaceReportsTheCallersCapabilities() {
    when(settingsService.isEnabled()).thenReturn(true);
    when(accessPolicy.isActiveDelegate()).thenReturn(true);
    when(accessPolicy.isGoverningLeadership()).thenReturn(false);
    when(accessPolicy.isBankStaff()).thenReturn(true);
    when(accessPolicy.managedUnitIds()).thenReturn(Set.of(UUID.randomUUID()));
    when(accessPolicy.canReadReport()).thenReturn(true);
    PurchasingSetting setting = new PurchasingSetting();
    setting.setPaused(true);
    when(settingsService.current()).thenReturn(setting);

    PurchasingStateDto state = service.state();

    assertThat(state.enabled()).isTrue();
    assertThat(state.delegate()).isTrue();
    assertThat(state.governing()).isFalse();
    assertThat(state.bankStaff()).isTrue();
    assertThat(state.managesUnit()).isTrue();
    assertThat(state.canReadReport()).isTrue();
    assertThat(state.paused()).isTrue();
  }

  @Test
  void aCallerWithoutManagedUnitsDoesNotManageAUnit() {
    when(settingsService.isEnabled()).thenReturn(true);
    when(accessPolicy.managedUnitIds()).thenReturn(Set.of());
    when(settingsService.current()).thenReturn(new PurchasingSetting());

    PurchasingStateDto state = service.state();

    assertThat(state.enabled()).isTrue();
    assertThat(state.delegate()).isFalse();
    assertThat(state.governing()).isFalse();
    assertThat(state.bankStaff()).isFalse();
    assertThat(state.managesUnit()).isFalse();
    assertThat(state.canReadReport()).isFalse();
    assertThat(state.paused()).isFalse();
  }
}
