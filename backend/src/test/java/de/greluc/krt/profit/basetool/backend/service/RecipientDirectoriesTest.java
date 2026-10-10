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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountGrant;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountGrantId;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountGrantRepository;
import de.greluc.krt.profit.basetool.backend.bank.internal.OrgUnitBankRecipientDirectory;
import de.greluc.krt.profit.basetool.backend.bank.internal.OrgUnitBankResponsibilityService;
import de.greluc.krt.profit.basetool.backend.model.OrgRelativeRole;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
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
 * The recipient directories the identity, orgunit and bank modules implement, and {@link
 * RecipientResolutionService} resolving every selector through them (REQ-NOTIF-008).
 */
@ExtendWith(MockitoExtension.class)
class RecipientDirectoriesTest {

  private static final UUID ORG_UNIT = UUID.randomUUID();
  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final UUID ALICE = UUID.randomUUID();
  private static final UUID BOB = UUID.randomUUID();

  @Mock private UserRepository userRepository;
  @Mock private RoleRepository roleRepository;
  @Mock private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Mock private BankAccountGrantRepository bankAccountGrantRepository;
  @Mock private OrgUnitBankResponsibilityService orgUnitBankResponsibilityService;

  private UserRoleRecipientDirectory roles;
  private OrgUnitMembershipRecipientDirectory orgUnits;
  private OrgUnitBankRecipientDirectory accounts;
  private RecipientResolutionService resolution;

  @BeforeEach
  void setUp() {
    roles = new UserRoleRecipientDirectory(userRepository, roleRepository);
    orgUnits = new OrgUnitMembershipRecipientDirectory(orgUnitMembershipRepository);
    accounts =
        new OrgUnitBankRecipientDirectory(
            bankAccountGrantRepository, orgUnitBankResponsibilityService);
    resolution = new RecipientResolutionService(roles, orgUnits, accounts);
  }

  @Test
  void identityResolvesRoleHoldersAndCatalogueCodes() {
    Role admin = new Role();
    admin.setCode("ADMIN");
    when(userRepository.findUserIdsByRoleCode("ADMIN")).thenReturn(Set.of(ALICE));
    when(roleRepository.findByCodeIgnoreCase("admin")).thenReturn(Optional.of(admin));
    when(roleRepository.findByCodeIgnoreCase("GUEST")).thenReturn(Optional.empty());

    assertThat(resolution.resolveByRole("ADMIN")).containsExactly(ALICE);
    assertThat(roles.catalogueRoleCode("admin")).contains("ADMIN");
    assertThat(roles.catalogueRoleCode("GUEST")).isEmpty();
  }

  @Test
  void anOrgRelativeOfficerIsAnOfficerRoleHolderInThatOrgUnit() {
    when(userRepository.findUserIdsByRoleCodeAndOrgUnitMembership(
            RecipientResolutionService.ROLE_OFFICER, ORG_UNIT))
        .thenReturn(Set.of(BOB));

    assertThat(resolution.resolveOrgRelative(OrgRelativeRole.OFFICER, ORG_UNIT))
        .containsExactly(BOB);
  }

  @Test
  void orgunitResolvesTheMembershipFlags() {
    when(orgUnitMembershipRepository.findLeadUserIdsByOrgUnit(ORG_UNIT)).thenReturn(Set.of(ALICE));
    when(orgUnitMembershipRepository.findLogisticianUserIdsByOrgUnit(ORG_UNIT))
        .thenReturn(Set.of(BOB));
    when(orgUnitMembershipRepository.findMissionManagerUserIdsByOrgUnit(ORG_UNIT))
        .thenReturn(Set.of(ALICE, BOB));

    assertThat(resolution.resolveOrgRelative(OrgRelativeRole.LEAD, ORG_UNIT))
        .containsExactly(ALICE);
    assertThat(resolution.resolveOrgRelative(OrgRelativeRole.LOGISTICIAN, ORG_UNIT))
        .containsExactly(BOB);
    assertThat(resolution.resolveOrgRelative(OrgRelativeRole.MISSION_MANAGER, ORG_UNIT))
        .containsExactlyInAnyOrder(ALICE, BOB);
  }

  @Test
  void bankResolvesGrantAndResponsibleHolders() {
    BankAccountGrant grant = new BankAccountGrant();
    grant.setId(new BankAccountGrantId(ALICE, ACCOUNT));
    when(bankAccountGrantRepository.findByAccountId(ACCOUNT)).thenReturn(List.of(grant));
    when(orgUnitBankResponsibilityService.resolveResponsibleHolderUserIds(ACCOUNT))
        .thenReturn(Set.of(BOB));

    assertThat(resolution.resolveAccountGrantHolders(ACCOUNT)).containsExactly(ALICE);
    assertThat(resolution.resolveAccountResponsibleHolders(ACCOUNT)).containsExactly(BOB);
  }
}
