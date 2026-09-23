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
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.support.AuthoritiesCacheProperties;
import de.greluc.krt.profit.basetool.backend.support.IngestGatewayProperties;
import de.greluc.krt.profit.basetool.backend.support.OrgUnitContextualAuthority;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
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
@Slf4j
public class CustomJwtGrantedAuthoritiesConverter
    implements Converter<Jwt, Collection<GrantedAuthority>> {

  private static final int MAX_SYNC_ATTEMPTS = 3;

  /**
   * The single authority a machine identity carries (ADR-0129).
   *
   * <p>Deliberately a named authority rather than an empty collection: an empty set makes the
   * caller anonymous to every downstream check, so a misconfiguration would read as "not
   * authenticated" instead of "authenticated as a machine" — and {@code
   * .anyRequest().authenticated()} would then refuse it for a reason that points nowhere. It grants
   * nothing on its own; the gateway's actual access comes from the member it acts for.
   */
  public static final String GATEWAY_AUTHORITY = "ROLE_INGEST_GATEWAY";

  private static final long RETRY_BACKOFF_MILLIS = 50L;

  /** Upper bound on distinct cached {@code (sub, session, azp, claims)} entries. */
  private static final long AUTHORITIES_CACHE_MAX_SIZE = 10_000;

  /**
   * The {@code cache} tag the memoisation is published under ({@code cache_gets_total{result}},
   * {@code cache_size}, {@code cache_evictions_total}), next to the Spring-managed caches of {@code
   * CacheConfig} — so the Spring-apps dashboard's hit-ratio panels and the {@code CacheHitRatioLow}
   * / {@code CacheSizeEvictionsHigh} alerts cover it without a rule of their own.
   */
  static final String AUTHORITIES_CACHE_NAME = "jwt-authorities";

  /**
   * Claims that change on every token the same session is issued and carry nothing the assembly
   * reads, so they stay out of the claims fingerprint: with them in it every refresh would be a
   * miss again, which is exactly the defect keying on {@code sid} removes (BE-PERF-08). {@code sid}
   * and {@code azp} are left in — they are part of the key anyway — and so is every other claim,
   * because a fingerprint that forgot one the assembly reads would serve a stale answer.
   */
  private static final Set<String> PER_TOKEN_CLAIMS = Set.of("iat", "exp", "nbf", "jti");

  private final UserReconciliationService userReconciliationService;
  private final IngestGatewayProperties ingestGatewayProperties;
  private final OrgUnitMembershipRepository orgUnitMembershipRepository;
  private final OrgUnitCascadeService orgUnitCascadeService;

  /**
   * Per-session memoisation of the fully-assembled authority collection (#1141, ADR-0174). The
   * resource-server authorities converter runs on <em>every</em> authenticated API call — every
   * fragment refetch, every live-sync coalesce burst, every check-in — and each miss pays {@link
   * UserReconciliationService#syncUser(Jwt)} (a write-capable transaction) plus a handful of
   * SELECTs (user load, {@code user_roles}, the role catalogue read once with its permissions, and
   * the membership read).
   *
   * <p>Keyed on the Keycloak session ({@code sid}) rather than on the token's {@code issuedAt}
   * since 2026-09-23 (BE-PERF-08, ADR-0174 amendment): a refreshed access token of the same session
   * is a hit, so the configured {@link AuthoritiesCacheProperties#ttl() TTL} — not the five-minute
   * access-token lifespan — decides how often the storm runs. A fresh login is a new session and
   * misses; a token whose content changed (a Keycloak role granted or revoked, a renamed account)
   * misses through the claims fingerprint in the key. Only successful results are cached (an
   * exception propagates uncached), the cached value is an immutable copy so a downstream mutation
   * cannot corrupt it, and a token with neither {@code sid} nor {@code issuedAt}, or without {@code
   * sub}, bypasses the cache entirely (always recomputed).
   */
  private final Cache<String, Collection<GrantedAuthority>> authoritiesCache;

  /**
   * Creates the converter and sizes its authorities cache from configuration.
   *
   * <p>An explicit constructor rather than {@code @RequiredArgsConstructor}: the cache's {@code
   * expireAfterWrite} window comes from {@code app.security.authorities-cache.ttl} (ADR-0174), so
   * it cannot be built in a field initialiser that runs before any dependency is available.
   *
   * @param userReconciliationService creates or updates the local {@code app_user} row on a cache
   *     miss; never {@code null}.
   * @param ingestGatewayProperties the machine-identity allowlist deciding whether a caller is a
   *     gateway rather than a member (ADR-0129); never {@code null}.
   * @param orgUnitMembershipRepository reads the memberships whose {@code is_logistician} / {@code
   *     is_mission_manager} flags become flat and contextual authorities; never {@code null}.
   * @param orgUnitCascadeService expands a leadership membership downward over the org-unit tree
   *     (REQ-ORG-015); never {@code null}.
   * @param authoritiesCacheProperties supplies the memoisation TTL, validated at startup to be
   *     positive and at most {@link AuthoritiesCacheProperties#MAX_TTL}; never {@code null}.
   * @param meterRegistry the registry the cache's hit / miss / eviction / size meters are bound to
   *     under {@code cache=}{@value #AUTHORITIES_CACHE_NAME}; never {@code null}.
   */
  public CustomJwtGrantedAuthoritiesConverter(
      @NonNull UserReconciliationService userReconciliationService,
      @NonNull IngestGatewayProperties ingestGatewayProperties,
      @NonNull OrgUnitMembershipRepository orgUnitMembershipRepository,
      @NonNull OrgUnitCascadeService orgUnitCascadeService,
      @NonNull AuthoritiesCacheProperties authoritiesCacheProperties,
      @NonNull MeterRegistry meterRegistry) {
    this.userReconciliationService = userReconciliationService;
    this.ingestGatewayProperties = ingestGatewayProperties;
    this.orgUnitMembershipRepository = orgUnitMembershipRepository;
    this.orgUnitCascadeService = orgUnitCascadeService;
    this.authoritiesCache =
        Caffeine.newBuilder()
            .maximumSize(AUTHORITIES_CACHE_MAX_SIZE)
            .expireAfterWrite(authoritiesCacheProperties.ttl())
            .recordStats()
            .build();
    CaffeineCacheMetrics.monitor(meterRegistry, authoritiesCache, AUTHORITIES_CACHE_NAME);
  }

  /**
   * Resolves the authorities for {@code jwt}, memoised per {@code (sub, session, azp, claims)} for
   * the configured {@link AuthoritiesCacheProperties#ttl() TTL} (#1141, ADR-0174). On a cache hit
   * the whole {@link #assembleAuthorities(Jwt)} pipeline — {@code syncUser} and its query storm —
   * is skipped; on a miss (or an unkeyable token) it is assembled fresh and, when keyable, cached
   * as an immutable copy.
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
   * Builds the memoisation key {@code sub | session | azp | claims-fingerprint}, or {@code null} to
   * bypass the cache for this token.
   *
   * <p><strong>Session.</strong> The Keycloak session id ({@code sid}) when the token carries one —
   * every access token refreshed within one login shares it, so a refresh is a hit and the TTL
   * alone bounds staleness (BE-PERF-08). A token without {@code sid} (a client-credentials grant,
   * which has no user session) falls back to its {@code issuedAt}, exactly the pre-2026-09-23 key;
   * a token with neither, or without {@code sub}, is not cached at all.
   *
   * <p><strong>{@code azp} belongs in the key because the assembly reads it.</strong> Since
   * REQ-SEC-036 / ADR-0141 the authority set is not a pure function of the subject: {@link
   * #assembleAuthorities} branches on the authorized party twice - the ingest-gateway
   * short-circuit, and the partial-role-scope client list that decides whether a client's role
   * claim may replace the stored set. Two clients of the same person must never share an entry, or
   * the first to arrive decides the authorities for both - precisely the admin-demotion (and,
   * mirrored, admin-elevation) REQ-SEC-036 exists to prevent. A memoisation key must be a superset
   * of the inputs the memoised computation reads.
   *
   * <p><strong>The claims fingerprint keeps a role change as fast as before.</strong> With {@code
   * issuedAt} in the key, every refreshed token re-read everything, so a realm role granted or
   * revoked in Keycloak took effect at the next refresh. A session-scoped key alone would serve the
   * old answer until the TTL ran out. The fingerprint is a SHA-256 over every claim except the four
   * that change per token ({@link #PER_TOKEN_CLAIMS}), so a refresh whose content is unchanged hits
   * and one carrying different roles, a renamed account or a new e-mail address misses.
   *
   * @param jwt the access token.
   * @return the cache key, or {@code null} to bypass caching for this token.
   */
  @Nullable
  static String authoritiesCacheKey(@NonNull Jwt jwt) {
    String sub = jwt.getSubject();
    if (sub == null) {
      return null;
    }
    String sid = jwt.getClaimAsString("sid");
    String session;
    if (sid != null && !sid.isBlank()) {
      session = "sid:" + sid;
    } else {
      Instant issuedAt = jwt.getIssuedAt();
      if (issuedAt == null) {
        return null;
      }
      session = "iat:" + issuedAt.toEpochMilli();
    }
    return sub + '|' + session + '|' + jwt.getClaimAsString("azp") + '|' + claimsFingerprint(jwt);
  }

  /**
   * Hashes the token's claims, minus {@link #PER_TOKEN_CLAIMS}, into a hex SHA-256 digest.
   *
   * <p>The input is an unambiguous canonical rendering: map keys sorted, every value tagged with
   * its kind and every string length-prefixed, so no two different claim sets can render to the
   * same text — a collision here would hand one token's authorities to a differently-roled token of
   * the same session.
   *
   * @param jwt the access token.
   * @return the lower-case hex digest of the canonical claim rendering.
   */
  private static String claimsFingerprint(@NonNull Jwt jwt) {
    Map<String, Object> claims = new TreeMap<>(jwt.getClaims());
    claims.keySet().removeAll(PER_TOKEN_CLAIMS);
    StringBuilder canonical = new StringBuilder(512);
    appendCanonical(canonical, claims);
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      // Every Java platform is required to provide SHA-256 (MessageDigest Javadoc).
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  /**
   * Appends an unambiguous rendering of one claim value: {@code m{k=v;…}} for a map with sorted
   * keys, {@code l[v;…]} for a collection in iteration order, {@code s<len>:text} for anything else
   * rendered through {@link String#valueOf(Object)}, and {@code n} for {@code null}.
   *
   * @param out the buffer to append to.
   * @param value the claim value; may be {@code null}.
   */
  private static void appendCanonical(@NonNull StringBuilder out, @Nullable Object value) {
    if (value == null) {
      out.append('n');
    } else if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new TreeMap<>();
      map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
      out.append("m{");
      sorted.forEach(
          (k, v) -> {
            appendCanonical(out, k);
            out.append('=');
            appendCanonical(out, v);
            out.append(';');
          });
      out.append('}');
    } else if (value instanceof Collection<?> collection) {
      out.append("l[");
      for (Object element : collection) {
        appendCanonical(out, element);
        out.append(';');
      }
      out.append(']');
    } else {
      String text = String.valueOf(value);
      out.append('s').append(text.length()).append(':').append(text);
    }
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
    // A MACHINE IS NOT A MEMBER (ADR-0129). The ingest gateway authenticates as a Keycloak service
    // account, which at token level is indistinguishable from a person: a real user with a UUID
    // `sub` and realm roles. Without this the very first call from the gateway ran the whole
    // registration flow on it — an app_user row, a PENDING stamp, the default personal blueprints,
    // and an admin notification "Neue Registrierung wartet auf Freigabe" — and then 403'd the
    // gateway on its own account. It locked itself out on its first authentication (2026-08-04).
    //
    // Keyed on `azp` against the SAME allowlist that already governs the far more dangerous
    // on-behalf-of decision (ActingMemberFilter), so this adds no new trust: `azp` is a claim
    // inside a Keycloak-signed token, not something a client can set. Empty allowlist means nobody.
    //
    // NOT a realm role: mapRolesTracked silently drops names absent from the local catalogue and
    // falls back to Guest, so the marker would vanish and the row would be created anyway — and
    // forgetting the grant in a new realm would fail OPEN. NOT a `service-account-` prefix either:
    // the `sub` is a UUID, and that prefix lives on `preferred_username`, a renameable display
    // convention.
    //
    // The scheduled Keycloak roster sync needs no equivalent carve-out: measured against Keycloak
    // 26.7, `GET /admin/realms/{realm}/users` omits service-account users entirely, even though the
    // user demonstrably exists.
    if (ingestGatewayProperties.isGatewayClient(jwt.getClaimAsString("azp"))) {
      return List.of(new SimpleGrantedAuthority(GATEWAY_AUTHORITY));
    }
    ObjectOptimisticLockingFailureException lastLockingFailure = null;
    for (int attempt = 1; attempt <= MAX_SYNC_ATTEMPTS; attempt++) {
      try {
        UserReconciliationService.ReconciledUser reconciled =
            userReconciliationService.syncUser(jwt);

        // Epic #720, Track 1 / REQ-SEC-017: a PENDING (or REJECTED) registration is granted NO
        // authorities, and REQ-SEC-053 refuses an approved account that maps to no role at all.
        // Both short-circuits live in assembleFor so the acting-member path shares them.
        //
        // Authorise with the roles the TOKEN presented, not with the row's. The two are the same
        // set for every client whose claim is complete; they differ for a partial-scope client,
        // which is exactly the point (REQ-SEC-036). Reading the row here instead would hand an
        // administrator using the mobile app the ADMIN authority its token deliberately withheld
        // -- the row still holds Admin precisely because that path no longer overwrites it.
        return assembleFor(reconciled.user(), reconciled.effectiveRoles());
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
   * Assembles a member's authorities from the database alone, with no token involved.
   *
   * <p>Extracted so the ordinary login path and the ingest gateway's acting-member path share
   * <em>one</em> implementation (ADR-0129). Two copies would drift, and the way they would drift is
   * the dangerous one: a member acting through the gateway would silently carry a different
   * authority set than the same member logging in.
   *
   * <p>Nothing here reads the token, which is what makes the acting-member path exact rather than
   * approximate: approval status, roles, per-role permissions and every org-unit-derived authority
   * are database reads already.
   *
   * @param user the member whose authorities to assemble
   * @return the authorities, or the lone {@code ROLE_PENDING_APPROVAL} for a non-approved
   *     registration
   */
  public Collection<GrantedAuthority> assembleFor(@NonNull User user) {
    return assembleFor(user, user.getRoles());
  }

  /**
   * Assembles a member's authorities from a role set that may differ from the one stored on {@code
   * user}.
   *
   * <p>The split exists for partial-scope clients (REQ-SEC-036). Their tokens carry a deliberately
   * narrowed role list which {@link UserReconciliationService#syncUser(Jwt)} refuses to write down;
   * the request must still be authorised with that narrower list, so the caller passes it here
   * explicitly rather than the converter reading it back off the row it just declined to change.
   *
   * <p>Everything else about the assembly is unchanged and still database-derived: the approval
   * short-circuit, the per-role permissions, and every org-unit-derived authority. Only *which*
   * roles seed it is parameterised.
   *
   * @param user the member whose approval status and memberships to read
   * @param roles the roles to authorise with; the stored set for every ordinary client
   * @return the authorities, or the lone {@code ROLE_PENDING_APPROVAL} for a non-approved
   *     registration
   */
  public Collection<GrantedAuthority> assembleFor(
      @NonNull User user, @NonNull Collection<Role> roles) {
    // Epic #720, Track 1 / REQ-SEC-017: a PENDING (or REJECTED) registration is granted NO
    // authorities. The ENTIRE assembly below — realm roles, permissions, membership-derived flat
    // roles, contextual + cascaded authorities — is short-circuited to a single
    // ROLE_PENDING_APPROVAL: a pending user is routed to the "waiting for approval" surface.
    if (!user.isApproved()) {
      return List.of(new SimpleGrantedAuthority("ROLE_PENDING_APPROVAL"));
    }

    Collection<GrantedAuthority> authorities =
        roles.stream()
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

    // R6.d / Plan D3: source the per-role flags from the user's OrgUnit memberships. Any membership
    // that carries `is_logistician = true` promotes the caller to the flat ROLE_LOGISTICIAN
    // authority (same for ROLE_MISSION_MANAGER).
    addMembershipDerivedRoles(user, authorities);

    // REQ-SEC-053 / ADR-0159: an approved account that ends up with NO authority at all is refused,
    // not admitted with an empty set. Until V239 it was mapped onto the authority-less GUEST role,
    // which the URL matrix's anonymous families then let through — so "no role" quietly meant "the
    // guest surface". With that surface gone the empty set would mean something worse: an
    // authenticated principal that passes every isAuthenticated() gate and fails only the ones that
    // name a role, which is a per-endpoint accident rather than a decision. ROLE_NO_ROLE is a
    // marker, not a permission; PendingApprovalAccessFilter turns it into 403 NO_ROLE before a
    // handler runs.
    //
    // <b>The check is on the ASSEMBLED set, not on the incoming role list.</b> A member can hold no
    // realm role and still be authorised: `addMembershipDerivedRoles` promotes anyone whose OrgUnit
    // membership carries `is_logistician` or `is_mission_manager`, and those flags live on the
    // membership rather than in Keycloak. Short-circuiting on `roles.isEmpty()` would have refused
    // exactly those people — an SK lead with no realm role is a real shape, and the converter's own
    // tests are full of it.
    //
    // It sits in assembleFor rather than on the JWT path so both callers get it: the
    // resource-server
    // conversion above, and DatabaseActingMemberAuthorities on the ingest gateway's acting-member
    // path (ADR-0129), which installs an authentication without inspecting it.
    if (authorities.isEmpty()) {
      return List.of(new SimpleGrantedAuthority(Roles.NO_ROLE_MARKER));
    }

    return authorities;
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
