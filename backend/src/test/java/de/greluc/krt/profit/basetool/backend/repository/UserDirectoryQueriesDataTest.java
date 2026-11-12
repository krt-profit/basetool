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

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for the member-directory {@link UserRepository} reference list and name
 * searches against real Postgres (REQ-ORG-017): they cover every user, whichever unit the user
 * belongs to, and match LIKE wildcards of an escaped fragment literally. Each test rolls back.
 */
@SpringBootTest
@Transactional
class UserDirectoryQueriesDataTest {

  /** Common username prefix so the substring searches target only this suite's users. */
  private static final String PREFIX = "directorytest-";

  /** A page wide enough to hold every user the shared test database seeds. */
  private static final PageRequest WIDE_PAGE = PageRequest.of(0, 5000);

  @Autowired private UserRepository userRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private SquadronRepository squadronRepository;

  @PersistenceContext private EntityManager entityManager;

  @Test
  void directoryQueriesCoverMembersOfEveryStaffelAndUsersWithoutOne() {
    User iridiumMember = createMember(Squadron.IRIDIUM_ID);
    User otherStaffelMember = createMember(createSecondSquadron());
    User unassigned = createNamed(PREFIX);
    flushAndClear();

    List<UUID> references =
        userRepository.findAllReference().stream().map(UserReferenceDto::id).toList();
    List<UUID> searched =
        userRepository.searchByName(PREFIX, WIDE_PAGE).map(User::getId).getContent();

    assertThat(references)
        .contains(iridiumMember.getId(), otherStaffelMember.getId(), unassigned.getId());
    assertThat(searched)
        .containsExactlyInAnyOrder(
            iridiumMember.getId(), otherStaffelMember.getId(), unassigned.getId());
    assertThat(referenceIds(PREFIX))
        .containsExactlyInAnyOrder(
            iridiumMember.getId(), otherStaffelMember.getId(), unassigned.getId());
  }

  @Test
  void nameSearchesMatchPercentAndUnderscoreLiterally() {
    User percent = createNamed(PREFIX + "lit%one-");
    User plain = createNamed(PREFIX + "litXone-");
    User underscore = createNamed(PREFIX + "lit_two-");
    createNamed(PREFIX + "litYtwo-");
    flushAndClear();

    assertThat(searchIds(PREFIX + "lit\\%one")).containsExactly(percent.getId());
    assertThat(searchIds(PREFIX + "lit\\_two")).containsExactly(underscore.getId());
    assertThat(searchIds(PREFIX + "litxone")).containsExactly(plain.getId());
    assertThat(referenceIds(PREFIX + "lit\\_two")).containsExactly(underscore.getId());
  }

  private List<UUID> searchIds(String escapedFragment) {
    return userRepository.searchByName(escapedFragment, WIDE_PAGE).map(User::getId).getContent();
  }

  private List<UUID> referenceIds(String escapedFragment) {
    return userRepository.searchReferencesByName(escapedFragment, WIDE_PAGE).getContent().stream()
        .map(UserReferenceDto::id)
        .toList();
  }

  /**
   * Persists a fresh {@link Squadron} with a unique name and shorthand.
   *
   * @return the generated id of the persisted Staffel.
   */
  private UUID createSecondSquadron() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    Squadron squadron = new Squadron();
    squadron.setName("DirectoryTest Beta " + suffix);
    squadron.setShorthand("DTB" + suffix);
    return squadronRepository.saveAndFlush(squadron).getId();
  }

  /**
   * Persists a user with a membership in the given Staffel; the V95 trigger fills the membership's
   * {@code kind} from the referenced org unit on insert.
   *
   * @param squadronId the Staffel the user is a member of; never {@code null}.
   * @return the persisted user.
   */
  private User createMember(UUID squadronId) {
    User user = createNamed(PREFIX);
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), squadronId));
    membership.setUser(user);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);
    return user;
  }

  private User createNamed(String usernamePrefix) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(usernamePrefix + UUID.randomUUID());
    return userRepository.save(user);
  }

  /** Flushes the seeded rows (firing the membership-kind trigger) then clears the context. */
  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }
}
