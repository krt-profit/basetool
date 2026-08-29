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
 * Real-Postgres regression coverage for {@code UserDeletionService.deleteUser} referential
 * integrity, covering the two ways the delete has broken in production.
 *
 * <p>{@code deleteUser_ownerOfMissionAndClaimStamper_…}: an ex-member who owns a mission (and
 * therefore a {@code mission_ownership} companion row) and who has stamped a {@code material_claim}
 * must be deletable without tripping a foreign-key violation (SQLSTATE 23503) on the FK-less {@code
 * mission_ownership.owner_id} (V63) and {@code material_claim.claimed_by_user_id} (V131) columns.
 * Guards against the latent gap where {@code deleteUser} reassigned {@code mission.owner} but left
 * its companion (and the audit stamp) pointed at the now-deleted user.
 *
 * <p>{@code deleteUser_withOrgUnitMembership_…}: the complementary Hibernate-side failure — a
 * database-level {@code ON DELETE CASCADE} row that was nevertheless pulled into the persistence
 * context before the delete, so the flush aborted with {@code TransientPropertyValueException}
 * instead of any SQL error at all.
 *
 * <p>Uses {@link Transactional} rollback plus an explicit {@link EntityManager#flush()} to force
 * the {@code DELETE FROM app_user} statement to execute: PostgreSQL checks the (non-deferrable) FKs
 * at statement time, and Hibernate runs its transient-reference check at the same moment, so the
 * flush is exactly where both failures surface without their fixes.
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
   * Mocked so the delete-time Keycloak existence check does not reach out over HTTP — the test
   * profile points {@code admin-url} at an unreachable host and the check is deliberately
   * fail-closed, so the real bean would refuse every deletion here. The Mockito default for {@code
   * userExists} is {@code false} — "the account is gone" — which is the precondition every deletion
   * test in this class relies on.
   */
  @MockitoBean private KeycloakService keycloakService;

  /**
   * Unused by the tests, and deliberately present: a {@code @MockitoBean} is part of the Spring
   * context cache key, so mocking Keycloak alone would give this class a context of its own instead
   * of the shared plain one. Declaring the <em>same</em> override pair as {@code
   * UserManagementTest} — which already owns a context for its {@code JwtDecoder} — lets both
   * classes share a single context, leaving the suite's context count unchanged. Without this field
   * the extra context pushes the 4 400-test run past the 2 GB test heap and every test in this
   * class fails with {@code OutOfMemoryError} while still passing in isolation.
   */
  @MockitoBean private JwtDecoder jwtDecoder;

  @Test
  void
      deleteUser_ownerOfMissionAndClaimStamper_reassignsCompanionAndNullsStampWithoutFkViolation() {
    // Given a fallback admin (so deleteUser has a reassignment target)...
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

    // ...and an ex-member (gone from Keycloak — the only kind deleteUser touches) who owns a
    // mission (hence a mission_ownership companion) and has stamped a material claim.
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
    // setMissionOwner mirrors production: it sets mission.owner AND upserts the companion row.
    missionService.setMissionOwner(missionId, exMember.getId());

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

    // Flush the seed to the DB and detach it so the post-delete reads see fresh state.
    entityManager.flush();
    entityManager.clear();

    // Sanity: before the delete the companion + claim point at the ex-member.
    assertThat(
            missionOwnershipRepository.findByMissionId(missionId).orElseThrow().getOwner().getId())
        .isEqualTo(exMemberId);
    assertThat(materialClaimRepository.findById(claimId).orElseThrow().getClaimedByUser().getId())
        .isEqualTo(exMemberId);
    entityManager.clear();

    // When the ex-member is deleted and the DELETE is flushed to Postgres...
    assertThatNoException()
        .isThrownBy(
            () -> {
              userDeletionService.deleteUser(exMemberId);
              entityManager.flush();
            });
    entityManager.clear();

    // Then no 23503 fired, the user is gone, the mission survives with its owner + companion both
    // reassigned to the same admin, and the claim survives with its audit stamp nulled.
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
   * An ex-member who still holds an org-unit membership must be deletable. {@code
   * org_unit_membership.user_id} is cleared by the database {@code ON DELETE CASCADE} (V98), so
   * {@code deleteUser} deliberately does not remove the rows itself — but the bank
   * responsible-holder snapshot it takes right before the delete used to resolve the affected org
   * units through {@code findAllByIdUserId}, attaching the membership entities to the persistence
   * context. Those managed rows outlived {@code userRepository.delete(user)} still pointing at the
   * removed {@code User}, and the flush aborted with {@code TransientPropertyValueException}
   * ({@code OrgUnitMembership.user -> User}) — a 500 on {@code DELETE /api/v1/users/{id}} for every
   * user who was a member of anything, which is nearly all of them. The snapshot now uses the bare
   * id projection {@code findOrgUnitIdsByUserId}, leaving the persistence context empty.
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

    // Mirrors OrgUnitMembershipService.addSpecialCommandMembership: the kind column is written by
    // the V95 trigger, so the in-memory value is only there for the immediate read-back.
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
   * The FK-less personal stores must be purged by the delete, not left behind. {@code
   * personal_blueprint.owner_user_id}, {@code personal_inventory_item.owner_user_id} and {@code
   * member_evaluation.user_id} hold the Keycloak subject as plain text with no foreign key to
   * {@code app_user}, so nothing cascades and no retention job reaches them — before REQ-DATA-008
   * required the explicit purge they survived the account indefinitely (production carried 16
   * orphaned blueprints and 12 orphaned evaluations from earlier deletions), stayed undiscoverable
   * because every lookup is keyed by a subject no roster can still offer, and would have been
   * silently re-adopted had the same Keycloak subject ever returned.
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
    // owner_user_id / user_id store app_user.id rendered as text — the JWT subject IS the primary
    // key.
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
