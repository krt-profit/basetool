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

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The <b>first</b> authenticated request of a brand-new account carries the authorities its token
 * claims — against a real database, a real role catalogue and a real transaction.
 *
 * <p><b>Why this exists.</b> Nothing covered this path, and it is the one every session starts on:
 * {@code CustomJwtGrantedAuthoritiesConverter} calls {@code syncUser(jwt)}, which creates the row
 * and maps the token's realm-role names onto the local catalogue, and then hands the result
 * straight to {@code assembleFor}. Every unit test around it mocks {@code RoleRepository}, so every
 * {@code Role} is a plain object with no persistence context — which makes the two failure modes
 * that actually happened invisible:
 *
 * <ul>
 *   <li><b>2026-09-06, the 500.</b> An N+1 fix cached the role catalogue in a field across
 *       transactions, handing out detached entities.
 *   <li><b>The 403 that followed.</b> Same defect one layer down and far better hidden: the roles
 *       were managed again, but {@code Role.permissions} is a {@code LAZY @ElementCollection} and
 *       {@code assembleFor} reads it <em>after</em> {@code syncUser}'s transaction has committed —
 *       a {@code LazyInitializationException} on the authentication path, so every login answered
 *       {@code 500}. It surfaced as a {@code 403} because the E2E seeder records its consent right
 *       after the password grant and <em>swallows</em> that call's failure by design; three calls
 *       later the terms gate refused with {@code TERMS_NOT_ACCEPTED}, which is what the run
 *       reported. Two CI cycles to get from that symptom back to this line.
 * </ul>
 *
 * <p>Both are one assertion away from being caught in seconds instead: a fresh admin login either
 * comes back holding {@code ROLE_ADMIN} or it does not. The seeding step that fails first in E2E is
 * an {@code @PreAuthorize(hasRole('ADMIN'))} endpoint called with a freshly minted admin token, so
 * this test stands exactly where that request stands.
 *
 * <p><b>Deliberately NOT {@code @Transactional}.</b> A test transaction keeps one Hibernate session
 * open for the whole method, which is precisely the condition a real request does not have: {@code
 * syncUser} commits and returns, and {@code assembleFor} then touches {@code Role.permissions}
 * outside it. Annotating this class {@code @Transactional} made all four cases pass while every
 * login in the E2E stack answered {@code 500} - the test would have been a decoration.
 */
@SpringBootTest
class FirstLoginAuthoritiesIntegrationTest {

  @Autowired private CustomJwtGrantedAuthoritiesConverter converter;
  @Autowired private UserRepository userRepository;
  @Autowired private TermsAcceptanceService termsAcceptanceService;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

  /** Subjects this class created, so the shared container is left as it was found. */
  private final List<UUID> createdSubjects = new java.util.ArrayList<>();

  /**
   * Removes the rows these cases wrote.
   *
   * <p>Necessary because the class is deliberately not {@code @Transactional} (see above): without
   * a test transaction nothing rolls back, and the container is shared with data tests that count
   * users — {@code TermsAcceptanceQueryDataTest} paginates the whole login-capable population and
   * fails on four extra rows it never seeded. A faithful transaction boundary is worth this
   * bookkeeping; hiding the bug behind {@code @Transactional} to avoid it is not.
   */
  @AfterEach
  void removeWhatTheseCasesCreated() {
    createdSubjects.forEach(
        id -> {
          jdbcTemplate.update("DELETE FROM terms_acceptance WHERE user_id = ?", id);
          jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", id);
          jdbcTemplate.update("DELETE FROM personal_blueprint WHERE owner_user_id = ?", id);
          jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", id);
        });
    createdSubjects.clear();
  }

  /**
   * Builds a Keycloak-shaped access token.
   *
   * @param username the {@code preferred_username} claim
   * @param realmRoles the {@code realm_access.roles} claim, in Keycloak's own casing
   * @return a token the converter can be handed directly
   */
  private Jwt tokenFor(String username, List<String> realmRoles) {
    UUID subject = UUID.randomUUID();
    createdSubjects.add(subject);
    return Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .subject(subject.toString())
        .claim("preferred_username", username)
        .claim("email", username + "@example.test")
        .claim("realm_access", Map.of("roles", realmRoles))
        .build();
  }

  @Test
  @DisplayName("a brand-new admin holds ROLE_ADMIN on the very first request")
  void firstLoginOfAnAdminCarriesRoleAdmin() {
    Jwt jwt = tokenFor("first-login-admin", List.of("Admin", "Officer", "KRT Member"));

    List<String> authorities =
        converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();

    assertThat(authorities)
        .as(
            "the seeding step that opens every E2E run is an @PreAuthorize(hasRole('ADMIN'))"
                + " endpoint called with exactly this token, on exactly this request")
        .contains(Roles.authority(Roles.ADMIN));
    assertThat(authorities)
        .as("and it must not be the role-less refusal (REQ-SEC-053) or the approval gate")
        .doesNotContain(Roles.NO_ROLE_MARKER, "ROLE_PENDING_APPROVAL");
  }

  @Test
  @DisplayName("a brand-new ordinary member is PENDING, not admitted with its roles")
  void firstLoginOfAMemberIsPendingApproval() {
    // The counterpart that keeps the case above honest: it must not pass merely because "the first
    // login works". An admin is force-ACTIVEd at first login precisely so the first administrator
    // can never be locked out (bootstrap safety); everybody else lands in the approval queue
    // (REQ-SEC-017, epic #720) and holds the single ROLE_PENDING_APPROVAL until an admin decides.
    Jwt jwt = tokenFor("first-login-member", List.of("KRT Member"));

    List<String> authorities =
        converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();

    assertThat(authorities)
        .as("a new registration is a queue entry, not a member yet")
        .containsExactly("ROLE_PENDING_APPROVAL");
  }

  @Test
  @DisplayName("an approved member whose realm roles map to nothing is refused with NO_ROLE")
  void anApprovedMemberWithNoMappableRoleIsRefused() {
    // REQ-SEC-053 from the other side, and it needs an APPROVED account: the role-less marker sits
    // after the approval short-circuit in assembleFor, so a brand-new row would answer
    // ROLE_PENDING_APPROVAL and prove nothing about the role mapping.
    Jwt jwt = tokenFor("first-login-nobody", List.of("no-such-realm-role"));
    converter.convert(jwt);
    User user = userRepository.findById(UUID.fromString(jwt.getSubject())).orElseThrow();
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    userRepository.saveAndFlush(user);

    List<String> authorities =
        converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();

    assertThat(authorities).containsExactly(Roles.NO_ROLE_MARKER);
  }

  @Test
  @DisplayName("the first thing every seeded client does - accepting the terms - works")
  void aFreshlyLoggedInAdminCanAcceptTheTerms() {
    // The E2E seeder records consent immediately after its password grant, because the stack runs
    // the dev profile and the gate is armed there. That call failing is invisible by design (it is
    // logged and swallowed, so the failure is reported by the seeding step that actually needed
    // consent) - which on 2026-09-06 turned a 500 here into a TERMS_NOT_ACCEPTED three calls later
    // and cost two CI cycles to trace back. Asserted directly, so the next time it breaks it says
    // so here.
    Jwt jwt = tokenFor("first-login-consenter", List.of("Admin", "Officer", "KRT Member"));
    converter.convert(jwt);
    UUID userId = UUID.fromString(jwt.getSubject());

    assertThat(termsAcceptanceService.acceptCurrentTerms(userId))
        .as("the first acceptance writes a row")
        .isTrue();
    assertThat(termsAcceptanceService.hasAcceptedCurrentTerms(userId))
        .as("and the gate sees it")
        .isTrue();
  }
}
