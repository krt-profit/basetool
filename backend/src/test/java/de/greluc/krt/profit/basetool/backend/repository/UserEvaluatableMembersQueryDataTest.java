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

package de.greluc.krt.profit.basetool.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link UserRepository#findEvaluatableMembers(UUID,
 * org.springframework.data.domain.Pageable)} against real Postgres: squadron members with the
 * {@code ADMIN} realm role or a leadership seat are excluded from the promotion matrix.
 *
 * <p>Each test rolls back and asserts only on its own user ids.
 */
@SpringBootTest
@Transactional
class UserEvaluatableMembersQueryDataTest {

  @Autowired private UserRepository userRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private RoleRepository roleRepository;

  @PersistenceContext private EntityManager entityManager;

  /**
   * The squadron-scoped query returns a {@code KRT_MEMBER} and a role-less member but drops a
   * leadership-seat holder and an {@code ADMIN}.
   */
  @Test
  void findEvaluatableMembers_squadronScope_keepsSimpleMembersDropsSeatHoldersAndAdmins() {
    User simpleMember = createIridiumMember("KRT_MEMBER");
    User rolelessMember = createIridiumMember(null);
    User leader = createIridiumLeader();
    User admin = createIridiumMember("ADMIN");
    entityManager.flush();
    entityManager.clear();

    List<UUID> evaluatableIds =
        userRepository
            .findEvaluatableMembers(java.util.Set.of(Squadron.IRIDIUM_ID), PageRequest.of(0, 5000))
            .map(User::getId)
            .getContent();

    assertThat(evaluatableIds)
        .as("simple squadron members (with or without the KRT_MEMBER role) stay evaluatable")
        .contains(simpleMember.getId(), rolelessMember.getId());
    assertThat(evaluatableIds)
        .as("seat holders and admins are excluded from the promotion matrix")
        .doesNotContain(leader.getId(), admin.getId());
  }

  /**
   * Verifies the {@code :scopeSquadronId IS NULL} "all squadrons" branch (an admin without a pinned
   * squadron) applies the same seat/admin exclusion: a seat holder never appears, a seeded ordinary
   * member does.
   */
  @Test
  void findEvaluatableMembers_allSquadronsScope_stillExcludesSeatHolders() {
    User simpleMember = createIridiumMember("KRT_MEMBER");
    User leader = createIridiumLeader();
    entityManager.flush();
    entityManager.clear();

    List<UUID> evaluatableIds =
        userRepository
            .findEvaluatableMembers(null, PageRequest.of(0, 5000))
            .map(User::getId)
            .getContent();

    assertThat(evaluatableIds)
        .as("the ordinary member is evaluatable in all-squadrons mode")
        .contains(simpleMember.getId());
    assertThat(evaluatableIds)
        .as("the seat holder is excluded even without a pinned squadron")
        .doesNotContain(leader.getId());
  }

  /**
   * Persists a user with the seeded role of the given code and a membership in the IRIDIUM
   * squadron.
   *
   * @param roleCode the seeded role code to assign (e.g. {@code ADMIN}, {@code KRT_MEMBER}), or
   *     {@code null} for a role-less ordinary member.
   * @return the persisted user.
   */
  private User createIridiumMember(String roleCode) {
    return createIridiumMember(roleCode, MembershipRole.MEMBER);
  }

  /**
   * Persists a role-less user holding an Ensign seat in the IRIDIUM squadron.
   *
   * @return the persisted user.
   */
  private User createIridiumLeader() {
    return createIridiumMember(null, MembershipRole.ENSIGN);
  }

  private User createIridiumMember(String roleCode, MembershipRole membershipRole) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("promo-" + UUID.randomUUID());
    if (roleCode != null) {
      Role role =
          roleRepository
              .findByCode(roleCode)
              .orElseThrow(() -> new IllegalStateException(roleCode + " role not seeded"));
      user.setRoles(Set.of(role));
    }
    userRepository.save(user);

    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), Squadron.IRIDIUM_ID));
    membership.setUser(user);
    membership.setRole(membershipRole);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);
    return user;
  }
}
