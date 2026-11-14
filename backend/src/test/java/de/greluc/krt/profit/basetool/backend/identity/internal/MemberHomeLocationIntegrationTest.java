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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.HomeLocationPreference;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the profile setting "my home location" against PostgreSQL (REQ-PATCH-030…032): the member's
 * own choice from the curated home locations with its own optimistic lock, the relocation that
 * overwrites it, and the audit event of every change.
 */
@SpringBootTest
class MemberHomeLocationIntegrationTest {

  @Autowired private MemberHomeLocationService preferences;
  @Autowired private HomeLocationPreference relocationFollow;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transaction;

  private final List<UUID> locations = new ArrayList<>();
  private UUID member;
  private UUID home;
  private UUID otherHome;
  private UUID port;
  private UUID hiddenHome;

  @BeforeEach
  void setUp() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("home-pref-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    member = userRepository.saveAndFlush(user).getId();
    home = location("Pref home ", true, false);
    otherHome = location("Pref other home ", true, false);
    port = location("Pref port ", false, false);
    hiddenHome = location("Pref hidden home ", true, true);
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM audit_event WHERE subject_id = ?", member);
    jdbc.update("DELETE FROM app_user WHERE id = ?", member);
    for (UUID location : locations) {
      UUID city =
          jdbc.queryForObject("SELECT city_id FROM location WHERE id = ?", UUID.class, location);
      jdbc.update("DELETE FROM location WHERE id = ?", location);
      jdbc.update("DELETE FROM city WHERE id = ?", city);
    }
  }

  @Test
  void aMemberWithoutAChoiceReadsNoneAtVersionZero() {
    MyHomeLocationResponse none = preferences.homeLocationOf(member);

    assertThat(none.homeLocationId()).isNull();
    assertThat(none.version()).isZero();
  }

  @Test
  void aMemberChoosesACuratedHomeLocationAndEveryChangeIsAudited() {
    MyHomeLocationResponse first = preferences.choose(member, home, 0L);
    MyHomeLocationResponse second = preferences.choose(member, otherHome, first.version());

    assertThat(first.homeLocationId()).isEqualTo(home);
    assertThat(second.homeLocationId()).isEqualTo(otherHome);
    assertThat(second.version()).isGreaterThan(first.version());
    assertThat(preferences.homeLocationOf(member)).isEqualTo(second);
    assertThat(auditDetails()).hasSize(2).allMatch(details -> details.contains("source=PROFILE"));
  }

  @Test
  void choosingTheStoredLocationAgainChangesNothing() {
    MyHomeLocationResponse first = preferences.choose(member, home, 0L);

    MyHomeLocationResponse again = preferences.choose(member, home, first.version());

    assertThat(again).isEqualTo(first);
    assertThat(auditDetails()).hasSize(1);
  }

  @Test
  void onlyACuratedHomeLocationThePickersOfferCanBeChosen() {
    assertThatThrownBy(() -> preferences.choose(member, port, 0L))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("error.profile.home_location_not_offered");
    assertThatThrownBy(() -> preferences.choose(member, hiddenHome, 0L))
        .isInstanceOf(BadRequestException.class);
    assertThat(preferences.homeLocationOf(member).homeLocationId()).isNull();
  }

  @Test
  void aStaleVersionIsRefused() {
    preferences.choose(member, home, 0L);

    assertThatThrownBy(() -> preferences.choose(member, otherHome, 0L))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertThat(preferences.homeLocationOf(member).homeLocationId()).isEqualTo(home);
  }

  @Test
  void aRelocationOverwritesTheChoiceWithoutAVersionAndIsAudited() {
    MyHomeLocationResponse chosen = preferences.choose(member, home, 0L);

    transaction.executeWithoutResult(_ -> relocationFollow.followRelocation(member, otherHome));

    MyHomeLocationResponse followed = preferences.homeLocationOf(member);
    assertThat(followed.homeLocationId()).isEqualTo(otherHome);
    assertThat(followed.version()).isGreaterThan(chosen.version());
    assertThat(auditDetails()).anyMatch(details -> details.contains("source=RELOCATION"));
    assertThatThrownBy(() -> preferences.choose(member, home, chosen.version()))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  void aRelocationSetsTheFirstChoiceAndAnUnchangedOneRecordsNothing() {
    transaction.executeWithoutResult(_ -> relocationFollow.followRelocation(member, home));
    transaction.executeWithoutResult(_ -> relocationFollow.followRelocation(member, home));

    assertThat(preferences.homeLocationOf(member).homeLocationId()).isEqualTo(home);
    assertThat(auditDetails()).hasSize(1);
  }

  @Test
  void theRelocationFollowNeedsTheRelocationsTransaction() {
    assertThatThrownBy(() -> relocationFollow.followRelocation(member, home))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  /**
   * Reads the details of the member's preference events, oldest first.
   *
   * @return the details
   */
  private @NotNull List<String> auditDetails() {
    return jdbc.queryForList(
        "SELECT details FROM audit_event WHERE event_type = 'HOME_LOCATION_PREFERENCE_SET'"
            + " AND subject_id = ? ORDER BY occurred_at",
        String.class,
        member);
  }

  /**
   * Inserts a location on a city of its own.
   *
   * @param prefix the name's prefix
   * @param curatedHome whether it is a curated home location
   * @param hidden whether the pickers hide it
   * @return its id
   */
  private @NotNull UUID location(@NotNull String prefix, boolean curatedHome, boolean hidden) {
    UUID id = UUID.randomUUID();
    TestPlaces.insertLocation(jdbc, id, prefix + id);
    jdbc.update(
        "UPDATE location SET is_home_location = ?, hidden = ? WHERE id = ?",
        curatedHome,
        hidden,
        id);
    locations.add(id);
    return id;
  }
}
