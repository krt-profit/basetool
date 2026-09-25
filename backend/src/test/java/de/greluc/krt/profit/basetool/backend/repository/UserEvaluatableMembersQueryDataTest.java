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

import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link UserRepository#findEvaluatableMembers(UUID,
 * org.springframework.data.domain.Pageable)} against real Postgres: squadron members with the
 * {@code ADMIN} or {@code OFFICER} realm role are excluded from the promotion matrix.
 *
 * <p>Each test rolls back and asserts only on its own user ids.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserEvaluatableMembersQueryDataTest {

  @Autowired private UserRepository userRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private RoleRepository roleRepository;

  @PersistenceContext private EntityManager entityManager;

  /**
   * The squadron-scoped query returns a {@code KRT_MEMBER} and a role-less member but drops an
   * {@code OFFICER} and an {@code ADMIN}.
   */
  @Test
  void findEvaluatableMembers_squadronScope_keepsSimpleMembersDropsOfficersAndAdmins() {
    User simpleMember = createIridiumMember("KRT_MEMBER");
    User rolelessMember = createIridiumMember(null);
    User officer = createIridiumMember("OFFICER");
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
        .as("officers and admins are excluded from the promotion matrix (issue #817)")
        .doesNotContain(officer.getId(), admin.getId());
  }

  /**
   * Verifies the {@code :scopeSquadronId IS NULL} "all squadrons" branch (an admin without a pinned
   * squadron) applies the same officer/admin exclusion: a seeded officer never appears, a seeded
   * ordinary member does.
   */
  @Test
  void findEvaluatableMembers_allSquadronsScope_stillExcludesOfficers() {
    User simpleMember = createIridiumMember("KRT_MEMBER");
    User officer = createIridiumMember("OFFICER");
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
        .as("the officer is excluded even without a pinned squadron (issue #817)")
        .doesNotContain(officer.getId());
  }

  /**
   * Persists a user with the seeded role of the given code and a membership in the IRIDIUM
   * squadron.
   *
   * @param roleCode the seeded role code to assign (e.g. {@code OFFICER}, {@code ADMIN}, {@code
   *     KRT_MEMBER}), or {@code null} for a role-less ordinary member.
   * @return the persisted user.
   */
  private User createIridiumMember(String roleCode) {
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
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);
    return user;
  }
}
