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

package de.greluc.krt.profit.basetool.backend.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountGrant;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountGrantId;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountGrantRepository;
import de.greluc.krt.profit.basetool.backend.bank.internal.OrgUnitBankRecipientDirectory;
import de.greluc.krt.profit.basetool.backend.bank.internal.OrgUnitBankResponsibilityService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeInstallationRecipientDirectory;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeInstallationRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.identity.internal.UserRoleRecipientDirectory;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionRecipientDirectoryImpl;
import de.greluc.krt.profit.basetool.backend.notification.api.OrgRelativeRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.internal.OrgUnitMembershipRecipientDirectory;
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
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private MissionRepository missionRepository;
  @Mock private ExchangeInstallationRepository exchangeInstallationRepository;

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
    resolution =
        new RecipientResolutionService(
            roles,
            orgUnits,
            accounts,
            new MissionRecipientDirectoryImpl(missionParticipantRepository, missionRepository),
            new ExchangeInstallationRecipientDirectory(exchangeInstallationRepository));
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
  void anOrgRelativeOfficerIsTheSeatHoldersDirectlyInThatOrgUnit() {
    when(orgUnitMembershipRepository.findLeadershipUserIdsByOrgUnit(ORG_UNIT))
        .thenReturn(Set.of(BOB));

    assertThat(resolution.resolveOrgRelative(OrgRelativeRole.OFFICER, ORG_UNIT))
        .containsExactly(BOB);
    verifyNoInteractions(userRepository);
  }

  @Test
  void everySeatHolderResolvesWithoutReadingTheRoleMirror() {
    when(orgUnitMembershipRepository.findAllLeadershipUserIds()).thenReturn(Set.of(ALICE, BOB));

    assertThat(resolution.resolveSeatHolders()).containsExactlyInAnyOrder(ALICE, BOB);
    verifyNoInteractions(userRepository);
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
  void orgunitResolvesEverySeatHolderAsUnitLeadership() {
    when(orgUnitMembershipRepository.findLeadershipUserIdsByOrgUnit(ORG_UNIT))
        .thenReturn(Set.of(ALICE, BOB));

    assertThat(resolution.resolveOrgRelative(OrgRelativeRole.UNIT_LEADERSHIP, ORG_UNIT))
        .containsExactlyInAnyOrder(ALICE, BOB);
  }

  @Test
  void missionResolvesParticipantsAndTheNotCheckedInSubset() {
    UUID mission = UUID.randomUUID();
    when(missionParticipantRepository.findRegisteredUserIdsByMission(mission))
        .thenReturn(Set.of(ALICE, BOB));
    when(missionParticipantRepository.findNotCheckedInUserIdsByMission(mission))
        .thenReturn(Set.of(BOB));

    assertThat(resolution.resolveMissionParticipants(mission, false))
        .containsExactlyInAnyOrder(ALICE, BOB);
    assertThat(resolution.resolveMissionParticipants(mission, true)).containsExactly(BOB);
  }

  @Test
  void missionLeadershipIsTheOwnerAndTheManagers() {
    UUID mission = UUID.randomUUID();
    when(missionRepository.findOwnerUserIdById(mission)).thenReturn(Optional.of(ALICE));
    when(missionRepository.findManagerUserIdsById(mission)).thenReturn(Set.of(BOB));

    assertThat(resolution.resolveMissionLeadership(mission)).containsExactlyInAnyOrder(ALICE, BOB);
  }

  @Test
  void missionLeadershipOfAnOwnerlessMissionIsItsManagers() {
    UUID mission = UUID.randomUUID();
    when(missionRepository.findOwnerUserIdById(mission)).thenReturn(Optional.empty());
    when(missionRepository.findManagerUserIdsById(mission)).thenReturn(Set.of(BOB));

    assertThat(resolution.resolveMissionLeadership(mission)).containsExactly(BOB);
  }

  @Test
  void exchangeResolvesTheHoldersOfOneClientOrOfAnyClient() {
    UUID client = UUID.randomUUID();
    when(exchangeInstallationRepository.findHolderUserIdsByClient(client))
        .thenReturn(Set.of(ALICE));
    when(exchangeInstallationRepository.findAllHolderUserIds()).thenReturn(Set.of(ALICE, BOB));

    assertThat(resolution.resolveExchangeClientHolders(client)).containsExactly(ALICE);
    assertThat(resolution.resolveExchangeClientHolders(null)).containsExactlyInAnyOrder(ALICE, BOB);
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
