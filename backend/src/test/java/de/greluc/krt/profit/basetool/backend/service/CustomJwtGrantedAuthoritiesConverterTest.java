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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.service.UserReconciliationService.ReconciledUser;
import de.greluc.krt.profit.basetool.backend.support.AuthoritiesCacheProperties;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import de.greluc.krt.profit.basetool.backend.support.IngestGatewayProperties;
import de.greluc.krt.profit.basetool.backend.support.OrgUnitContextualAuthority;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Mockito unit tests for {@link CustomJwtGrantedAuthoritiesConverter}, focused on the epic #692 /
 * REQ-ORG-015 cascade: a Bereichsleitung / OL leadership membership must mint officer-equivalent
 * flat roles ({@code ROLE_LOGISTICIAN} / {@code ROLE_MISSION_MANAGER}) plus contextual authorities
 * for every org unit the leadership reaches downward, and a plain member must be unaffected.
 */
@ExtendWith(MockitoExtension.class)
class CustomJwtGrantedAuthoritiesConverterTest {

  @Mock private UserReconciliationService userReconciliationService;
  @Mock private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Mock private OrgUnitCascadeService orgUnitCascadeService;
  @Mock private Jwt jwt;

  /** The gateway allowlist a test may extend; the properties record reads it by reference. */
  private final List<String> gatewayClientIds = new ArrayList<>();

  /**
   * A real instance, not a mock: the default empty allowlist is the state most of these tests need
   * — no caller is a gateway, so the machine-identity carve-out never fires and each case exercises
   * the ordinary member path it was written for (ADR-0129).
   *
   * <p>A spy rather than a plain field so the two carve-out tests can set an allowlist on it and
   * drive the other branch.
   */
  @Spy
  private final IngestGatewayProperties ingestGatewayProperties =
      new IngestGatewayProperties(gatewayClientIds);

  /**
   * A real instance, not a mock: the converter reads {@link AuthoritiesCacheProperties#ttl()} in
   * its constructor to size the memoisation window, so a mock would hand it {@code null} and the
   * Caffeine builder would fail before any test ran. The default five minutes (ADR-0174) is the
   * production value, so every case below exercises the shipped configuration.
   */
  @Spy
  private final AuthoritiesCacheProperties authoritiesCacheProperties =
      BoundProperties.defaults(AuthoritiesCacheProperties.class);

  /**
   * A real registry, not a mock: the converter binds its cache meters in the constructor, and the
   * hit / miss counts are asserted below.
   */
  @Spy private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

  @InjectMocks private CustomJwtGrantedAuthoritiesConverter converter;

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID BEREICH_ID = UUID.randomUUID();
  private static final UUID DESCENDANT_STAFFEL_ID = UUID.randomUUID();
  private static final UUID DESCENDANT_SK_ID = UUID.randomUUID();

  /**
   * A configured gateway is a machine: exactly one marker authority, and no registration.
   *
   * <p>The carve-out shipped untested, which is how the defect it fixes reached production in the
   * first place. Both halves are asserted, because each fails differently:
   *
   * <ul>
   *   <li>The authority set is {@code ROLE_INGEST_GATEWAY} and <em>nothing else</em>. A named
   *       authority rather than an empty set, so a misconfiguration reads as "authenticated as a
   *       machine" instead of "not authenticated" — and nothing extra, so the gateway's own bearer
   *       can reach no member surface.
   *   <li>{@code userReconciliationService} is never touched. That is the actual production
   *       failure: the gateway's first call created an {@code app_user} row for itself, stamped it
   *       PENDING, granted the default blueprints, notified the admins, and then 403'd its own
   *       account. Asserting only the authorities would still pass while all of that happened.
   * </ul>
   */
  @Test
  void grantsAConfiguredGatewayTheMachineAuthorityAndNeverRegistersIt() {
    gatewayClientIds.add("test-ingest-gateway");
    when(jwt.getClaimAsString("azp")).thenReturn("test-ingest-gateway");

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    assertEquals(
        List.of(CustomJwtGrantedAuthoritiesConverter.GATEWAY_AUTHORITY),
        authorities.stream().map(GrantedAuthority::getAuthority).toList());
    verifyNoInteractions(userReconciliationService);
  }

  /**
   * A caller whose {@code azp} is not on the allowlist takes the ordinary member path.
   *
   * <p>The complement of the case above: without it, a carve-out that matched everything would
   * still pass, and every member would authenticate as a machine with no roles at all.
   */
  @Test
  void leavesANonGatewayCallerOnTheOrdinaryMemberPath() {
    gatewayClientIds.add("test-ingest-gateway");
    when(jwt.getClaimAsString("azp")).thenReturn("basetool-frontend");
    when(userReconciliationService.syncUser(jwt)).thenReturn(ReconciledUser.of(userWithNoRoles()));

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    assertFalse(
        authorities.stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(CustomJwtGrantedAuthoritiesConverter.GATEWAY_AUTHORITY::equals));
    verify(userReconciliationService).syncUser(jwt);
  }

  /**
   * REQ-SEC-036 - the request is authorised by the roles the TOKEN carried, not by the row's.
   *
   * <p>The two are the same set for every client whose claim is complete. They differ for a
   * partial-scope client, and this is the case that makes the split worth its cost: because that
   * path deliberately no longer overwrites the stored roles, the row an administrator's app request
   * loads still holds {@code Admin}. Reading the roles back off it here would hand the app exactly
   * the authority its Keycloak client scope was configured to withhold - a guard that made the
   * problem worse than the defect it replaced.
   */
  @Test
  void authorisesWithTheEffectiveRolesRatherThanTheStoredOnes() {
    User admin = userWithNoRoles();
    admin.setRoles(Set.of(namedRole("Admin"), namedRole("KRT Member")));

    when(userReconciliationService.syncUser(jwt))
        .thenReturn(new ReconciledUser(admin, Set.of(namedRole("KRT Member"))));

    Collection<String> authorities =
        converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();

    assertTrue(authorities.contains("ROLE_KRT_MEMBER"), "the token's role must authorise");
    assertFalse(
        authorities.contains("ROLE_ADMIN"),
        "the row still holds Admin because the app path no longer overwrites it - it must not"
            + " leak into the authorities");
  }

  /**
   * The database-only overload keeps its contract, which the ingest gateway's acting-member path
   * depends on (ADR-0129): with no token in hand it must still assemble from the stored set.
   */
  @Test
  void assembleForAUserAloneStillReadsTheStoredRoles() {
    User user = userWithNoRoles();
    user.setRoles(Set.of(namedRole("Admin")));

    Collection<String> authorities =
        converter.assembleFor(user).stream().map(GrantedAuthority::getAuthority).toList();

    assertTrue(authorities.contains("ROLE_ADMIN"));
  }

  /**
   * A role with only a name, which is all the authority mapping reads.
   *
   * @param name the role's display name
   * @return the role, with no permissions attached
   */
  private static Role namedRole(String name) {
    Role role = new Role();
    role.setName(name);
    role.setPermissions(Set.of());
    return role;
  }

  private User userWithNoRoles() {
    User user = new User();
    user.setId(USER_ID);
    return user;
  }

  private static OrgUnitMembership membership(UUID orgUnitId, OrgUnitKind kind) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(USER_ID, orgUnitId));
    m.setKind(kind);
    return m;
  }

  @Test
  void convert_sameToken_memoisesAuthorities_assemblesOnce() {
    when(jwt.getSubject()).thenReturn("sub-1");
    when(jwt.getIssuedAt()).thenReturn(Instant.ofEpochSecond(1_700_000_000L));
    when(userReconciliationService.syncUser(jwt)).thenReturn(ReconciledUser.of(userWithNoRoles()));
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of());

    Collection<GrantedAuthority> first = converter.convert(jwt);
    Collection<GrantedAuthority> second = converter.convert(jwt);

    assertEquals(first, second, "the memoised result must equal the freshly assembled one");
    verify(userReconciliationService, times(1)).syncUser(jwt);
    verify(orgUnitMembershipRepository, times(1)).findAllByIdUserId(USER_ID);
  }

  @Test
  void convert_freshlyIssuedToken_missesCache_reassembles() {
    when(jwt.getSubject()).thenReturn("sub-1");
    when(jwt.getIssuedAt())
        .thenReturn(Instant.ofEpochSecond(1_700_000_000L), Instant.ofEpochSecond(1_700_000_300L));
    when(userReconciliationService.syncUser(jwt)).thenReturn(ReconciledUser.of(userWithNoRoles()));
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of());

    converter.convert(jwt);
    converter.convert(jwt);

    verify(userReconciliationService, times(2)).syncUser(jwt);
  }

  @Test
  void convert_tokenWithoutIssuedAt_bypassesCache() {
    when(jwt.getSubject()).thenReturn("sub-1");
    when(userReconciliationService.syncUser(jwt)).thenReturn(ReconciledUser.of(userWithNoRoles()));
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of());

    converter.convert(jwt);
    converter.convert(jwt);

    verify(userReconciliationService, times(2)).syncUser(jwt);
  }

  /**
   * BE-PERF-08: a refreshed access token of the same Keycloak session is a HIT. Before, {@code
   * issuedAt} in the key made every refresh — every five minutes — rerun the whole storm, so a TTL
   * longer than the token lifespan bought nothing.
   */
  @Test
  void convert_refreshedTokenOfTheSameSession_isServedFromTheCache() {
    when(userReconciliationService.syncUser(any(Jwt.class)))
        .thenReturn(ReconciledUser.of(userWithNoRoles()));
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of());

    converter.convert(token("session-1", "basetool-frontend", 1_700_000_000L, "KRT Member"));
    converter.convert(token("session-1", "basetool-frontend", 1_700_000_300L, "KRT Member"));

    verify(userReconciliationService, times(1)).syncUser(any(Jwt.class));
  }

  /**
   * The guarantee the session key must not cost: a realm role granted or revoked in Keycloak takes
   * effect on the next refreshed token, exactly as it did while {@code issuedAt} was in the key.
   * The refreshed token carries the new role list, the claims fingerprint differs, and the
   * authorities are re-assembled — here the revocation is observable in the result, not only in the
   * call count.
   */
  @Test
  void convert_refreshedTokenWithChangedRoles_missesAndReflectsTheChangeImmediately() {
    Jwt before = token("session-1", "basetool-frontend", 1_700_000_000L, "Admin", "KRT Member");
    Jwt after = token("session-1", "basetool-frontend", 1_700_000_300L, "KRT Member");
    when(userReconciliationService.syncUser(before))
        .thenReturn(
            new ReconciledUser(
                userWithNoRoles(), Set.of(namedRole("Admin"), namedRole("KRT Member"))));
    when(userReconciliationService.syncUser(after))
        .thenReturn(new ReconciledUser(userWithNoRoles(), Set.of(namedRole("KRT Member"))));
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of());

    Collection<String> first =
        converter.convert(before).stream().map(GrantedAuthority::getAuthority).toList();
    Collection<String> second =
        converter.convert(after).stream().map(GrantedAuthority::getAuthority).toList();

    assertTrue(first.contains("ROLE_ADMIN"));
    assertFalse(second.contains("ROLE_ADMIN"), "a revoked realm role must bite on the refresh");
    verify(userReconciliationService).syncUser(before);
    verify(userReconciliationService).syncUser(after);
  }

  /** A new login is a new Keycloak session and therefore a miss, whatever the claims say. */
  @Test
  void convert_newSession_misses() {
    when(userReconciliationService.syncUser(any(Jwt.class)))
        .thenReturn(ReconciledUser.of(userWithNoRoles()));
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of());

    converter.convert(token("session-1", "basetool-frontend", 1_700_000_000L, "KRT Member"));
    converter.convert(token("session-2", "basetool-frontend", 1_700_000_000L, "KRT Member"));

    verify(userReconciliationService, times(2)).syncUser(any(Jwt.class));
  }

  /**
   * REQ-SEC-036: two clients of the same person never share an entry, even inside one Keycloak
   * session and with otherwise identical claims — the partial-scope client must not inherit the web
   * client's authorities, nor the other way round.
   */
  @Test
  void convert_sameSessionDifferentAzp_neverShareAnEntry() {
    when(userReconciliationService.syncUser(any(Jwt.class)))
        .thenReturn(ReconciledUser.of(userWithNoRoles()));
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of());

    converter.convert(token("session-1", "basetool-frontend", 1_700_000_000L, "KRT Member"));
    converter.convert(token("session-1", "basetool-android", 1_700_000_000L, "KRT Member"));

    verify(userReconciliationService, times(2)).syncUser(any(Jwt.class));
  }

  /**
   * The per-token claims stay out of the fingerprint (a new {@code jti} and {@code exp} on every
   * refresh would otherwise make every refresh a miss again), while every other claim is in it.
   */
  @Test
  void authoritiesCacheKey_ignoresPerTokenClaimsButNotTheRest() {
    Jwt first = token("session-1", "basetool-frontend", 1_700_000_000L, "KRT Member");
    Jwt refreshed =
        Jwt.withTokenValue("t2")
            .header("alg", "none")
            .claims(claims -> claims.putAll(first.getClaims()))
            .issuedAt(Instant.ofEpochSecond(1_700_000_300L))
            .expiresAt(Instant.ofEpochSecond(1_700_000_600L))
            .claim("jti", "another-token-id")
            .build();
    Jwt renamed =
        Jwt.withTokenValue("t3")
            .header("alg", "none")
            .claims(claims -> claims.putAll(first.getClaims()))
            .claim("preferred_username", "renamed")
            .build();

    assertEquals(
        CustomJwtGrantedAuthoritiesConverter.authoritiesCacheKey(first),
        CustomJwtGrantedAuthoritiesConverter.authoritiesCacheKey(refreshed));
    assertNotEquals(
        CustomJwtGrantedAuthoritiesConverter.authoritiesCacheKey(first),
        CustomJwtGrantedAuthoritiesConverter.authoritiesCacheKey(renamed));
  }

  /**
   * The memoisation is observable: a hit and a miss land on {@code cache_gets_total} under {@code
   * cache="jwt-authorities"}, the series the Spring-apps dashboard and {@code CacheHitRatioLow}
   * already read for every other cache.
   */
  @Test
  void convert_publishesHitsAndMissesUnderTheCacheMeters() {
    when(userReconciliationService.syncUser(any(Jwt.class)))
        .thenReturn(ReconciledUser.of(userWithNoRoles()));
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of());

    converter.convert(token("session-1", "basetool-frontend", 1_700_000_000L, "KRT Member"));
    converter.convert(token("session-1", "basetool-frontend", 1_700_000_300L, "KRT Member"));

    assertEquals(1.0, cacheGets("hit"));
    assertEquals(1.0, cacheGets("miss"));
  }

  /**
   * Reads one {@code cache.gets} counter of the authorities cache.
   *
   * @param result {@code hit} or {@code miss}
   * @return the counter value
   */
  private double cacheGets(String result) {
    return meterRegistry
        .get("cache.gets")
        .tag("cache", CustomJwtGrantedAuthoritiesConverter.AUTHORITIES_CACHE_NAME)
        .tag("result", result)
        .functionCounter()
        .count();
  }

  /**
   * A real, Keycloak-shaped access token for the session-key tests.
   *
   * @param sid the Keycloak session id
   * @param azp the authorized party
   * @param issuedAtEpochSecond the token's {@code iat}
   * @param realmRoles the realm roles the token carries
   * @return the token
   */
  private static Jwt token(String sid, String azp, long issuedAtEpochSecond, String... realmRoles) {
    return Jwt.withTokenValue("token-" + sid + '-' + azp + '-' + issuedAtEpochSecond)
        .header("alg", "none")
        .subject("sub-1")
        .claim("sid", sid)
        .claim("azp", azp)
        .claim("jti", "jti-" + issuedAtEpochSecond)
        .claim("preferred_username", "member")
        .claim("realm_access", Map.of("roles", List.of(realmRoles)))
        .issuedAt(Instant.ofEpochSecond(issuedAtEpochSecond))
        .expiresAt(Instant.ofEpochSecond(issuedAtEpochSecond + 300))
        .build();
  }

  @Test
  void plainStaffelMember_getsNoLeadershipRolesAndNoCascade() {
    when(userReconciliationService.syncUser(jwt)).thenReturn(ReconciledUser.of(userWithNoRoles()));
    OrgUnitMembership plain = membership(DESCENDANT_STAFFEL_ID, OrgUnitKind.SQUADRON);
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of(plain));
    when(orgUnitCascadeService.cascadedOfficerReach(any())).thenReturn(Set.of());

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    assertFalse(authorities.contains(new SimpleGrantedAuthority("ROLE_LOGISTICIAN")));
    assertFalse(authorities.contains(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER")));
    assertFalse(
        authorities.contains(new OrgUnitContextualAuthority("LOGISTICIAN", DESCENDANT_STAFFEL_ID)));
  }

  @Test
  void bereichsleiter_getsFlatRolesAndCascadedContextualAuthorities() {
    when(userReconciliationService.syncUser(jwt)).thenReturn(ReconciledUser.of(userWithNoRoles()));
    OrgUnitMembership lead = membership(BEREICH_ID, OrgUnitKind.BEREICH);
    lead.setRole(MembershipRole.BEREICHSLEITER);
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of(lead));
    when(orgUnitCascadeService.cascadedOfficerReach(any()))
        .thenReturn(Set.of(BEREICH_ID, DESCENDANT_STAFFEL_ID, DESCENDANT_SK_ID));

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    assertTrue(authorities.contains(new SimpleGrantedAuthority("ROLE_LOGISTICIAN")));
    assertTrue(authorities.contains(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER")));
    for (UUID reached : List.of(BEREICH_ID, DESCENDANT_STAFFEL_ID, DESCENDANT_SK_ID)) {
      assertTrue(authorities.contains(new OrgUnitContextualAuthority("LOGISTICIAN", reached)));
      assertTrue(authorities.contains(new OrgUnitContextualAuthority("MISSION_MANAGER", reached)));
    }
  }

  @Test
  void olMember_getsFlatRolesAndContextualAuthoritiesForEveryReachedUnit() {
    when(userReconciliationService.syncUser(jwt)).thenReturn(ReconciledUser.of(userWithNoRoles()));
    UUID olId = UUID.randomUUID();
    OrgUnitMembership ol = membership(olId, OrgUnitKind.ORGANISATIONSLEITUNG);
    ol.setRole(MembershipRole.OL_MEMBER);
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of(ol));
    when(orgUnitCascadeService.cascadedOfficerReach(any()))
        .thenReturn(Set.of(olId, BEREICH_ID, DESCENDANT_STAFFEL_ID));

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    assertTrue(authorities.contains(new SimpleGrantedAuthority("ROLE_LOGISTICIAN")));
    assertTrue(authorities.contains(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER")));
    assertTrue(
        authorities.contains(new OrgUnitContextualAuthority("LOGISTICIAN", DESCENDANT_STAFFEL_ID)));
  }

  @Test
  void staffelleiter_getsFlatRolesAndOwnSquadronContextualOnly_noCascade() {
    when(userReconciliationService.syncUser(jwt)).thenReturn(ReconciledUser.of(userWithNoRoles()));
    UUID squadronId = UUID.randomUUID();
    OrgUnitMembership lead = membership(squadronId, OrgUnitKind.SQUADRON);
    lead.setRole(MembershipRole.STAFFELLEITER);
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of(lead));
    when(orgUnitCascadeService.cascadedOfficerReach(any())).thenReturn(Set.of());

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    assertTrue(authorities.contains(new SimpleGrantedAuthority("ROLE_LOGISTICIAN")));
    assertTrue(authorities.contains(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER")));
    assertTrue(authorities.contains(new OrgUnitContextualAuthority("LOGISTICIAN", squadronId)));
    assertTrue(authorities.contains(new OrgUnitContextualAuthority("MISSION_MANAGER", squadronId)));
    UUID foreignUnit = UUID.randomUUID();
    assertFalse(authorities.contains(new OrgUnitContextualAuthority("LOGISTICIAN", foreignUnit)));
    assertFalse(
        authorities.contains(new OrgUnitContextualAuthority("MISSION_MANAGER", foreignUnit)));
  }

  @Test
  void pendingRegistration_getsOnlyPendingApprovalAndNeverConsultsMembership() {
    User pending = userWithNoRoles();
    pending.setApprovalStatus(ApprovalStatus.PENDING);
    when(userReconciliationService.syncUser(jwt)).thenReturn(ReconciledUser.of(pending));

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    assertEquals(
        List.of(new SimpleGrantedAuthority("ROLE_PENDING_APPROVAL")), List.copyOf(authorities));
    assertFalse(authorities.contains(new SimpleGrantedAuthority("ROLE_GUEST")));
    assertFalse(authorities.contains(new SimpleGrantedAuthority(Roles.NO_ROLE_MARKER)));
    verifyNoInteractions(orgUnitMembershipRepository, orgUnitCascadeService);
  }

  @Test
  void rejectedRegistration_getsOnlyPendingApprovalAuthority() {
    User rejected = userWithNoRoles();
    rejected.setApprovalStatus(ApprovalStatus.REJECTED);
    when(userReconciliationService.syncUser(jwt)).thenReturn(ReconciledUser.of(rejected));

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    assertEquals(
        List.of(new SimpleGrantedAuthority("ROLE_PENDING_APPROVAL")), List.copyOf(authorities));
    verifyNoInteractions(orgUnitMembershipRepository, orgUnitCascadeService);
  }

  @Test
  void memberlessUser_getsNoMembershipDerivedAuthorities() {
    when(userReconciliationService.syncUser(jwt)).thenReturn(ReconciledUser.of(userWithNoRoles()));
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of());
    lenient().when(orgUnitCascadeService.cascadedOfficerReach(any())).thenReturn(Set.of());

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    assertFalse(authorities.contains(new SimpleGrantedAuthority("ROLE_LOGISTICIAN")));
    assertFalse(authorities.contains(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER")));
  }

  @Test
  void convert_retriesOnOptimisticLockThenSucceeds() {
    when(userReconciliationService.syncUser(jwt))
        .thenThrow(new ObjectOptimisticLockingFailureException(User.class, USER_ID))
        .thenReturn(ReconciledUser.of(userWithNoRoles()));
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of());

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    assertNotNull(authorities, "the retried sync must resolve to a real authority collection");
    verify(userReconciliationService, times(2)).syncUser(jwt);
  }

  @Test
  void convert_exhaustsRetries_throwsAuthenticationServiceException() {
    when(userReconciliationService.syncUser(jwt))
        .thenThrow(new ObjectOptimisticLockingFailureException(User.class, USER_ID));

    assertThrows(AuthenticationServiceException.class, () -> converter.convert(jwt));
    verify(userReconciliationService, times(3)).syncUser(jwt);
  }
}
