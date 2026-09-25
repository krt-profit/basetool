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
 * Integration tests asserting that the first authenticated request of a new account carries the
 * authorities its token claims, against a real database, role catalogue and transaction.
 *
 * <p>Deliberately not {@code @Transactional}: {@code syncUser} must commit before {@code
 * assembleFor} reads the lazily loaded {@code Role.permissions}, exactly as in a real request.
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
   * Removes the rows these cases wrote, since the class runs without a rolling-back test
   * transaction and shares its container with tests that count users.
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
