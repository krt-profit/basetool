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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.support.OrgUnitContextualAuthority;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Translates an incoming Keycloak JWT into the authorities Spring Security will check against
 * {@code @PreAuthorize}.
 *
 * <p>Three sources are merged: (1) Keycloak realm roles assigned to the user, mapped to {@code
 * ROLE_<UPPER_SNAKE_CASE>} authorities; (2) every permission name attached to those roles in the
 * local {@code role}/{@code permission} tables, used directly (no {@code ROLE_} prefix) for
 * fine-grained {@code hasAuthority} checks; (3) the per-OrgUnit-membership flags {@code
 * is_logistician} and {@code is_mission_manager} on {@code org_unit_membership}, promoted to flat
 * {@code ROLE_LOGISTICIAN} / {@code ROLE_MISSION_MANAGER} so an admin can grant these roles via the
 * membership-management UI without round-tripping through Keycloak.
 *
 * <p>SPEZIALKOMMANDO_PLAN.md D3 + §6.1: the per-role flags are sourced from {@code
 * org_unit_membership} — the legacy {@code app_user.is_logistician} / {@code
 * app_user.is_mission_manager} columns were dropped in V101 (R9 Step 5). The user gets the flat
 * role iff <b>any</b> of their memberships (Staffel + every SK) carries the flag — the contextual
 * scoping ("logistician of which OrgUnit") still happens at the {@code @PreAuthorize} call site
 * through {@link de.greluc.krt.profit.basetool.backend.service.OwnerScopeService}.
 *
 * <p>The converter calls {@link UserReconciliationService#syncUser(Jwt)} on every authentication so
 * the local row is created or updated lazily — this is where new Keycloak users acquire their
 * {@code app_user} record. Optimistic-locking conflicts from concurrent first-time logins by the
 * same user are retried up to {@value #MAX_SYNC_ATTEMPTS} times with a short fixed backoff; after
 * that the authentication is rejected with {@link AuthenticationServiceException} to avoid a stuck
 * client retry loop.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CustomJwtGrantedAuthoritiesConverter
    implements Converter<Jwt, Collection<GrantedAuthority>> {

  private static final int MAX_SYNC_ATTEMPTS = 3;
  private static final long RETRY_BACKOFF_MILLIS = 50L;

  /**
   * Short memoisation window for the assembled authorities (#1141). Bounds the staleness of a
   * mid-token role / permission / approval / membership change to ~30&nbsp;s (after which the next
   * request re-runs {@code syncUser}), while collapsing the per-request {@code syncUser} +
   * role-lookup + membership query storm to once per token issuance. This login-path reconciliation
   * is independent of — and far fresher than — the periodic drift-correction {@code
   * app.keycloak.sync} (now daily), which only covers users who are not currently logging in.
   */
  private static final Duration AUTHORITIES_CACHE_TTL = Duration.ofSeconds(30);

  /** Upper bound on distinct cached {@code (sub, issuedAt)} entries. */
  private static final long AUTHORITIES_CACHE_MAX_SIZE = 10_000;

  private final UserReconciliationService userReconciliationService;
  private final OrgUnitMembershipRepository orgUnitMembershipRepository;
  private final OrgUnitCascadeService orgUnitCascadeService;

  /**
   * Per-{@code (sub, token issued-at)} memoisation of the fully-assembled authority collection
   * (#1141). The resource-server authorities converter runs on <em>every</em> authenticated API
   * call — every fragment refetch, every live-sync coalesce burst, every check-in — and each miss
   * pays {@link UserReconciliationService#syncUser(Jwt)} (a write-capable transaction) plus
   * ~5&ndash;8 SELECTs (user load, {@code user_roles}, one role lookup per realm role, and the
   * membership read). Keying on the token's {@code issuedAt} means a fresh login always misses and
   * re-reads, so a re-authentication picks up new authorities immediately; within one token's life
   * the {@link #AUTHORITIES_CACHE_TTL} bounds staleness. Only successful results are cached (an
   * exception propagates uncached), the cached value is an immutable copy so a downstream mutation
   * cannot corrupt it, and a token missing {@code sub} or {@code issuedAt} bypasses the cache
   * entirely (always recomputed).
   */
  private final Cache<String, Collection<GrantedAuthority>> authoritiesCache =
      Caffeine.newBuilder()
          .maximumSize(AUTHORITIES_CACHE_MAX_SIZE)
          .expireAfterWrite(AUTHORITIES_CACHE_TTL)
          .build();

  /**
   * Resolves the authorities for {@code jwt}, memoised per {@code (sub, issuedAt)} for {@link
   * #AUTHORITIES_CACHE_TTL} (#1141). On a cache hit the whole {@link #assembleAuthorities(Jwt)}
   * pipeline — {@code syncUser} and its query storm — is skipped; on a miss (or an unkeyable token)
   * it is assembled fresh and, when keyable, cached as an immutable copy.
   *
   * @param jwt the validated Keycloak access token; never {@code null}.
   * @return the authorities Spring Security checks against {@code @PreAuthorize}.
   */
  @Override
  public Collection<GrantedAuthority> convert(@NonNull Jwt jwt) {
    String cacheKey = authoritiesCacheKey(jwt);
    if (cacheKey != null) {
      Collection<GrantedAuthority> cached = authoritiesCache.getIfPresent(cacheKey);
      if (cached != null) {
        return cached;
      }
    }
    Collection<GrantedAuthority> authorities = List.copyOf(assembleAuthorities(jwt));
    if (cacheKey != null) {
      authoritiesCache.put(cacheKey, authorities);
    }
    return authorities;
  }

  /**
   * Builds the {@code (sub, issuedAt)} memoisation key, or {@code null} when either claim is absent
   * — in which case {@link #convert(Jwt)} bypasses the cache and always recomputes. Keying on the
   * token's issued-at epoch millis guarantees a freshly-issued token (re-login, refresh) is a
   * distinct key and therefore a miss, so authority changes take effect on re-authentication.
   *
   * @param jwt the access token.
   * @return the cache key, or {@code null} to bypass caching for this token.
   */
  private static String authoritiesCacheKey(@NonNull Jwt jwt) {
    String sub = jwt.getSubject();
    Instant issuedAt = jwt.getIssuedAt();
    if (sub == null || issuedAt == null) {
      return null;
    }
    return sub + '|' + issuedAt.toEpochMilli();
  }

  /**
   * Assembles the authorities from scratch: syncs the local user (retried on optimistic-lock
   * contention), short-circuits a non-approved registration to {@code ROLE_PENDING_APPROVAL}, and
   * otherwise merges realm-role, permission and membership-derived authorities. Extracted from
   * {@link #convert(Jwt)} so the cache wraps exactly this work (#1141).
   *
   * @param jwt the access token.
   * @return the freshly assembled authorities.
   */
  private Collection<GrantedAuthority> assembleAuthorities(@NonNull Jwt jwt) {
    ObjectOptimisticLockingFailureException lastLockingFailure = null;
    for (int attempt = 1; attempt <= MAX_SYNC_ATTEMPTS; attempt++) {
      try {
        User user = userReconciliationService.syncUser(jwt);

        // Epic #720, Track 1 / REQ-SEC-017: a PENDING (or REJECTED) registration is granted NO
        // authorities. The ENTIRE assembly below — realm roles, permissions, membership-derived
        // flat roles, contextual + cascaded authorities — is short-circuited to a single
        // ROLE_PENDING_APPROVAL. ROLE_GUEST is deliberately NOT carried: a pending user is routed
        // to
        // the "waiting for approval" surface, not given the guest read surface.
        if (!user.isApproved()) {
          return List.of(new SimpleGrantedAuthority("ROLE_PENDING_APPROVAL"));
        }

        Collection<GrantedAuthority> authorities =
            user.getRoles().stream()
                .flatMap(
                    role -> {
                      Stream<GrantedAuthority> roleAuth =
                          Stream.of(
                              new SimpleGrantedAuthority(
                                  "ROLE_" + role.getName().toUpperCase().replace(" ", "_")));
                      Stream<GrantedAuthority> permAuth =
                          role.getPermissions().stream().map(SimpleGrantedAuthority::new);
                      return Stream.concat(roleAuth, permAuth);
                    })
                .collect(Collectors.toCollection(ArrayList::new));

        // R6.d / Plan D3: source the per-role flags from the user's OrgUnit memberships. Any
        // membership that carries `is_logistician = true` promotes the caller to the flat
        // ROLE_LOGISTICIAN authority (same for ROLE_MISSION_MANAGER). The legacy User-level
        // columns are consulted as a fallback so a user whose memberships have not yet been
        // backfilled by V95 (impossible today but defensive for the migration window) does not
        // silently lose access.
        addMembershipDerivedRoles(user, authorities);

        return authorities;
      } catch (ObjectOptimisticLockingFailureException e) {
        lastLockingFailure = e;
        int attemptsLeft = MAX_SYNC_ATTEMPTS - attempt;
        log.warn(
            "Optimistic locking failure during user sync (attempt {}/{}). Attempts left: {}",
            attempt,
            MAX_SYNC_ATTEMPTS,
            attemptsLeft);
        if (attemptsLeft > 0) {
          try {
            Thread.sleep(RETRY_BACKOFF_MILLIS);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AuthenticationServiceException(
                "User authority sync interrupted while retrying after optimistic locking failure",
                ie);
          }
        }
      }
    }

    log.error(
        "Failed to sync user authorities after {} attempts due to repeated optimistic locking"
            + " failures. Authentication denied.",
        MAX_SYNC_ATTEMPTS,
        lastLockingFailure);
    throw new AuthenticationServiceException(
        "Failed to resolve user authorities after " + MAX_SYNC_ATTEMPTS + " attempts",
        lastLockingFailure);
  }

  /**
   * Plan D3 + §6.1 — emits two parallel authority surfaces:
   *
   * <ol>
   *   <li><b>Flat (back-compat)</b> — {@code ROLE_LOGISTICIAN} / {@code ROLE_MISSION_MANAGER}
   *       based on the OR-union of every OrgUnit membership. Lets every existing {@code
   *       @PreAuthorize("hasRole('LOGISTICIAN')")} SpEL string keep working unchanged.
   *   <li><b>Contextual (§6.1, long-term)</b> — one {@link OrgUnitContextualAuthority} per
   *       (membership, flag = true) pair, i.e. {@code ROLE_LOGISTICIAN@<orgUnitUuid>}. Enables
   *       per-OrgUnit scoping at the {@code @PreAuthorize} surface without a service-layer
   *       round-trip. Matches the plan §6.1 design: "Spring Security authentication carries a
   *       Set&lt;ContextualAuthority&gt;".
   *   <li><b>Cascaded contextual (epic #692, REQ-ORG-015)</b> — additional {@link
   *       OrgUnitContextualAuthority} entries for the org units a Bereichsleitung / OL leadership
   *       membership reaches <em>downward</em> (a Bereich's Staffeln + SKs; for OL, every org
   *       unit), resolved by {@link
   *       OrgUnitCascadeService#cascadedOfficerReach(java.util.Collection)}. This makes a
   *       Bereichsleitung / OL member act with officer-equivalent {@code LOGISTICIAN} / {@code
   *       MISSION_MANAGER} authority in their subordinate units — never admin. A caller with no
   *       leadership flag contributes nothing here, so the authority set is unchanged from the
   *       pre-#692 behaviour.
   * </ol>
   *
   * <p>Both lists emit on every authentication so existing flat-role gates and new contextual
   * gates coexist. The {@link
   * de.greluc.krt.profit.basetool.backend.service.OwnerScopeService#hasRoleInOrgUnit} helper reads
   * the contextual authorities by value, which lets a SpEL like {@code
   * @ownerScopeService.hasRoleInOrgUnit(#dto.owningOrgUnitId, 'LOGISTICIAN')} resolve without
   * the caller having to construct the authority string by hand.
   *
   * <p>Post-R9 D3: the legacy User-level {@code is_logistician} / {@code is_mission_manager}
   * columns have been dropped from {@code app_user} (V101). Memberless users carry no
   * membership-derived authority — admin / guest accounts never had a Staffel link to anchor a
   * Logistician / MissionManager flag on, so the empty-memberships branch is now a clean no-op.
   *
   * @param user the local {@link User} record produced by {@link
   *     UserReconciliationService#syncUser(Jwt)}; never {@code null}.
   * @param authorities the mutable authority list being assembled by the converter; flags are
   *     appended in place.
   */
  private void addMembershipDerivedRoles(
      @NonNull User user, @NonNull Collection<GrantedAuthority> authorities) {
    List<OrgUnitMembership> memberships =
        orgUnitMembershipRepository.findAllByIdUserId(user.getId());

    if (memberships.isEmpty()) {
      // Memberless users (admins, guests) carry no Logistician / MissionManager flag — the V101
      // column drop made org_unit_membership the single source of truth.
      return;
    }

    // Any functional rank (MembershipRole != MEMBER) is automatically BOTH a logistician AND a
    // mission manager of its org unit — the rank sits above both within that unit, mirroring how
    // admin outranks every role and an Officer is logistician + mission manager of their own
    // squadron (#344). The rank is kind-scoped by the V184 chk_org_unit_membership_role_kind CHECK,
    // so a squadron rank only ever widens its own Staffel, an SK_LEAD its own SK, and so on.
    //
    // Epic #800 / REQ-ROLE-001/002 unifies the former five boolean leadership flags into the rank
    // enum and extends the "leadership ⊇ logistician + mission manager" principle to the squadron
    // ranks: a Staffelleiter / Kommandoleiter / stellv. Kommandoleiter / Ensign confers
    // officer-equivalent reach over its own squadron — never admin — exactly as an SK_LEAD does for
    // its SK and a Bereichsleitung / OL membership does for its subtree. The flat role is the
    // back-compat surface for role-only @PreAuthorize gates; the per-unit scoping is applied
    // separately (contextual authorities below + OwnerScopeService's scope predicate).
    boolean anyLogistician =
        memberships.stream().anyMatch(m -> m.isLogistician() || confersFlatOfficerRole(m));
    boolean anyMissionManager =
        memberships.stream().anyMatch(m -> m.isMissionManager() || confersFlatOfficerRole(m));

    if (anyLogistician) {
      authorities.add(new SimpleGrantedAuthority("ROLE_LOGISTICIAN"));
    }
    if (anyMissionManager) {
      authorities.add(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER"));
    }

    // §6.1 — one contextual authority per (membership, own-unit-officer reach) pair. The per-row
    // evaluation here is what differentiates this from the flat OR-union above: a user with the
    // Logistician flag on Staffel A but not on SK B gets a contextual authority for A only,
    // even though the flat ROLE_LOGISTICIAN was granted by either of them. That distinction is
    // what callers using @ownerScopeService.hasRoleInOrgUnit(...) need to know about. An SK_LEAD —
    // and, from epic #800, every squadron rank (Staffelleiter / Kommandoleiter / stellv. / Ensign)
    // — gets its own unit's contextual LOGISTICIAN + MISSION_MANAGER authorities here too
    // (own-unit officer ⊇ logistician + mission manager, no cascade). Bereich/OL seats are NOT
    // minted here: their own-seat contextual authority comes from the cascade below, which already
    // includes the seat itself.
    for (OrgUnitMembership m : memberships) {
      boolean ownUnitOfficer = confersOwnUnitOfficerReach(m);
      if (m.isLogistician() || ownUnitOfficer) {
        authorities.add(new OrgUnitContextualAuthority("LOGISTICIAN", m.getId().getOrgUnitId()));
      }
      if (m.isMissionManager() || ownUnitOfficer) {
        authorities.add(
            new OrgUnitContextualAuthority("MISSION_MANAGER", m.getId().getOrgUnitId()));
      }
    }

    // Epic #692 / REQ-ORG-015 — cascade the contextual authorities down the hierarchy. A
    // Bereichsleitung member acts as LOGISTICIAN + MISSION_MANAGER in every Staffel/SK below their
    // Bereich (and in the Bereich itself); an OL member in every org unit. The reachable id set is
    // resolved by the shared OrgUnitCascadeService so the scope resolver and this converter agree
    // on exactly which units a leader reaches. Plain Staffel/SK memberships contribute nothing here
    // (handled by the per-row loop above), so for a caller with no Bereich/OL leadership flag this
    // set is empty and the authority list is unchanged from the pre-#692 behaviour.
    for (UUID cascadedOrgUnitId : orgUnitCascadeService.cascadedOfficerReach(memberships)) {
      authorities.add(new OrgUnitContextualAuthority("LOGISTICIAN", cascadedOrgUnitId));
      authorities.add(new OrgUnitContextualAuthority("MISSION_MANAGER", cascadedOrgUnitId));
    }
  }

  /**
   * {@code true} iff holding {@code m} promotes the caller to the flat, officer-equivalent {@code
   * ROLE_LOGISTICIAN} / {@code ROLE_MISSION_MANAGER} (epic #800, REQ-ROLE-001/002). A membership
   * qualifies when it carries any functional rank — i.e. {@link
   * MembershipRole#confersOwnLevelOversight()} ({@code role != MEMBER}): an SK-Lead, a
   * Bereichsleitung rank, the OL, <em>or</em> a squadron rank (Staffelleiter / Kommandoleiter /
   * stellv. Kommandoleiter / Ensign), each of which ranks at or above logistician + mission manager
   * on its own unit (#344). The flat role is the back-compat surface for role-only
   * {@code @PreAuthorize} gates.
   *
   * <p>This is deliberately <b>not</b> the cascade-reach predicate. Which org units a leader
   * reaches downward — and thus which contextual authorities are minted — is computed separately by
   * {@link OrgUnitCascadeService#cascadedOfficerReach(Collection)}, which, unlike this method,
   * cascades only area / OL ranks: an SK-Lead and a squadron rank keep own-unit-only reach
   * (REQ-ROLE-002, REQ-ORG-017) and receive their unit's contextual authority from the per-row loop
   * above (see {@link #confersOwnUnitOfficerReach(OrgUnitMembership)}), not from the cascade.
   *
   * <p>A per-membership Logistician / MissionManager flag is handled by the explicit {@code
   * m.isLogistician()} / {@code m.isMissionManager()} terms at the call sites (a plain logistician
   * confers only the logistician flat role, not mission manager), and a rank-less ({@link
   * MembershipRole#MEMBER}) seat confers nothing — so both return {@code false} here.
   *
   * @param m the membership row to classify; never {@code null}.
   * @return {@code true} iff {@code m} carries any functional rank other than {@link
   *     MembershipRole#MEMBER}.
   */
  private static boolean confersFlatOfficerRole(@NonNull OrgUnitMembership m) {
    return m.getRole().confersOwnLevelOversight();
  }

  /**
   * {@code true} iff holding {@code m} mints its <em>own</em> org unit's contextual {@code
   * LOGISTICIAN@<id>} + {@code MISSION_MANAGER@<id>} authorities in the per-row loop, without any
   * downward cascade (epic #800, REQ-ROLE-002). This is the own-unit-officer set: an {@link
   * MembershipRole#SK_LEAD} (logistician + mission manager of its SK, #344) and the four squadron
   * ranks ({@link MembershipRole#isSquadronRank()}), which the baseline grant treats as
   * officer-equivalent over their own squadron only.
   *
   * <p>Area ranks ({@link MembershipRole#isAreaRank()}) and {@link MembershipRole#OL_MEMBER} are
   * deliberately excluded here: their own-seat contextual authority is contributed by {@link
   * OrgUnitCascadeService#cascadedOfficerReach(Collection)}, which includes the Bereich/OL seat
   * itself alongside its descendants. Including them here too would double-mint the seat's
   * authority. {@link MembershipRole#MEMBER} confers nothing.
   *
   * @param m the membership row to classify; never {@code null}.
   * @return {@code true} iff {@code m} is an SK-Lead or one of the four squadron ranks.
   */
  private static boolean confersOwnUnitOfficerReach(@NonNull OrgUnitMembership m) {
    return m.getRole() == MembershipRole.SK_LEAD || m.getRole().isSquadronRank();
  }
}
