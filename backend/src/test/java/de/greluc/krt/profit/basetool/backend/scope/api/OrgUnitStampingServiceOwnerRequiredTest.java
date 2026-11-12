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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.kernel.AppExceptionKind;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitSeatEligibility;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies that the "pin, else choose" rejection of both stamping entry points carries its own
 * stable problem code (REQ-ORG-017, REQ-ORG-023), on which the frontend branches.
 */
@ExtendWith(MockitoExtension.class)
class OrgUnitStampingServiceOwnerRequiredTest {

  @Mock private RequestScopeResolver requestScopeResolver;
  @Mock private AccessGateService accessGateService;
  @Mock private AuthHelperService authHelper;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private OrgUnitSeatEligibility seatEligibility;

  private OrgUnitStampingService service;
  private User targetUser;

  @BeforeEach
  void setUp() {
    service =
        new OrgUnitStampingService(
            requestScopeResolver,
            accessGateService,
            authHelper,
            orgUnitRepository,
            seatEligibility);

    targetUser = new User();
    targetUser.setId(UUID.randomUUID());

    lenient()
        .when(seatEligibility.findStampableOrgUnitIds(targetUser.getId()))
        .thenReturn(Set.of(UUID.randomUUID(), UUID.randomUUID()));
  }

  @Test
  void theUnitOwnedAggregatePickerRefusesWithTheStableOwnerRequiredCode() {
    when(requestScopeResolver.currentPin()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.resolveOrgUnitForUnitOwnedAggregate(targetUser, null))
        .isInstanceOf(OwnerOrgUnitRequiredException.class)
        .extracting(ex -> ((OwnerOrgUnitRequiredException) ex).code())
        .isEqualTo("OWNER_ORG_UNIT_REQUIRED");
  }

  @Test
  void theOrgUnitPickerRefusesWithTheStableOwnerRequiredCode() {
    when(requestScopeResolver.currentPin()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.resolveOrgUnitForPickerOutputNullable(targetUser, null))
        .isInstanceOf(OwnerOrgUnitRequiredException.class)
        .extracting(ex -> ((OwnerOrgUnitRequiredException) ex).code())
        .isEqualTo("OWNER_ORG_UNIT_REQUIRED");
  }

  @Test
  void theRefusalStaysA400AndIsNotAConflictOrAPermissionFailure() {
    when(requestScopeResolver.currentPin()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.resolveOrgUnitForPickerOutputNullable(targetUser, null))
        .isInstanceOf(OwnerOrgUnitRequiredException.class)
        .extracting(ex -> ((OwnerOrgUnitRequiredException) ex).status())
        .isEqualTo(AppExceptionKind.BAD_REQUEST.status());
  }

  @Test
  void anHonourablePinStillStampsWithoutRefusing() {
    UUID pinned = UUID.randomUUID();
    when(seatEligibility.findStampableOrgUnitIds(targetUser.getId()))
        .thenReturn(Set.of(pinned, UUID.randomUUID()));
    when(requestScopeResolver.currentPin()).thenReturn(Optional.of(pinned));
    Squadron squadron = new Squadron();
    squadron.setId(pinned);
    when(orgUnitRepository.findById(pinned)).thenReturn(Optional.of(squadron));

    assertThat(service.resolveOrgUnitForPickerOutputNullable(targetUser, null)).isSameAs(squadron);
  }
}
