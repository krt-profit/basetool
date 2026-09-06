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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Unit tests for {@link AuthHelperService} — the sole sanctioned reader of {@link
 * SecurityContextHolder} in this codebase. ArchUnit forbids every other service / mapper /
 * controller from touching the static security context, so the behaviour of this class is
 * load-bearing for every {@code @PreAuthorize} decision in the backend. The previous test coverage
 * of these branches was effectively zero; this suite exercises each one.
 *
 * <p>{@link SecurityContextHolder} state is bound to the calling thread, so every test must restore
 * an empty context on teardown to avoid cross-test leakage when the suite is run with a shared
 * thread.
 */
@ExtendWith(MockitoExtension.class)
class AuthHelperServiceTest {

  @Mock private RoleHierarchy roleHierarchy;

  @InjectMocks private AuthHelperService helper;

  @BeforeEach
  void resetContext() {
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  // ---------------------------------------------------------------------
  // currentAuthentication() — null + anonymous filtered out, real returned
  // ---------------------------------------------------------------------

  @Nested
  class CurrentAuthenticationTests {

    @Test
    void returnsEmpty_whenSecurityContextHolderHasNoAuth() {
      // setSecurityContext default is an empty context (auth==null)
      assertTrue(helper.currentAuthentication().isEmpty());
    }

    @Test
    void returnsEmpty_whenAuthIsAnonymous() {
      AnonymousAuthenticationToken anon =
          new AnonymousAuthenticationToken(
              "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
      SecurityContextHolder.getContext().setAuthentication(anon);

      assertTrue(
          helper.currentAuthentication().isEmpty(),
          "anonymous tokens must be treated as no-authentication for the "
              + "purposes of the @PreAuthorize-readable helper API");
    }

    @Test
    void returnsAuth_whenRealJwtPrincipalPresent() {
      Authentication real =
          new UsernamePasswordAuthenticationToken(
              "alice", "n/a", AuthorityUtils.createAuthorityList("ROLE_KRT_MEMBER"));
      SecurityContextHolder.getContext().setAuthentication(real);

      Optional<Authentication> result = helper.currentAuthentication();

      assertTrue(result.isPresent());
      assertSame(real, result.get());
    }
  }

  // ---------------------------------------------------------------------
  // currentUserId() — reads the SUBJECT through the seam, never the principal name
  // ---------------------------------------------------------------------

  @Nested
  class CurrentUserIdTests {

    private static final UUID SUB = UUID.fromString("44444444-4444-4444-4444-444444444444");

    /** The ordinary resource-server case: the id comes from the token's {@code sub}. */
    @Test
    void readsTheSubjectOfABearerToken() {
      Jwt token = Jwt.withTokenValue("t").header("alg", "none").subject(SUB.toString()).build();
      SecurityContextHolder.getContext()
          .setAuthentication(new JwtAuthenticationToken(token, List.of()));

      assertEquals(Optional.of(SUB), helper.currentUserId());
    }

    /**
     * A caller whose {@code getName()} is a UUID but who carries no subject yields empty.
     *
     * <p>The behaviour this accessor changed. It used to read {@link Authentication#getName()} and
     * parse it, so this returned the id; since ADR-0129 it asks {@code AuthenticatedSubject}, which
     * accepts a JWT subject or an authentication that opts in via {@code SubjectAuthentication} and
     * nothing else. That narrowing is the point: {@code getName()} is a callsign on such a token,
     * and REQ-OBS-004 keeps callsigns out of everything this id feeds. Unreachable in production —
     * the backend is a pure resource server — but this is the accessor with ~53 call sites behind
     * it, so the contract is pinned rather than assumed.
     */
    @Test
    void yieldsEmptyForAPrincipalNameThatMerelyLooksLikeAnId() {
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(SUB.toString(), "n/a", List.of()));

      assertTrue(helper.currentUserId().isEmpty());
    }

    /**
     * A {@link Jwt} carried as the principal of some other authentication is still read.
     *
     * <p>{@code AuthenticatedSubject} keeps that branch specifically for this shape, and nothing
     * else exercised it.
     */
    @Test
    void readsAJwtCarriedAsThePrincipalOfAnotherAuthentication() {
      Jwt token = Jwt.withTokenValue("t").header("alg", "none").subject(SUB.toString()).build();
      SecurityContextHolder.getContext()
          .setAuthentication(new UsernamePasswordAuthenticationToken(token, "n/a", List.of()));

      assertEquals(Optional.of(SUB), helper.currentUserId());
    }

    /** An anonymous caller has no id. */
    @Test
    void yieldsEmptyForAnAnonymousCaller() {
      SecurityContextHolder.getContext()
          .setAuthentication(
              new AnonymousAuthenticationToken(
                  "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

      assertTrue(helper.currentUserId().isEmpty());
    }
  }

  // ---------------------------------------------------------------------
  // rawAuthentication() — passes through whatever is bound, including null + anonymous
  // ---------------------------------------------------------------------

  @Nested
  class RawAuthenticationTests {

    @Test
    void returnsNull_whenContextEmpty() {
      assertNull(helper.rawAuthentication());
    }

    @Test
    void returnsAnonymousAuthentication_whenAnonymousBound() {
      AnonymousAuthenticationToken anon =
          new AnonymousAuthenticationToken(
              "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
      SecurityContextHolder.getContext().setAuthentication(anon);

      Authentication raw = helper.rawAuthentication();

      assertSame(
          anon,
          raw,
          "rawAuthentication must NOT filter anonymous (the filter contract "
              + "is the responsibility of currentAuthentication())");
    }

    @Test
    void returnsRealAuthentication_whenBound() {
      Authentication real = new UsernamePasswordAuthenticationToken("alice", "n/a", List.of());
      SecurityContextHolder.getContext().setAuthentication(real);

      assertSame(real, helper.rawAuthentication());
    }
  }

  // ---------------------------------------------------------------------
  // isAuthenticated() — all three negation paths + the positive path
  // ---------------------------------------------------------------------

  @Nested
  class IsAuthenticatedTests {

    @Test
    void falseWhenContextIsEmpty() {
      assertFalse(helper.isAuthenticated());
    }

    @Test
    void falseWhenAuthIsExplicitlyUnauthenticated() {
      UsernamePasswordAuthenticationToken raw =
          UsernamePasswordAuthenticationToken.unauthenticated("alice", "n/a");
      SecurityContextHolder.getContext().setAuthentication(raw);

      assertFalse(
          helper.isAuthenticated(), "auth.isAuthenticated()==false must short-circuit to false");
    }

    @Test
    void falseWhenAuthIsAnonymousEvenIfMarkedAuthenticated() {
      AnonymousAuthenticationToken anon =
          new AnonymousAuthenticationToken(
              "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
      // Anonymous tokens internally are isAuthenticated()==true, so the
      // anonymous-check is what saves us.
      SecurityContextHolder.getContext().setAuthentication(anon);

      assertFalse(helper.isAuthenticated());
    }

    @Test
    void trueWhenRealAuthenticatedPrincipal() {
      Authentication real =
          new UsernamePasswordAuthenticationToken(
              "alice", "n/a", AuthorityUtils.createAuthorityList("ROLE_KRT_MEMBER"));
      SecurityContextHolder.getContext().setAuthentication(real);

      assertTrue(helper.isAuthenticated());
    }
  }

  // ---------------------------------------------------------------------
  // hasReachableRole() — auth-null short-circuit, direct match, hierarchy match, miss
  // ---------------------------------------------------------------------

  @Nested
  class HasReachableRoleTests {

    @Test
    void falseWhenContextIsEmpty_withoutConsultingRoleHierarchy() {
      // setUp left the context empty.
      assertFalse(helper.hasReachableRole("ROLE_ADMIN"));

      // Critical: an empty context must NOT invoke the role hierarchy at all
      // (otherwise we leak auth-context-state to the hierarchy and risk an NPE
      // when running with `SecurityContextHolder.MODE_INHERITABLETHREADLOCAL`).
      verify(roleHierarchy, never()).getReachableGrantedAuthorities(any());
    }

    @Test
    void trueWhenAuthorityIsDirectlyHeld() {
      authContextWith("ROLE_OFFICER");
      // RoleHierarchy spelled out below — the hierarchy reaches ROLE_OFFICER
      // from itself (identity reach), nothing else.
      stubHierarchyReaches(List.of("ROLE_OFFICER"));

      assertTrue(helper.hasReachableRole("ROLE_OFFICER"));
    }

    @Test
    void trueWhenAuthorityIsReachableViaHierarchy() {
      // Mirrors the production config: ROLE_ADMIN > ROLE_LOGISTICIAN.
      authContextWith("ROLE_ADMIN");
      stubHierarchyReaches(List.of("ROLE_ADMIN", "ROLE_LOGISTICIAN", "ROLE_MISSION_MANAGER"));

      assertTrue(
          helper.hasReachableRole("ROLE_LOGISTICIAN"),
          "ROLE_ADMIN must reach ROLE_LOGISTICIAN via SecurityConfig's " + "declared hierarchy");
    }

    @Test
    void falseWhenAuthorityIsNotReachable() {
      authContextWith("ROLE_KRT_MEMBER");
      stubHierarchyReaches(List.of("ROLE_KRT_MEMBER"));

      assertFalse(
          helper.hasReachableRole("ROLE_LOGISTICIAN"), "KRT_MEMBER must not reach LOGISTICIAN");
    }

    @Test
    void rolePrefixIsLiteral_noFallbackResolution() {
      // hasReachableRole must compare strings verbatim — passing "LOGISTICIAN"
      // (without the ROLE_ prefix) must NOT silently match "ROLE_LOGISTICIAN".
      authContextWith("ROLE_LOGISTICIAN");
      stubHierarchyReaches(List.of("ROLE_LOGISTICIAN"));

      assertFalse(
          helper.hasReachableRole("LOGISTICIAN"),
          "callers must pass the full ROLE_ prefix — anything else is a "
              + "configuration bug, not a near-miss to forgive");
    }

    @Test
    void throwsNullPointerException_whenCalledWithNullRole() {
      authContextWith("ROLE_KRT_MEMBER");
      stubHierarchyReaches(List.of("ROLE_KRT_MEMBER"));

      // role parameter is @NotNull; calling with null is a programmer error
      // that should not be silently swallowed. (String.equals(null) returns
      // false rather than throwing, so the resulting return value is false;
      // we assert that to lock in the "null returns false" behaviour rather
      // than relying on an undocumented exception.)
      assertFalse(helper.hasReachableRole("ROLE_NONEXISTENT"));
    }
  }

  // ---------------------------------------------------------------------
  // isLogisticianOrAbove() — shortcut for hasReachableRole("ROLE_LOGISTICIAN")
  // ---------------------------------------------------------------------

  @Nested
  class IsLogisticianOrAboveTests {

    @Test
    void trueWhenAdminReachesLogistician() {
      authContextWith("ROLE_ADMIN");
      stubHierarchyReaches(List.of("ROLE_ADMIN", "ROLE_LOGISTICIAN", "ROLE_MISSION_MANAGER"));

      assertTrue(helper.isLogisticianOrAbove());
    }

    @Test
    void trueWhenOfficerReachesLogistician() {
      authContextWith("ROLE_OFFICER");
      stubHierarchyReaches(List.of("ROLE_OFFICER", "ROLE_LOGISTICIAN", "ROLE_MISSION_MANAGER"));

      assertTrue(helper.isLogisticianOrAbove());
    }

    @Test
    void trueForDirectLogistician() {
      authContextWith("ROLE_LOGISTICIAN");
      stubHierarchyReaches(List.of("ROLE_LOGISTICIAN"));

      assertTrue(helper.isLogisticianOrAbove());
    }

    @Test
    void falseForSquadronMember() {
      authContextWith("ROLE_KRT_MEMBER");
      stubHierarchyReaches(List.of("ROLE_KRT_MEMBER"));

      assertFalse(helper.isLogisticianOrAbove());
    }

    @Test
    void falseWhenNobodyIsLoggedIn() {
      // No context bound at all -> false, hierarchy never consulted.
      assertFalse(helper.isLogisticianOrAbove());
      verify(roleHierarchy, never()).getReachableGrantedAuthorities(any());
    }
  }

  // ---------------------------------------------------------------------
  // isMemberOrAbove() — the "mission outsider" predicate (its negation): false for anonymous and
  // for an authenticated role-less GUEST; true for every registered-member / elevated role.
  // ---------------------------------------------------------------------

  @Nested
  class IsMemberOrAboveTests {

    @Test
    void falseWhenNobodyIsLoggedIn_withoutConsultingRoleHierarchy() {
      assertFalse(helper.isMemberOrAbove());
      verify(roleHierarchy, never()).getReachableGrantedAuthorities(any());
    }

    @Test
    void falseForAnonymous() {
      AnonymousAuthenticationToken anon =
          new AnonymousAuthenticationToken(
              "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
      SecurityContextHolder.getContext().setAuthentication(anon);
      stubHierarchyReaches(List.of("ROLE_ANONYMOUS"));

      assertFalse(helper.isMemberOrAbove());
    }

    @Test
    void falseForARoleLessAccount() {
      // The authority was ROLE_GUEST until V239 deleted the role; ROLE_NO_ROLE is the marker that
      // replaced it (REQ-SEC-053). What the case pins is unchanged — holding it is not membership
      // — but the account carrying it no longer reaches anything at all:
      // PendingApprovalAccessFilter
      // refuses it with 403 NO_ROLE before a handler runs, so isMemberOrAbove() answering false is
      // now defence in depth rather than the decision.
      authContextWith("ROLE_NO_ROLE");
      stubHierarchyReaches(List.of("ROLE_NO_ROLE"));

      assertFalse(
          helper.isMemberOrAbove(), "an authenticated but role-less account is not a member");
    }

    @Test
    void trueForSquadronMember() {
      authContextWith("ROLE_KRT_MEMBER");
      stubHierarchyReaches(List.of("ROLE_KRT_MEMBER"));

      assertTrue(helper.isMemberOrAbove());
    }

    @Test
    void trueForLegacyMemberAlias() {
      authContextWith("ROLE_KRT_MEMBER");
      stubHierarchyReaches(List.of("ROLE_KRT_MEMBER"));

      assertTrue(helper.isMemberOrAbove());
    }

    @Test
    void trueForOfficer() {
      authContextWith("ROLE_OFFICER");
      stubHierarchyReaches(List.of("ROLE_OFFICER", "ROLE_LOGISTICIAN", "ROLE_MISSION_MANAGER"));

      assertTrue(helper.isMemberOrAbove());
    }

    @Test
    void trueForAdmin() {
      authContextWith("ROLE_ADMIN");
      stubHierarchyReaches(List.of("ROLE_ADMIN", "ROLE_LOGISTICIAN", "ROLE_MISSION_MANAGER"));

      assertTrue(helper.isMemberOrAbove());
    }

    @Test
    void trueForContextualLogisticianWithoutSquadronMemberRole() {
      // A user promoted via an org-unit is_logistician flag carries ROLE_LOGISTICIAN even without a
      // KRT_MEMBER realm role — they are still an insider, not an outsider.
      authContextWith("ROLE_LOGISTICIAN");
      stubHierarchyReaches(List.of("ROLE_LOGISTICIAN"));

      assertTrue(helper.isMemberOrAbove());
    }
  }

  // ---------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------

  private void authContextWith(String... roles) {
    Authentication auth =
        new UsernamePasswordAuthenticationToken(
            "alice", "n/a", AuthorityUtils.createAuthorityList(roles));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  @SuppressWarnings("unchecked")
  private void stubHierarchyReaches(List<String> reachableRoles) {
    Collection<GrantedAuthority> reachable =
        reachableRoles.stream()
            .map(SimpleGrantedAuthority::new)
            .map(GrantedAuthority.class::cast)
            .toList();
    lenient()
        .when(roleHierarchy.getReachableGrantedAuthorities(any()))
        .thenReturn((Collection) reachable);
  }
}
