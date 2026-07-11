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
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-Postgres regression coverage for {@code UserDeletionService.deleteUser} referential
 * integrity: an ex-member who owns a mission (and therefore a {@code mission_ownership} companion
 * row) and who has stamped a {@code material_claim} must be deletable without tripping a
 * foreign-key violation (SQLSTATE 23503) on the FK-less {@code mission_ownership.owner_id} (V63)
 * and {@code material_claim.claimed_by_user_id} (V131) columns. Guards against the latent gap where
 * {@code deleteUser} reassigned {@code mission.owner} but left its companion (and the audit stamp)
 * pointed at the now-deleted user.
 *
 * <p>Uses {@link Transactional} rollback plus an explicit {@link EntityManager#flush()} to force
 * the {@code DELETE FROM app_user} statement to execute: PostgreSQL checks the (non-deferrable) FKs
 * at statement time, so the flush is exactly where the 23503 would surface without the fix.
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
  @Autowired private MaterialClaimRepository materialClaimRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private SpecialCommandRepository specialCommandRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private EntityManager entityManager;

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
}
