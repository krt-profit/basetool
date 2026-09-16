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
import static org.assertj.core.api.Assertions.within;

import de.greluc.krt.profit.basetool.backend.model.User;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration coverage for the orphaned-account guard's two queries against the real Postgres test
 * container (REQ-SEC-059), so the V241 column validates against the entity and the bulk update that
 * stamps it behaves as written.
 *
 * <p>The property that needs a real database is that {@code markMissingUsers} is a <b>bulk JPQL
 * update</b>: it bypasses the entity lifecycle, which is exactly why {@code updatedAt} could not
 * have carried this fact and why the stamp had to become an explicit assignment. A Mockito test
 * cannot show that the assignment reaches the column.
 *
 * <p><b>The roster handed to {@code markMissingUsers} is always every committed id minus the one
 * under test.</b> That is not ceremony: the update flags <em>everything not in the roster</em>, so
 * a narrow list would soft-delete every user another test class has committed into this shared
 * container.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrphanedAccountRepositoryIntegrationTest {

  @Autowired private UserRepository userRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  /** Ids of the {@code app_user} rows this class created, removed again after each test. */
  private final Set<UUID> seeded = new HashSet<>();

  /**
   * Removes the users this class created.
   *
   * <p>They commit into a database shared by the whole suite, and a leftover login-capable user
   * shifts the totals other classes assert over -- the terms-acceptance overview counts every one
   * of them.
   */
  @AfterEach
  void removeSeededUsers() {
    transactionTemplate.executeWithoutResult(
        status -> {
          userRepository.deleteAllById(seeded);
          seeded.clear();
        });
  }

  /**
   * Creates a committed {@code app_user} row.
   *
   * @param inKeycloak the presence flag to persist
   * @param absentSince the absence stamp to persist, or {@code null}
   * @return the created user's id
   */
  private UUID user(boolean inKeycloak, Instant absentSince) {
    UUID id = UUID.randomUUID();
    transactionTemplate.executeWithoutResult(
        status -> {
          User u = new User();
          u.setId(id);
          u.setUsername("orphan-probe-" + id);
          u.setInKeycloak(inKeycloak);
          u.setKeycloakAbsentSince(absentSince);
          userRepository.save(u);
        });
    seeded.add(id);
    return id;
  }

  /**
   * Every committed user id except the given one, so the sweep flags exactly that account.
   *
   * @param excluded the id to leave out of the simulated Keycloak roster, or {@code null} to
   *     include everybody
   * @return the roster to hand to {@code markMissingUsers}
   */
  private List<UUID> rosterWithout(UUID excluded) {
    return userRepository.findAll().stream()
        .map(User::getId)
        .filter(id -> !id.equals(excluded))
        .toList();
  }

  /**
   * Reads a user back in its own transaction, so the assertion cannot see a stale entity left in a
   * persistence context by the bulk update.
   *
   * @param id the user to reload
   * @return the freshly loaded user
   */
  private User reload(UUID id) {
    return transactionTemplate.execute(status -> userRepository.findById(id).orElseThrow());
  }

  // covers REQ-SEC-059 — the bulk update writes the stamp, which is the whole point of the column
  @Test
  void markMissingUsersStampsWhenTheAbsenceWasObserved() {
    UUID gone = user(true, null);
    UUID present = user(true, null);
    Instant observed = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    // Everybody is in the roster: nothing flips, and no stamp is written.
    transactionTemplate.executeWithoutResult(
        status ->
            assertThat(userRepository.markMissingUsers(rosterWithout(null), observed)).isZero());
    assertThat(reload(gone).getKeycloakAbsentSince()).isNull();

    // `gone` drops out of the roster: it flips, and only it.
    transactionTemplate.executeWithoutResult(
        status ->
            assertThat(userRepository.markMissingUsers(rosterWithout(gone), observed))
                .isEqualTo(1));

    User flagged = reload(gone);
    assertThat(flagged.isInKeycloak()).isFalse();
    assertThat(flagged.getKeycloakAbsentSince()).isCloseTo(observed, within(1, ChronoUnit.SECONDS));

    User untouched = reload(present);
    assertThat(untouched.isInKeycloak()).isTrue();
    assertThat(untouched.getKeycloakAbsentSince()).isNull();
  }

  // covers REQ-SEC-059 — a second sweep must not push the stamp forward, or the age would only ever
  // report the sync's own cadence rather than how long the account has really been waiting
  @Test
  void aSecondSweepDoesNotRefreshAnExistingStamp() {
    Instant first = Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
    UUID orphan = user(false, first);

    transactionTemplate.executeWithoutResult(
        status -> userRepository.markMissingUsers(rosterWithout(orphan), Instant.now()));

    assertThat(reload(orphan).getKeycloakAbsentSince())
        .isCloseTo(first, within(1, ChronoUnit.SECONDS));
  }

  // covers REQ-SEC-059 — the two gauge queries read what the collector expects
  @Test
  void theGaugeQueriesSeeOnlyOrphansAndReportTheOldest() {
    Instant older = Instant.now().minus(40, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
    Instant newer = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);

    long before = userRepository.countOrphanedMemberAccounts();
    user(false, older);
    user(false, newer);
    user(true, null);

    assertThat(userRepository.countOrphanedMemberAccounts()).isEqualTo(before + 2);
    assertThat(userRepository.findOldestOrphanedMemberAbsenceStamp())
        .isNotNull()
        .isBeforeOrEqualTo(older);
  }

  // covers REQ-SEC-059 - a service-account row is not somebody's unfinished deletion
  @Test
  void aServiceAccountRowIsNotCountedAsAnOrphan() {
    // Production holds exactly one such row and can never clear it: an unfiltered GET /users omits
    // service accounts, so the roster sync never reports one and nothing calls setInKeycloak(true).
    // The first exclusion was conditional on app.security.ingest-gateway.client-ids, which defaults
    // empty -- and empty is exactly the configuration in which the row gets created, because the
    // machine-identity carve-out is gated on the same property. Unconditional now.
    long before = userRepository.countOrphanedMemberAccounts();
    serviceAccount("service-account-basetool-ingest");
    serviceAccount("SERVICE-ACCOUNT-Basetool-Other");

    assertThat(userRepository.countOrphanedMemberAccounts())
        .as("neither casing is counted; Keycloak treats usernames case-insensitively")
        .isEqualTo(before);
  }

  // covers REQ-SEC-059 — clearing the stamp is what stops a returning account alerting forever
  @Test
  void clearingTheFlagAlsoClearsTheStamp() {
    UUID returning = user(false, Instant.now().minus(10, ChronoUnit.DAYS));

    transactionTemplate.executeWithoutResult(
        status -> {
          User u = userRepository.findById(returning).orElseThrow();
          u.setInKeycloak(true);
          u.setKeycloakAbsentSince(null);
          userRepository.save(u);
        });

    User back = reload(returning);
    assertThat(back.isInKeycloak()).isTrue();
    assertThat(back.getKeycloakAbsentSince()).isNull();
  }

  /**
   * Commits a flagged, stamped row under Keycloak's service-account naming convention.
   *
   * @param username the generated username, in whatever casing the test is about
   */
  private void serviceAccount(String username) {
    transactionTemplate.executeWithoutResult(
        status -> {
          User machine = new User();
          machine.setId(UUID.randomUUID());
          machine.setUsername(username);
          machine.setInKeycloak(false);
          machine.setKeycloakAbsentSince(Instant.now().minusSeconds(86_400L * 30));
          seeded.add(machine.getId());
          userRepository.save(machine);
        });
  }
}
