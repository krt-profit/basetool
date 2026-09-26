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
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
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
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for the Staffel-scoped {@link UserRepository} listing, search and reference
 * queries against real Postgres (REQ-ORG-017).
 *
 * <p>Covers a {@code null} scope collection binding cleanly to the {@code IN} clause and a
 * two-element scope returning the members of both Staffeln. Each test rolls back.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserScopedQueriesDataTest {

  /** Common username prefix so the substring-search queries can target only this suite's users. */
  private static final String PREFIX = "scopetest-";

  @Autowired private UserRepository userRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private SquadronRepository squadronRepository;

  @PersistenceContext private EntityManager entityManager;

  /**
   * The {@code null} scope (admin "all squadrons" mode) must bind to every IN-bearing scope query
   * without a dialect-level failure and return all users — both Staffel members and the unassigned
   * (admin-like) user.
   */
  @Test
  void nullScope_bindsCleanlyAndReturnsEveryUser() {
    UUID secondSquadronId = createSecondSquadron();
    User iridiumMember = createMember(Squadron.IRIDIUM_ID);
    User secondMember = createMember(secondSquadronId);
    User unassigned = createUnassigned();
    flushAndClear();

    List<UUID> paged =
        userRepository.findAllScoped(null, PageRequest.of(0, 5000)).map(User::getId).getContent();
    List<UUID> list =
        userRepository.findAllScopedList(null, Sort.by("username")).stream()
            .map(User::getId)
            .toList();
    List<UUID> reference =
        userRepository.findAllReferenceScoped(null).stream().map(UserReferenceDto::id).toList();
    List<UUID> searchPaged =
        userRepository
            .searchScoped(PREFIX, null, PageRequest.of(0, 5000))
            .map(User::getId)
            .getContent();
    List<UUID> searchList =
        userRepository.searchScopedList(PREFIX, null).stream().map(User::getId).toList();

    assertThat(paged)
        .as("findAllScoped(null) returns every user incl. the unassigned bucket")
        .contains(iridiumMember.getId(), secondMember.getId(), unassigned.getId());
    assertThat(list).contains(iridiumMember.getId(), secondMember.getId(), unassigned.getId());
    assertThat(reference).contains(iridiumMember.getId(), secondMember.getId(), unassigned.getId());
    assertThat(searchPaged)
        .as("searchScoped(null) finds the prefixed members and the unassigned user")
        .contains(iridiumMember.getId(), secondMember.getId(), unassigned.getId());
    assertThat(searchList)
        .contains(iridiumMember.getId(), secondMember.getId(), unassigned.getId());
  }

  /**
   * The two-element scope (a dual-Staffel member's union, REQ-ORG-017) expands the {@code IN}
   * clause correctly and returns the members of <em>both</em> Staffeln plus the always-visible
   * unassigned bucket.
   */
  @Test
  void twoSquadronUnionScope_returnsBothStaffelnMembers() {
    UUID secondSquadronId = createSecondSquadron();
    User iridiumMember = createMember(Squadron.IRIDIUM_ID);
    User secondMember = createMember(secondSquadronId);
    User unassigned = createUnassigned();
    flushAndClear();

    Set<UUID> union = Set.of(Squadron.IRIDIUM_ID, secondSquadronId);
    List<UUID> paged =
        userRepository.findAllScoped(union, PageRequest.of(0, 5000)).map(User::getId).getContent();
    List<UUID> reference =
        userRepository.findAllReferenceScoped(union).stream().map(UserReferenceDto::id).toList();
    List<UUID> searchPaged =
        userRepository
            .searchScoped(PREFIX, union, PageRequest.of(0, 5000))
            .map(User::getId)
            .getContent();

    assertThat(paged)
        .as("a two-Staffel union returns both Staffeln's members and the unassigned bucket")
        .contains(iridiumMember.getId(), secondMember.getId(), unassigned.getId());
    assertThat(reference).contains(iridiumMember.getId(), secondMember.getId(), unassigned.getId());
    assertThat(searchPaged).contains(iridiumMember.getId(), secondMember.getId());
  }

  /**
   * A single-Staffel scope narrows to that Staffel's members (plus the unassigned bucket) and
   * excludes a member of a different Staffel — proving the {@code IN} predicate filters rather than
   * collapsing to "all".
   */
  @Test
  void singleSquadronScope_excludesForeignStaffelMember() {
    UUID secondSquadronId = createSecondSquadron();
    User iridiumMember = createMember(Squadron.IRIDIUM_ID);
    User secondMember = createMember(secondSquadronId);
    User unassigned = createUnassigned();
    flushAndClear();

    List<UUID> paged =
        userRepository
            .findAllScoped(Set.of(Squadron.IRIDIUM_ID), PageRequest.of(0, 5000))
            .map(User::getId)
            .getContent();

    assertThat(paged)
        .as("the IRIDIUM member and the unassigned bucket are in scope")
        .contains(iridiumMember.getId(), unassigned.getId());
    assertThat(paged)
        .as("a member of a different Staffel is filtered out")
        .doesNotContain(secondMember.getId());
  }

  /**
   * Persists a fresh {@link Squadron} with a unique name and shorthand, as a second Staffel to
   * union against.
   *
   * @return the generated id of the persisted second Staffel.
   */
  private UUID createSecondSquadron() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    Squadron squadron = new Squadron();
    squadron.setName("ScopeTest Beta " + suffix);
    squadron.setShorthand("STB" + suffix);
    return squadronRepository.saveAndFlush(squadron).getId();
  }

  /**
   * Persists a user with a {@code SQUADRON}-kind membership in the given Staffel. The membership
   * {@code kind} column is populated by the V95 trigger on insert from the referenced org unit.
   *
   * @param squadronId the Staffel the user is a member of; never {@code null}.
   * @return the persisted user.
   */
  private User createMember(UUID squadronId) {
    User user = createUnassigned();
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), squadronId));
    membership.setUser(user);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);
    return user;
  }

  /**
   * Persists a user with no org-unit membership — the "unassigned" bucket (admins / guests) that
   * the scope queries always include via their {@code NOT EXISTS} branch.
   *
   * @return the persisted user.
   */
  private User createUnassigned() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(PREFIX + UUID.randomUUID());
    return userRepository.save(user);
  }

  /** Flushes the seeded rows (firing the membership-kind trigger) then clears the context. */
  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }
}
