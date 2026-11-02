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

package de.greluc.krt.profit.basetool.backend.scope.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit tests for {@link OwnerScopeService}: the {@code SquadronVisibility} adapter forwards to the
 * scope kernel, and the Logistician-in-owner guard refuses a caller without that reach.
 */
@ExtendWith(MockitoExtension.class)
class OwnerScopeServiceTest {

  private static final UUID UNIT = UUID.randomUUID();

  @Mock private RequestScopeResolver requestScopeResolver;
  @Mock private AccessGateService accessGateService;

  @InjectMocks private OwnerScopeService service;

  @Test
  void canSeeSquadronAsksTheAccessGate() {
    when(accessGateService.canSeeSquadron(UNIT)).thenReturn(true);

    assertThat(service.canSeeSquadron(UNIT)).isTrue();
  }

  @Test
  void currentUserListScopeSquadronIdsComesFromTheResolver() {
    when(requestScopeResolver.currentUserListScopeSquadronIds()).thenReturn(Set.of(UNIT));

    assertThat(service.currentUserListScopeSquadronIds()).containsExactly(UNIT);
  }

  @Test
  void requireLogisticianInOwnerPassesWithReachInTheOwningUnit() {
    Squadron unit = new Squadron();
    unit.setId(UNIT);
    when(accessGateService.canActAsLogisticianInOwner(UNIT)).thenReturn(true);

    assertThatCode(() -> service.requireLogisticianInOwner(unit)).doesNotThrowAnyException();
  }

  @Test
  void requireLogisticianInOwnerRefusesWithoutReach() {
    Squadron unit = new Squadron();
    unit.setId(UNIT);
    when(accessGateService.canActAsLogisticianInOwner(UNIT)).thenReturn(false);

    assertThatThrownBy(() -> service.requireLogisticianInOwner(unit))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void requireLogisticianInOwnerRefusesAnOwnerlessRowForANonAdmin() {
    when(accessGateService.canActAsLogisticianInOwner(null)).thenReturn(false);

    assertThatThrownBy(() -> service.requireLogisticianInOwner(null))
        .isInstanceOf(AccessDeniedException.class);
  }
}
