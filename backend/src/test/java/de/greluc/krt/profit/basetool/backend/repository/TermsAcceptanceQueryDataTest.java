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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.model.TermsAcceptance;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.TermsAcceptanceStatusDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for the Terms-of-Use consent queries against real Postgres (REQ-SEC-028),
 * notably the paginated admin overview whose count query Spring Data derives.
 *
 * <p>Each test rolls back.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TermsAcceptanceQueryDataTest {

  /** The wording under test; literal so the assertions do not move when the real terms change. */
  private static final String VERSION_IN_FORCE = "test-version-in-force";

  /** A superseded wording, used to prove an old acceptance does not satisfy the current one. */
  private static final String OLDER_VERSION = "test-version-superseded";

  @Autowired private TermsAcceptanceRepository termsAcceptanceRepository;

  @PersistenceContext private EntityManager entityManager;

  private UUID acceptedUserId;
  private UUID pendingUserId;
  private UUID staleAcceptanceUserId;
  private UUID departedUserId;

  /**
   * Seeds four users covering every branch of the overview: one who accepted the version in force,
   * one who never accepted, one who accepted only a superseded wording, and one whose Keycloak
   * login is gone and who must therefore not appear at all.
   */
  @BeforeEach
  void seed() {
    acceptedUserId = persistUser("accepted-user", "Accepted", true);
    pendingUserId = persistUser("pending-user", "Pending", true);
    staleAcceptanceUserId = persistUser("stale-user", "Stale", true);
    departedUserId = persistUser("departed-user", "Departed", false);

    persistAcceptance(acceptedUserId, VERSION_IN_FORCE, Instant.now().minus(1, ChronoUnit.HOURS));
    persistAcceptance(
        staleAcceptanceUserId, OLDER_VERSION, Instant.now().minus(9, ChronoUnit.DAYS));
    persistAcceptance(departedUserId, VERSION_IN_FORCE, Instant.now().minus(2, ChronoUnit.DAYS));
    entityManager.flush();
  }

  /**
   * A page smaller than the result set still reports the true total, proving the derived count
   * query works with the constructor expression over the outer join.
   */
  @Test
  void adminOverviewPaginatesWithACorrectTotal() {
    Page<TermsAcceptanceStatusDto> firstPage =
        termsAcceptanceRepository.findAcceptanceStatus(
            VERSION_IN_FORCE, "ALL", PageRequest.of(0, 1, Sort.by("username")));

    assertThat(firstPage.getContent()).hasSize(1);
    assertThat(firstPage.getTotalElements()).isEqualTo(3);
    assertThat(firstPage.getTotalPages()).isEqualTo(3);
  }

  /** Every login-capable user appears under {@code ALL}, and the departed one does not. */
  @Test
  void allFilterReturnsEveryLoginCapableUserAndHidesTheDepartedOne() {
    Page<TermsAcceptanceStatusDto> page =
        termsAcceptanceRepository.findAcceptanceStatus(
            VERSION_IN_FORCE, "ALL", PageRequest.of(0, 50, Sort.by("username")));

    assertThat(page.getContent())
        .extracting(TermsAcceptanceStatusDto::userId)
        .containsExactlyInAnyOrder(acceptedUserId, pendingUserId, staleAcceptanceUserId)
        .doesNotContain(departedUserId);
  }

  /**
   * {@code acceptedAt} is the consent state: set for the user who accepted the version in force,
   * null for the one who never did <em>and</em> for the one whose acceptance names an older
   * wording.
   */
  @Test
  void acceptedAtIsSetOnlyForTheVersionInForce() {
    Page<TermsAcceptanceStatusDto> page =
        termsAcceptanceRepository.findAcceptanceStatus(
            VERSION_IN_FORCE, "ALL", PageRequest.of(0, 50, Sort.by("username")));

    assertThat(rowFor(page, acceptedUserId).acceptedAt()).isNotNull();
    assertThat(rowFor(page, acceptedUserId).accepted()).isTrue();
    assertThat(rowFor(page, pendingUserId).acceptedAt()).isNull();
    assertThat(rowFor(page, staleAcceptanceUserId).acceptedAt()).isNull();
    assertThat(rowFor(page, staleAcceptanceUserId).accepted()).isFalse();
  }

  /** {@code PENDING} lists exactly the users who still owe consent for the version in force. */
  @Test
  void pendingFilterReturnsOnlyUsersWithoutConsentForTheVersionInForce() {
    Page<TermsAcceptanceStatusDto> page =
        termsAcceptanceRepository.findAcceptanceStatus(
            VERSION_IN_FORCE, "PENDING", PageRequest.of(0, 50, Sort.by("username")));

    assertThat(page.getContent())
        .extracting(TermsAcceptanceStatusDto::userId)
        .containsExactlyInAnyOrder(pendingUserId, staleAcceptanceUserId);
    assertThat(page.getTotalElements()).isEqualTo(2);
  }

  /** {@code ACCEPTED} lists exactly the users who consented to the version in force. */
  @Test
  void acceptedFilterReturnsOnlyUsersWithConsentForTheVersionInForce() {
    Page<TermsAcceptanceStatusDto> page =
        termsAcceptanceRepository.findAcceptanceStatus(
            VERSION_IN_FORCE, "ACCEPTED", PageRequest.of(0, 50, Sort.by("username")));

    assertThat(page.getContent())
        .extracting(TermsAcceptanceStatusDto::userId)
        .containsExactly(acceptedUserId);
  }

  /** The headline figure counts the same users the {@code PENDING} filter lists. */
  @Test
  void pendingCountMatchesThePendingFilter() {
    assertThat(termsAcceptanceRepository.countPendingUsers(VERSION_IN_FORCE)).isEqualTo(2);
  }

  /** The hot per-request lookup is version-scoped, not merely user-scoped. */
  @Test
  void existsIsScopedToTheExactVersion() {
    assertThat(
            termsAcceptanceRepository.existsByUserIdAndTermsVersion(
                acceptedUserId, VERSION_IN_FORCE))
        .isTrue();
    assertThat(
            termsAcceptanceRepository.existsByUserIdAndTermsVersion(
                staleAcceptanceUserId, VERSION_IN_FORCE))
        .isFalse();
    assertThat(
            termsAcceptanceRepository.existsByUserIdAndTermsVersion(
                staleAcceptanceUserId, OLDER_VERSION))
        .isTrue();
  }

  /** Re-consent after a wording change adds to the history rather than replacing it. */
  @Test
  void historyKeepsEveryAcceptedVersionNewestFirst() {
    persistAcceptance(staleAcceptanceUserId, VERSION_IN_FORCE, Instant.now());
    entityManager.flush();

    assertThat(termsAcceptanceRepository.findByUserIdOrderByAcceptedAtDesc(staleAcceptanceUserId))
        .extracting(TermsAcceptance::getTermsVersion)
        .containsExactly(VERSION_IN_FORCE, OLDER_VERSION);
  }

  /**
   * Sorting on the joined {@code acceptedAt} column works only through its alias path, since a
   * plain {@link Sort} property resolves against the query root {@code User}.
   */
  @Test
  void sortingOnTheJoinedColumnRequiresTheAliasPath() {
    Page<TermsAcceptanceStatusDto> page =
        termsAcceptanceRepository.findAcceptanceStatus(
            VERSION_IN_FORCE, "ALL", PageRequest.of(0, 50, JpaSort.unsafe("ta.acceptedAt")));

    assertThat(page.getTotalElements()).isEqualTo(3);
    assertThat(page.getContent().getFirst().userId()).isEqualTo(acceptedUserId);
    assertThat(page.getContent().get(1).acceptedAt()).isNull();
  }

  /**
   * A bare property naming the joined column is rejected — the trap the service translates away.
   */
  @Test
  void sortingOnABarePropertyForTheJoinedColumnIsRejected() {
    PageRequest bare = PageRequest.of(0, 50, Sort.by("acceptedAt"));

    assertThatThrownBy(
            () -> {
              Page<TermsAcceptanceStatusDto> page =
                  termsAcceptanceRepository.findAcceptanceStatus(VERSION_IN_FORCE, "ALL", bare);
              page.getContent();
            })
        .hasMessageContaining("acceptedAt");
  }

  /**
   * Looks up one row of the page by user.
   *
   * @param page the page to search
   * @param userId the user whose row is wanted
   * @return that user's row
   */
  private static TermsAcceptanceStatusDto rowFor(Page<TermsAcceptanceStatusDto> page, UUID userId) {
    return page.getContent().stream()
        .filter(row -> row.userId().equals(userId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No row for user " + userId));
  }

  /**
   * Persists a user with the attributes the overview reads.
   *
   * @param username the login name, also the deterministic sort key of these tests
   * @param displayName the callsign
   * @param inKeycloak whether the account can still sign in
   * @return the new user's id
   */
  private UUID persistUser(String username, String displayName, boolean inKeycloak) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    user.setDisplayName(displayName);
    user.setInKeycloak(inKeycloak);
    entityManager.persist(user);
    return user.getId();
  }

  /**
   * Persists one acceptance row.
   *
   * @param userId the accepting user
   * @param version the wording accepted
   * @param acceptedAt when consent was given
   */
  private void persistAcceptance(UUID userId, String version, Instant acceptedAt) {
    entityManager.persist(
        TermsAcceptance.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .termsVersion(version)
            .acceptedAt(acceptedAt)
            .build());
  }
}
