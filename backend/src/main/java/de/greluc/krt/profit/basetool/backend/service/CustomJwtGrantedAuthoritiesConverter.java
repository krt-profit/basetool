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
 * Translates a Keycloak JWT into the authorities checked by {@code @PreAuthorize}.
 *
 * <p>Merges realm roles as {@code ROLE_*}, their local permissions, and flat and contextual
 * logistician / mission-manager roles derived from org-unit memberships. Each miss syncs the local
 * user via {@link UserReconciliationService#syncUser(Jwt)}, retrying optimistic-lock conflicts up
 * to {@value #MAX_SYNC_ATTEMPTS} times before rejecting with {@link
 * AuthenticationServiceException}.
 */
@Component
@Slf4j
public class CustomJwtGrantedAuthoritiesConverter
    implements Converter<Jwt, Collection<GrantedAuthority>> {

  private static final int MAX_SYNC_ATTEMPTS = 3;

  /**
   * The single authority a machine identity carries (ADR-0129). It grants nothing on its own; the
   * gateway's access comes from the member it acts for.
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
   * Claims that change on every token of the same session and are excluded from the claims
   * fingerprint, so a refreshed token hits the cache.
   */
  private static final Set<String> PER_TOKEN_CLAIMS = Set.of("iat", "exp", "nbf", "jti");

  private final UserReconciliationService userReconciliationService;
  private final IngestGatewayProperties ingestGatewayProperties;
  private final OrgUnitMembershipRepository orgUnitMembershipRepository;
  private final OrgUnitCascadeService orgUnitCascadeService;

  /**
   * Per-session cache of the assembled authority collection (ADR-0174).
   *
   * <p>Keyed by {@link #authoritiesCacheKey(Jwt)} for the configured {@link
   * AuthoritiesCacheProperties#ttl() TTL}. Only successful results are cached, as immutable copies.
   */
  private final Cache<String, Collection<GrantedAuthority>> authoritiesCache;

  /**
   * Creates the converter and sizes its authorities cache from {@code
   * app.security.authorities-cache.ttl}.
   *
   * @param userReconciliationService creates or updates the local {@code app_user} row on a cache
   *     miss
   * @param ingestGatewayProperties the machine-identity allowlist (ADR-0129)
   * @param orgUnitMembershipRepository reads the memberships behind the officer authorities
   * @param orgUnitCascadeService expands a leadership membership down the org-unit tree
   * @param authoritiesCacheProperties supplies the cache TTL
   * @param meterRegistry binds the cache meters under {@code cache=}{@value
   *     #AUTHORITIES_CACHE_NAME}
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
   * Resolves the authorities for {@code jwt}, cached per {@code (sub, session, azp, claims)} for
   * the configured TTL (ADR-0174); a miss or an unkeyable token runs {@link
   * #assembleAuthorities(Jwt)}.
   *
   * @param jwt the validated Keycloak access token
   * @return the authorities checked against {@code @PreAuthorize}
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
   * Builds the cache key {@code sub | session | azp | claims-fingerprint}, or {@code null} to
   * bypass the cache.
   *
   * <p>The session is {@code sid}, falling back to {@code issuedAt}; a token with neither, or
   * without {@code sub}, is not cached. {@code azp} is part of the key because the assembly reads
   * it (REQ-SEC-036). The fingerprint makes a changed role or account miss.
   *
   * @param jwt the access token
   * @return the cache key, or {@code null} to bypass caching
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
   * Hashes the token's claims, minus {@link #PER_TOKEN_CLAIMS}, into a hex SHA-256 digest over an
   * unambiguous canonical rendering.
   *
   * @param jwt the access token
   * @return the lower-case hex digest
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
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  /**
   * Appends an unambiguous rendering of one claim value: {@code m{k=v;…}} for a map with sorted
   * keys, {@code l[v;…]} for a collection, {@code s<len>:text} for anything else and {@code n} for
   * {@code null}.
   *
   * @param out the buffer to append to
   * @param value the claim value; may be {@code null}
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
   * Assembles the authorities from scratch: syncs the local user (retrying on optimistic-lock
   * contention), returns {@code ROLE_PENDING_APPROVAL} for a non-approved registration, and
   * otherwise merges realm-role, permission and membership-derived authorities.
   *
   * @param jwt the access token
   * @return the freshly assembled authorities
   */
  private Collection<GrantedAuthority> assembleAuthorities(@NonNull Jwt jwt) {
    if (ingestGatewayProperties.isGatewayClient(jwt.getClaimAsString("azp"))) {
      return List.of(new SimpleGrantedAuthority(GATEWAY_AUTHORITY));
    }
    ObjectOptimisticLockingFailureException lastLockingFailure = null;
    for (int attempt = 1; attempt <= MAX_SYNC_ATTEMPTS; attempt++) {
      try {
        UserReconciliationService.ReconciledUser reconciled =
            userReconciliationService.syncUser(jwt);

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
   * Assembles a member's authorities from the database alone, shared by the login path and the
   * ingest gateway's acting-member path (ADR-0129).
   *
   * @param user the member whose authorities to assemble
   * @return the authorities, or the lone {@code ROLE_PENDING_APPROVAL} for a non-approved
   *     registration
   */
  public Collection<GrantedAuthority> assembleFor(@NonNull User user) {
    return assembleFor(user, user.getRoles());
  }

  /**
   * Assembles a member's authorities from the given role set rather than the one stored on {@code
   * user}, for partial-scope clients (REQ-SEC-036).
   *
   * @param user the member whose approval status and memberships to read
   * @param roles the roles to authorise with; the stored set for every ordinary client
   * @return the authorities, or the lone {@code ROLE_PENDING_APPROVAL} for a non-approved
   *     registration
   */
  public Collection<GrantedAuthority> assembleFor(
      @NonNull User user, @NonNull Collection<Role> roles) {
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

    addMembershipDerivedRoles(user, authorities);

    if (authorities.isEmpty()) {
      return List.of(new SimpleGrantedAuthority(Roles.NO_ROLE_MARKER));
    }

    return authorities;
  }

  /**
   * Appends the membership-derived authorities.
   *
   * <ul>
   *   <li>flat {@code ROLE_LOGISTICIAN} / {@code ROLE_MISSION_MANAGER} from the union of all
   *       memberships;
   *   <li>one {@link OrgUnitContextualAuthority} per membership and flag;
   *   <li>contextual authorities for the org units a leadership membership reaches downward, via
   *       {@link OrgUnitCascadeService#cascadedOfficerReach(java.util.Collection)} (REQ-ORG-015).
   * </ul>
   *
   * @param user the local {@link User} record
   * @param authorities the authority list being assembled; appended in place
   */
  private void addMembershipDerivedRoles(
      @NonNull User user, @NonNull Collection<GrantedAuthority> authorities) {
    List<OrgUnitMembership> memberships =
        orgUnitMembershipRepository.findAllByIdUserId(user.getId());

    if (memberships.isEmpty()) {
      return;
    }

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

    for (UUID cascadedOrgUnitId : orgUnitCascadeService.cascadedOfficerReach(memberships)) {
      authorities.add(new OrgUnitContextualAuthority("LOGISTICIAN", cascadedOrgUnitId));
      authorities.add(new OrgUnitContextualAuthority("MISSION_MANAGER", cascadedOrgUnitId));
    }
  }

  /**
   * Whether holding {@code m} confers the flat {@code ROLE_LOGISTICIAN} / {@code
   * ROLE_MISSION_MANAGER}: true for any functional rank ({@link
   * MembershipRole#confersOwnLevelOversight()}) (REQ-ROLE-001/002).
   *
   * @param m the membership row to classify
   * @return {@code true} iff {@code m} carries a rank other than {@link MembershipRole#MEMBER}
   */
  private static boolean confersFlatOfficerRole(@NonNull OrgUnitMembership m) {
    return m.getRole().confersOwnLevelOversight();
  }

  /**
   * Whether holding {@code m} mints its own org unit's contextual {@code LOGISTICIAN@<id>} and
   * {@code MISSION_MANAGER@<id>} authorities without cascade (REQ-ROLE-002). Area and OL ranks get
   * theirs from the cascade instead.
   *
   * @param m the membership row to classify
   * @return {@code true} iff {@code m} is an SK-Lead or one of the four squadron ranks
   */
  private static boolean confersOwnUnitOfficerReach(@NonNull OrgUnitMembership m) {
    return m.getRole() == MembershipRole.SK_LEAD || m.getRole().isSquadronRank();
  }
}
