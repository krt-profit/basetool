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

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRegistrationService;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.identity.internal.AssignableRole;
import de.greluc.krt.profit.basetool.backend.identity.internal.TermsAcceptanceService;
import de.greluc.krt.profit.basetool.backend.identity.internal.UserRoleAssignmentService;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.scope.api.CustomJwtGrantedAuthoritiesConverter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The authorities of an authenticated request come from {@code user_roles} alone (REQ-SEC-088),
 * against a real database, role catalogue and transaction.
 *
 * <p>Deliberately not {@code @Transactional}: {@code syncUser} must commit before {@code
 * assembleFor} reads the lazily loaded {@code Role.permissions}, and a role change must commit
 * before the authorities cache is evicted, exactly as in a real request.
 */
@SpringBootTest
class FirstLoginAuthoritiesIntegrationTest {

  @Autowired private CustomJwtGrantedAuthoritiesConverter converter;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private UserRegistrationService userRegistrationService;
  @Autowired private UserRoleAssignmentService userRoleAssignmentService;
  @Autowired private TermsAcceptanceService termsAcceptanceService;
  @Autowired private JdbcTemplate jdbcTemplate;

  /** Subjects this class created, so the shared container is left as it was found. */
  private final List<UUID> createdSubjects = new ArrayList<>();

  /**
   * Removes the rows these cases wrote, since the class runs without a rolling-back test
   * transaction and shares its container with tests that count users.
   */
  @AfterEach
  void removeWhatTheseCasesCreated() {
    SecurityContextHolder.clearContext();
    createdSubjects.forEach(id -> {
      jdbcTemplate.update("DELETE FROM notification WHERE recipient_user_id = ?", id);
      jdbcTemplate.update("DELETE FROM audit_event WHERE subject_id = ?", id);
      jdbcTemplate.update("DELETE FROM user_approval_event WHERE user_id = ?", id);
      jdbcTemplate.update("DELETE FROM terms_acceptance WHERE user_id = ?", id);
      jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", id);
      jdbcTemplate.update("DELETE FROM personal_blueprint WHERE owner_user_id = ?", id);
      jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", id);
    });
    createdSubjects.clear();
  }

  /**
   * Builds a Keycloak-shaped access token of one session that still carries a realm-role claim.
   *
   * @param subject the {@code sub} claim
   * @param username the {@code preferred_username} claim
   * @param realmRoles the {@code realm_access.roles} claim, which must grant nothing
   * @return a token the converter can be handed directly
   */
  private static Jwt tokenFor(UUID subject, String username, List<String> realmRoles) {
    return Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .subject(subject.toString())
        .claim("sid", "session-" + subject)
        .claim("preferred_username", username)
        .claim("email", username + "@example.test")
        .claim("realm_access", Map.of("roles", realmRoles))
        .build();
  }

  private UUID newSubject() {
    UUID subject = UUID.randomUUID();
    createdSubjects.add(subject);
    return subject;
  }

  /**
   * Stores an approved member holding exactly the given roles.
   *
   * @param username the callsign
   * @param roleCodes the role codes to store in {@code user_roles}
   * @return the stored member's id
   */
  private UUID storedMember(String username, String... roleCodes) {
    UUID id = newSubject();
    User user = new User();
    user.setId(id);
    user.setUsername(username);
    user.setEmail(username + "@example.test");
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setRoles(new HashSet<>());
    for (String code : roleCodes) {
      user.getRoles().add(roleRepository.findByCode(code).orElseThrow());
    }
    userRepository.saveAndFlush(user);
    return id;
  }

  private List<String> authorities(Jwt jwt) {
    return converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();
  }

  @Test
  @DisplayName("a brand-new account whose token claims Admin is a pending registration")
  void aTokenClaimingAdminGrantsNothingOnFirstLogin() {
    Jwt jwt = tokenFor(newSubject(), "first-login-claimant", List.of("Admin", "KRT Member"));

    assertThat(authorities(jwt))
        .as("the realm-role claim is ignored, so a new account waits in the approval queue")
        .containsExactly("ROLE_PENDING_APPROVAL");
  }

  @Test
  @DisplayName("a stored role authorises and the token's realm roles do not")
  void theStoredRolesAuthoriseAndTheClaimDoesNot() {
    UUID id = storedMember("stored-member", Roles.KRT_MEMBER);

    List<String> authorities =
        authorities(tokenFor(id, "stored-member", List.of("Admin", "Bank Management")));

    assertThat(authorities).contains(Roles.authority(Roles.KRT_MEMBER));
    assertThat(authorities)
        .doesNotContain(Roles.authority(Roles.ADMIN), Roles.authority(Roles.BANK_MANAGEMENT));
    assertThat(userRepository.findById(id).orElseThrow().getRoles())
        .as("a login writes no role")
        .hasSize(1);
  }

  @Test
  @DisplayName("a login keeps the stored roles of a token that carries none")
  void aTokenWithoutRolesKeepsTheStoredRoles() {
    UUID id = storedMember("roleless-token", Roles.KRT_MEMBER, Roles.BANK_EMPLOYEE);

    List<String> authorities = authorities(tokenFor(id, "roleless-token", List.of()));

    assertThat(authorities)
        .contains(Roles.authority(Roles.KRT_MEMBER), Roles.authority(Roles.BANK_EMPLOYEE));
  }

  @Test
  @DisplayName("a role assigned in the Basetool applies on the next request of the same session")
  void aRoleChangeEvictsTheCachedAuthorities() {
    UUID id = storedMember("evicted-member", Roles.KRT_MEMBER);
    Jwt jwt = tokenFor(id, "evicted-member", List.of());
    assertThat(authorities(jwt)).doesNotContain(Roles.authority(Roles.BANK_EMPLOYEE));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new TestingAuthenticationToken("admin", "n/a", Roles.authority(Roles.ADMIN)));

    userRoleAssignmentService.change(id, AssignableRole.BANK_EMPLOYEE, true, 0L);

    assertThat(authorities(jwt)).contains(Roles.authority(Roles.BANK_EMPLOYEE));
  }

  @Test
  @DisplayName("approving a registration grants KRT Member")
  void approvingARegistrationGrantsKrtMember() {
    UUID subject = newSubject();
    Jwt jwt = tokenFor(subject, "approved-registration", List.of());
    assertThat(authorities(jwt)).containsExactly("ROLE_PENDING_APPROVAL");
    UUID adminId = storedMember("approving-admin", Roles.ADMIN);
    long version = userRepository.findById(subject).orElseThrow().getVersion();

    userRegistrationService.approveUser(subject, version, adminId);

    assertThat(authorities(jwt)).contains(Roles.authority(Roles.KRT_MEMBER));
  }

  @Test
  @DisplayName("an approved member holding no role is refused with NO_ROLE")
  void anApprovedMemberWithoutRolesIsRefused() {
    UUID id = storedMember("first-login-nobody");

    assertThat(authorities(tokenFor(id, "first-login-nobody", List.of("KRT Member"))))
        .containsExactly(Roles.NO_ROLE_MARKER);
  }

  @Test
  @DisplayName("the first thing every seeded client does - accepting the terms - works")
  void aFreshlyLoggedInAdminCanAcceptTheTerms() {
    UUID id = storedMember("first-login-consenter", Roles.ADMIN, Roles.KRT_MEMBER);
    authorities(tokenFor(id, "first-login-consenter", List.of()));

    assertThat(termsAcceptanceService.acceptCurrentTerms(id))
        .as("the first acceptance writes a row")
        .isTrue();
    assertThat(termsAcceptanceService.hasAcceptedCurrentTerms(id))
        .as("and the gate sees it")
        .isTrue();
  }
}
