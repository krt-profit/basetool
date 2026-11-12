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
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitSeatEligibility;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The units a new row is stamped on without a pick (REQ-ORG-032, REQ-SEC-015): the owner's direct
 * memberships on active units while they hold {@code KRT Member}, the same units the owner picker
 * offers, so a create never fails for a pick the form could not show. The owner here is a member of
 * the active Hel and of a deactivated SK, so the active-unit read answers Hel alone.
 */
@ExtendWith(MockitoExtension.class)
class OrgUnitStampingStampableUnitsTest {

  private static final UUID HEL = UUID.randomUUID();
  private static final UUID PINNED_STAFFEL = UUID.randomUUID();

  @Mock private RequestScopeResolver requestScopeResolver;
  @Mock private AccessGateService accessGateService;
  @Mock private AuthHelperService authHelper;
  @Mock private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private UserRepository userRepository;

  private OrgUnitStampingService service;
  private User owner;
  private Squadron hel;

  @BeforeEach
  void setUp() {
    service =
        new OrgUnitStampingService(
            requestScopeResolver,
            accessGateService,
            authHelper,
            orgUnitRepository,
            new OrgUnitSeatEligibility(orgUnitMembershipRepository, userRepository));
    owner = new User();
    owner.setId(UUID.randomUUID());
    hel = new Squadron();
    hel.setId(HEL);
    lenient().when(orgUnitRepository.findById(HEL)).thenReturn(Optional.of(hel));
    lenient().when(requestScopeResolver.currentPin()).thenReturn(Optional.empty());
    when(orgUnitMembershipRepository.findActiveOrgUnitIdsByUserId(owner.getId()))
        .thenReturn(Set.of(HEL));
  }

  @Test
  void aPersonalRowWithoutAPickLandsInTheOnlyActiveMembership() {
    when(userRepository.hasAnyRoleCode(owner.getId(), List.of(Roles.KRT_MEMBER))).thenReturn(true);

    assertThat(service.resolveOrgUnitForPickerOutputNullable(owner, null)).isSameAs(hel);
  }

  @Test
  void aMissionWithoutAPickOrPinLandsInTheOnlyActiveMembership() {
    when(userRepository.hasAnyRoleCode(owner.getId(), List.of(Roles.KRT_MEMBER))).thenReturn(true);

    assertThat(service.resolveOrgUnitForUnitOwnedAggregate(owner, null)).isSameAs(hel);
  }

  @Test
  void aConnectedApplicationAndAnImportStampTheOnlyActiveMembership() {
    when(userRepository.hasAnyRoleCode(owner.getId(), List.of(Roles.KRT_MEMBER))).thenReturn(true);

    assertThat(service.resolveOrgUnitForClientCreate(owner)).isSameAs(hel);
    assertThat(service.resolveOrgUnitForImport(owner)).isSameAs(hel);
  }

  @Test
  void anAdminOnBehalfCreateWithoutAPickStampsTheMembersOnlyActiveMembership() {
    when(userRepository.hasAnyRoleCode(owner.getId(), List.of(Roles.KRT_MEMBER))).thenReturn(true);

    assertThat(service.resolveOrgUnitForAdminOnBehalf(owner, null)).isSameAs(hel);
  }

  @Test
  void anOwnerWithoutKrtMemberKeepsANewPersonalRowWithoutAUnit() {
    when(userRepository.hasAnyRoleCode(owner.getId(), List.of(Roles.KRT_MEMBER))).thenReturn(false);

    assertThat(service.resolveOrgUnitForPickerOutputNullable(owner, null)).isNull();
    assertThat(service.resolveOrgUnitForClientCreate(owner)).isNull();
    assertThat(service.resolveOrgUnitForImport(owner)).isNull();
    assertThat(service.resolveOrgUnitForAdminOnBehalf(owner, null)).isNull();
  }

  @Test
  void anOwnerWithoutKrtMemberMayNotPickAFormerUnitOutsideTheCallersScope() {
    when(userRepository.hasAnyRoleCode(owner.getId(), List.of(Roles.KRT_MEMBER))).thenReturn(false);
    when(accessGateService.canEditSquadron(HEL)).thenReturn(false);

    assertThatThrownBy(() -> service.resolveOrgUnitForPickerOutputNullable(owner, HEL))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void aStoredMembershipOfAnOwnerWithoutKrtMemberCannotBeChosenExplicitly() {
    when(userRepository.hasAnyRoleCode(owner.getId(), List.of(Roles.KRT_MEMBER))).thenReturn(false);

    assertThatThrownBy(() -> service.resolveOwnerMembershipTarget(owner, HEL))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.resolveOrgUnitForAdminOnBehalf(owner, HEL))
        .isInstanceOf(BadRequestException.class);
    assertThat(service.resolveOwnerMembershipTarget(owner, null)).isNull();
  }

  @Test
  void anExplicitPersonalTargetMustBeAnActiveDirectMembership() {
    when(userRepository.hasAnyRoleCode(owner.getId(), List.of(Roles.KRT_MEMBER))).thenReturn(true);

    assertThat(service.resolveOwnerMembershipTarget(owner, HEL)).isSameAs(hel);
    assertThatThrownBy(() -> service.resolveOwnerMembershipTarget(owner, PINNED_STAFFEL))
        .isInstanceOf(BadRequestException.class);
    hel.setActive(false);
    assertThatThrownBy(() -> service.resolveOwnerMembershipTarget(owner, HEL))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void aMissionWithoutAPickLandsOnTheHonouredPin() {
    when(userRepository.hasAnyRoleCode(owner.getId(), List.of(Roles.KRT_MEMBER))).thenReturn(true);
    when(requestScopeResolver.currentPin()).thenReturn(Optional.of(PINNED_STAFFEL));
    Squadron pinned = new Squadron();
    pinned.setId(PINNED_STAFFEL);
    when(orgUnitRepository.findById(PINNED_STAFFEL)).thenReturn(Optional.of(pinned));

    assertThat(service.resolveOrgUnitForUnitOwnedAggregate(owner, null)).isSameAs(pinned);
  }

  @Test
  void aPersonalRowIgnoresAPinOutsideTheOwnersActiveMemberships() {
    when(userRepository.hasAnyRoleCode(owner.getId(), List.of(Roles.KRT_MEMBER))).thenReturn(true);
    when(requestScopeResolver.currentPin()).thenReturn(Optional.of(PINNED_STAFFEL));

    assertThat(service.resolveOrgUnitForPickerOutputNullable(owner, null)).isSameAs(hel);
  }
}
