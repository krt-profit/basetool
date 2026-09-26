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
import static org.assertj.core.api.Assertions.assertThatNoException;

import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionOwnership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.model.PersonalInventoryItem;
import de.greluc.krt.profit.basetool.backend.model.PersonalInventoryLocationType;
import de.greluc.krt.profit.basetool.backend.model.QualityRequirement;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialClaimRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionOwnershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalInventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-Postgres tests for the referential integrity of {@code UserDeletionService.deleteUser}: a
 * user who owns a mission, stamped a material claim or holds an org-unit membership must be
 * deletable without a foreign-key violation or a {@code TransientPropertyValueException}.
 *
 * <p>Each test flushes explicitly inside a rolled-back transaction, which is where both failure
 * modes surface.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserDeletionForeignKeyIntegrityTest {

  @Autowired private UserDeletionService userDeletionService;
  @Autowired private MissionService missionService;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private MissionOwnershipRepository missionOwnershipRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private PersonalBlueprintRepository personalBlueprintRepository;
  @Autowired private PersonalInventoryItemRepository personalInventoryItemRepository;
  @Autowired private MaterialClaimRepository materialClaimRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private SpecialCommandRepository specialCommandRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private EntityManager entityManager;

  /**
   * Mocked so the fail-closed delete-time Keycloak existence check makes no HTTP call; its default
   * {@code userExists = false} is the precondition every deletion test relies on.
   */
  @MockitoBean private KeycloakService keycloakService;

  /**
   * Unused, but declared so this class shares its Spring test context with {@code
   * UserManagementTest}, which mocks the same pair of beans.
   */
  @MockitoBean private JwtDecoder jwtDecoder;

  @Test
  void
      deleteUser_ownerOfMissionAndClaimStamper_reassignsCompanionAndNullsStampWithoutFkViolation() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    Role adminRole =
        roleRepository
            .findByNameIgnoreCase("ADMIN")
            .orElseGet(
                () -> {
                  Role fresh = new Role();
                  fresh.setName("ADMIN");
                  fresh.setCode("ADMIN");
                  return roleRepository.save(fresh);
                });
    User admin = new User();
    admin.setId(UUID.randomUUID());
    admin.setUsername("fk-admin-" + tag);
    admin.setRank(1);
    admin.setInKeycloak(true);
    admin.getRoles().add(adminRole);
    userRepository.save(admin);

    User exMember = new User();
    exMember.setId(UUID.randomUUID());
    exMember.setUsername("fk-exmember-" + tag);
    exMember.setRank(1);
    exMember.setInKeycloak(false);
    userRepository.save(exMember);

    Mission mission = new Mission();
    mission.setName("FK Mission " + tag);
    mission.setStatus("PLANNED");
    mission.setIsInternal(false);
    mission = missionRepository.save(mission);
    UUID missionId = mission.getId();
    missionService.updateMissionOwner(missionId, exMember.getId(), 0L);

    SpecialCommand sk = new SpecialCommand();
    sk.setName("FK-SK-" + tag);
    sk.setShorthand("S" + tag);
    sk.setProfitEligible(true);
    sk = specialCommandRepository.save(sk);

    Squadron squadron = new Squadron();
    squadron.setName("FK-SQ-" + tag);
    squadron.setShorthand("Q" + tag);
    squadron.setProfitEligible(true);
    squadron = squadronRepository.save(squadron);

    Material material = new Material();
    material.setName("FK-Mat-" + tag);
    material.setType(MaterialType.RAW);
    material = materialRepository.save(material);

    JobOrder order =
        JobOrder.builder()
            .responsibleOrgUnit(sk)
            .requestingOrgUnit(squadron)
            .handle("fk-test")
            .status(JobOrderStatus.OPEN)
            .build();
    order.addMaterial(
        JobOrderMaterial.builder().material(material).minQuality(700).amount(10.0).build());
    order = jobOrderRepository.save(order);

    MaterialClaim claim =
        MaterialClaim.builder()
            .jobOrder(order)
            .material(material)
            .qualityRequirement(QualityRequirement.GOOD)
            .claimingOrgUnit(squadron)
            .amount(5.0)
            .claimedByUser(exMember)
            .build();
    claim = materialClaimRepository.save(claim);
    UUID claimId = claim.getId();
    UUID exMemberId = exMember.getId();

    entityManager.flush();
    entityManager.clear();

    assertThat(
            missionOwnershipRepository.findByMissionId(missionId).orElseThrow().getOwner().getId())
        .isEqualTo(exMemberId);
    assertThat(materialClaimRepository.findById(claimId).orElseThrow().getClaimedByUser().getId())
        .isEqualTo(exMemberId);
    entityManager.clear();

    assertThatNoException()
        .isThrownBy(
            () -> {
              userDeletionService.deleteUser(exMemberId);
              entityManager.flush();
            });
    entityManager.clear();

    assertThat(userRepository.findById(exMemberId)).isEmpty();

    Mission reloaded = missionRepository.findById(missionId).orElseThrow();
    assertThat(reloaded.getOwner()).isNotNull();
    assertThat(reloaded.getOwner().getId()).isNotEqualTo(exMemberId);
    UUID newOwnerId = reloaded.getOwner().getId();

    MissionOwnership companion =
        missionOwnershipRepository.findByMissionId(missionId).orElseThrow();
    assertThat(companion.getOwner()).isNotNull();
    assertThat(companion.getOwner().getId())
        .as("mission_ownership.owner must mirror mission.owner after reassignment")
        .isEqualTo(newOwnerId);

    MaterialClaim reloadedClaim = materialClaimRepository.findById(claimId).orElseThrow();
    assertThat(reloadedClaim.getClaimedByUser())
        .as("the audit-only claim stamp is nulled, not reassigned, and the claim survives")
        .isNull();
  }

  /**
   * Verifies that a user holding an org-unit membership is deletable: the membership rows, removed
   * by {@code ON DELETE CASCADE}, must not be loaded into the persistence context before the flush.
   */
  @Test
  void deleteUser_withOrgUnitMembership_doesNotTripTransientPropertyValueOnFlush() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    Role adminRole =
        roleRepository
            .findByNameIgnoreCase("ADMIN")
            .orElseGet(
                () -> {
                  Role fresh = new Role();
                  fresh.setName("ADMIN");
                  fresh.setCode("ADMIN");
                  return roleRepository.save(fresh);
                });
    User admin = new User();
    admin.setId(UUID.randomUUID());
    admin.setUsername("mem-admin-" + tag);
    admin.setRank(1);
    admin.setInKeycloak(true);
    admin.getRoles().add(adminRole);
    userRepository.save(admin);

    User exMember = new User();
    exMember.setId(UUID.randomUUID());
    exMember.setUsername("mem-exmember-" + tag);
    exMember.setRank(1);
    exMember.setInKeycloak(false);
    exMember = userRepository.save(exMember);
    UUID exMemberId = exMember.getId();

    SpecialCommand sk = new SpecialCommand();
    sk.setName("MEM-SK-" + tag);
    sk.setShorthand("M" + tag);
    sk.setProfitEligible(true);
    sk = specialCommandRepository.save(sk);

    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(exMemberId, sk.getId()));
    membership.setUser(exMember);
    membership.setKind(OrgUnitKind.SPECIAL_COMMAND);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);

    entityManager.flush();
    entityManager.clear();

    assertThat(orgUnitMembershipRepository.countByIdUserId(exMemberId))
        .as("precondition: the ex-member still holds the membership the cascade will remove")
        .isEqualTo(1);

    final UUID deletedId = exMemberId;
    assertThatNoException()
        .isThrownBy(
            () -> {
              userDeletionService.deleteUser(deletedId);
              entityManager.flush();
            });
    entityManager.clear();

    assertThat(userRepository.findById(exMemberId)).isEmpty();
    assertThat(orgUnitMembershipRepository.countByIdUserId(exMemberId))
        .as("the DB ON DELETE CASCADE removed the membership row along with the user")
        .isZero();
  }

  /**
   * Verifies that the delete purges the personal stores keyed by the user's subject ({@code
   * personal_blueprint}, {@code personal_inventory_item}, {@code member_evaluation}, REQ-DATA-008).
   */
  @Test
  void deleteUser_purgesTheFkLessPersonalStoresKeyedByTheKeycloakSubject() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    Role adminRole =
        roleRepository
            .findByNameIgnoreCase("ADMIN")
            .orElseGet(
                () -> {
                  Role fresh = new Role();
                  fresh.setName("ADMIN");
                  fresh.setCode("ADMIN");
                  return roleRepository.save(fresh);
                });
    User admin = new User();
    admin.setId(UUID.randomUUID());
    admin.setUsername("purge-admin-" + tag);
    admin.setRank(1);
    admin.setInKeycloak(true);
    admin.getRoles().add(adminRole);
    userRepository.save(admin);

    User exMember = new User();
    exMember.setId(UUID.randomUUID());
    exMember.setUsername("purge-exmember-" + tag);
    exMember.setRank(1);
    exMember.setInKeycloak(false);
    exMember = userRepository.save(exMember);
    final UUID exMemberId = exMember.getId();
    final UUID ownerUserId = exMemberId;

    personalBlueprintRepository.save(
        PersonalBlueprint.builder()
            .ownerUserId(ownerUserId)
            .productKey("purge-product-" + tag)
            .productName("Purge Product " + tag)
            .build());
    personalInventoryItemRepository.save(
        PersonalInventoryItem.builder()
            .ownerUserId(ownerUserId)
            .name("Purge Item " + tag)
            .note("a free-text note that must not outlive the account")
            .locationUexId(1)
            .locationType(PersonalInventoryLocationType.CITY)
            .locationNameSnapshot("Purge City")
            .quantity(1)
            .build());

    entityManager.flush();
    entityManager.clear();

    assertThat(personalBlueprintRepository.findAllByOwnerUserId(ownerUserId, Pageable.unpaged()))
        .as("precondition: the ex-member owns a personal blueprint")
        .hasSize(1);
    assertThat(
            personalInventoryItemRepository.findAllByOwnerUserId(ownerUserId, Pageable.unpaged()))
        .as("precondition: the ex-member owns a Mein-Inventar row")
        .hasSize(1);

    assertThatNoException()
        .isThrownBy(
            () -> {
              userDeletionService.deleteUser(exMemberId);
              entityManager.flush();
            });
    entityManager.clear();

    assertThat(userRepository.findById(exMemberId)).isEmpty();
    assertThat(personalBlueprintRepository.findAllByOwnerUserId(ownerUserId, Pageable.unpaged()))
        .as("personal blueprints are purged, auto-granted defaults included")
        .isEmpty();
    assertThat(
            personalInventoryItemRepository.findAllByOwnerUserId(ownerUserId, Pageable.unpaged()))
        .as("Mein Inventar is purged, free-text notes and all")
        .isEmpty();
  }
}
