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
 * Data-level pins for the Terms-of-Use consent queries (REQ-SEC-028, V229) against the real
 * Postgres test schema.
 *
 * <p>The admin overview query is the reason this class exists. It combines three things that each
 * work alone and can fail together: a constructor expression, a {@code LEFT JOIN … ON} whose right
 * side may be absent, and {@link org.springframework.data.domain.Pageable}. Spring Data derives the
 * count query for a paginated {@code @Query} by rewriting the select clause, and a constructor
 * expression over an outer join is exactly the shape where that rewrite can fail — at runtime, on
 * the first request, never at compile time. {@link #adminOverviewPaginatesWithACorrectTotal} calls
 * it with a page smaller than the result set specifically so the derived count is exercised rather
 * than incidentally equal to the page size.
 *
 * <p>{@link Transactional} so every method rolls back — the seeded rows never commit to the shared
 * Testcontainers database.
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
    // The departed user did accept — the row must still be hidden, because the exclusion is about
    // being able to act on the person, not about whether they ever consented.
    persistAcceptance(departedUserId, VERSION_IN_FORCE, Instant.now().minus(2, ChronoUnit.DAYS));
    entityManager.flush();
  }

  /**
   * The regression this class was written for: a page smaller than the result set must still report
   * the true total, which is only possible if Spring Data's derived count query survives the
   * constructor expression over the outer join.
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
   * Pins the repository's actual sort contract: the joined column must be named by its <em>alias
   * path</em>, because Spring Data resolves a plain {@link Sort} property against the query root
   * ({@code User}), where {@code acceptedAt} does not exist. Translating the caller's property into
   * this path is {@code TermsAcceptanceService}'s job, and {@code TermsAcceptanceServiceSortTest}
   * pins that half — this test exists so the repository side of the contract is written down rather
   * than rediscovered by whoever next passes a bare property and gets a 500.
   */
  @Test
  void sortingOnTheJoinedColumnRequiresTheAliasPath() {
    Page<TermsAcceptanceStatusDto> page =
        termsAcceptanceRepository.findAcceptanceStatus(
            VERSION_IN_FORCE, "ALL", PageRequest.of(0, 50, JpaSort.unsafe("ta.acceptedAt")));

    assertThat(page.getTotalElements()).isEqualTo(3);
    // Postgres treats NULL as larger than any non-null value, so ASC means NULLS LAST: the one user
    // who accepted the version in force leads, and everyone still owing consent trails. Worth
    // pinning because it is the opposite of what the admin overview wants by default — the useful
    // default sort is the pending users first — so a later NULLS FIRST or DESC switch has to be a
    // visible decision rather than a silent reordering of the worklist.
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
