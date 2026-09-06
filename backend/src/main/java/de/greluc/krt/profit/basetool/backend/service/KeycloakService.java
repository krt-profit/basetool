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

import de.greluc.krt.profit.basetool.backend.config.KeycloakSyncProperties;
import de.greluc.krt.profit.basetool.backend.config.KeycloakTrustSupport;
import de.greluc.krt.profit.basetool.backend.exception.ExternalServiceException;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.dto.KeycloakUserDto;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Client for the Keycloak Admin REST API. The bulk of it is the read-only scheduled user sync;
 * {@link #linkDiscordIdentity} and {@link #deleteUser} are the only writes — the narrow
 * account-linking path (REQ-SEC-026) that attaches a Discord federated identity to an existing
 * account and removes the throwaway Discord user. Those writes require the service account to hold
 * the {@code manage-users} realm-management role on top of the {@code view-users}/{@code
 * view-realm} the sync needs.
 *
 * <p>Obtains an admin access token via the {@code client_credentials} grant against the realm's
 * {@code openid-connect/token} endpoint, then pages through {@code /admin/realms/{realm}/users} and
 * joins each user record with the roles the app cares about. Role membership is resolved
 * <em>role-indexed</em> — one paged {@code GET /roles/{name}/users} per app-relevant realm role
 * (see {@link #fetchRoleMemberships}) — rather than one {@code /role-mappings} call per user, so
 * the per-run Admin-API call count is bounded by the (small) number of mappable roles instead of
 * the user count. That N&#8594;#roles collapse is the primary 5000-account scaling fix: the old
 * per-user fan-out issued ~2 admin calls per user (one roles, one federated-identity), which at
 * thousands of accounts was a multi-minute Keycloak-hammering burst. The Discord federated-identity
 * back-fill is likewise made <em>incremental</em> — read only for users who do not already carry a
 * local Discord link (see the {@code knownDiscordLinkedIds} argument of {@link #fetchUsers}).
 * Failures are swallowed at the top level: the scheduler treats an empty list as "skip this run"
 * and never wipes local users based on it (see {@link UserService#markMissingUsers}).
 *
 * <p>The {@link #BEARER_PREFIX} constant exists so the literal {@code "Bearer "} never gets typed
 * by hand at a new call site — open-coding the prefix in a future log statement is the canonical
 * way to accidentally leak a raw token; the PII masker catches it, but defense in depth is cheaper
 * than auditing every new log line.
 */
@Service
@Slf4j
public class KeycloakService {

  /**
   * RFC 6750 Bearer-token authentication scheme prefix used when handing the access token to
   * Keycloak's admin REST API. Centralised here so the literal never gets typed by hand at a call
   * site — open-coding {@code "Bearer " + token} in a log statement or a future request builder is
   * the canonical way to accidentally leak a raw token into the logs (the PII masker catches it,
   * but defence in depth is cheaper than auditing every new log line).
   */
  private static final String BEARER_PREFIX = "Bearer ";

  /**
   * Name of the Spring SSL bundle whose truststore pins the self-signed certificate the production
   * Keycloak presents on its internal {@code https://keycloak:18443} admin connector. Defined in
   * {@code application-prod.yml}; absent in dev/test, where the admin URL is plain HTTP.
   */
  private static final String KEYCLOAK_TRUST_BUNDLE = "keycloak-trust";

  /**
   * Alias of the Discord identity provider in the realm. Must match the alias configured in
   * Keycloak (fixed to {@code discord} by {@code docs/keycloak/DISCORD_KEYCLOAK_SETUP.md}, since
   * the alias is the broker redirect path and the {@code kc_idp_hint}). Used to pick the Discord
   * entry out of a user's federated-identity list when back-filling the local Discord link
   * (REQ-DATA-006).
   */
  private static final String DISCORD_IDP_ALIAS = "discord";

  private final KeycloakSyncProperties properties;

  /** Micrometer registry for the {@code basetool_keycloak_sync_fetch_failures_total} counter. */
  private final MeterRegistry meterRegistry;

  /**
   * Pre-built request factory pinned (via {@link KeycloakTrustSupport}) to trust only the {@link
   * #KEYCLOAK_TRUST_BUNDLE} truststore, or {@code null} when that bundle is not configured for the
   * active profile. {@code null} makes {@link #adminClient()} fall back to the default {@link
   * RestClient}, which trusts the JVM {@code cacerts} and is what the dev/test plain-HTTP admin URL
   * needs. Built once at construction so the keystore is parsed a single time, not per request.
   */
  @Nullable private final ClientHttpRequestFactory trustedRequestFactory;

  /**
   * Wires the user-sync properties and resolves the TLS trust for the Keycloak Admin API once at
   * startup.
   *
   * @param properties the {@code app.keycloak.sync.*} configuration (admin URL, realm, credentials)
   * @param sslBundles the registered Spring SSL bundles; consulted for {@link
   *     #KEYCLOAK_TRUST_BUNDLE} to pin the self-signed Keycloak certificate in production
   * @param meterRegistry the Micrometer registry for the {@code
   *     basetool_keycloak_sync_fetch_failures_total} counter
   */
  public KeycloakService(
      KeycloakSyncProperties properties, SslBundles sslBundles, MeterRegistry meterRegistry) {
    this.properties = properties;
    this.trustedRequestFactory = buildTrustedRequestFactory(sslBundles);
    this.meterRegistry = meterRegistry;
  }

  /**
   * Resolves the truststore-pinned {@link ClientHttpRequestFactory} for the Keycloak Admin API by
   * delegating to {@link KeycloakTrustSupport#trustedRequestFactory}, and logs a debug line when
   * the {@link #KEYCLOAK_TRUST_BUNDLE} bundle is absent (dev/test plain-HTTP admin URL) so the
   * fallback to the default {@link RestClient} is visible. Hostname verification stays at the JDK
   * default, so the pinned certificate must carry {@code dns:keycloak} in its SAN.
   *
   * @param sslBundles the registered Spring SSL bundles
   * @return a truststore-pinned request factory, or {@code null} to fall back to the default {@link
   *     RestClient} (JVM trust store + plain HTTP for dev/test)
   * @throws IllegalStateException if the bundle exists but a TLS context cannot be built from it
   */
  @Nullable
  private static ClientHttpRequestFactory buildTrustedRequestFactory(SslBundles sslBundles) {
    ClientHttpRequestFactory factory =
        KeycloakTrustSupport.trustedRequestFactory(sslBundles, KEYCLOAK_TRUST_BUNDLE);
    if (factory == null) {
      log.debug(
          "No '{}' SSL bundle configured; using the default Keycloak admin client",
          KEYCLOAK_TRUST_BUNDLE);
    }
    return factory;
  }

  /**
   * Creates a {@link RestClient} bound to the configured admin base URL, using the
   * truststore-pinned request factory when one was resolved at construction. Callers guarantee the
   * admin URL is non-null (see {@link #fetchUsers(Collection, Set)}), so building the lightweight
   * client per call is safe — the expensive TLS context lives in {@link #trustedRequestFactory} and
   * is reused.
   *
   * @return a ready-to-use {@link RestClient} for the Keycloak Admin API
   */
  private RestClient adminClient() {
    RestClient.Builder builder = RestClient.builder().baseUrl(properties.getAdminUrl());
    if (trustedRequestFactory != null) {
      builder.requestFactory(trustedRequestFactory);
    }
    return builder.build();
  }

  /**
   * Fetches the entire realm user catalog plus the app-relevant role memberships, joining an
   * incremental Discord federated-identity back-fill.
   *
   * <p>Short-circuits to an empty list when sync is disabled or the admin URL is unconfigured — a
   * missing setting must NOT trigger an exception that would leak the configuration shape to the
   * sync task. Any unexpected error (network, auth, malformed payload) is logged and the method
   * returns an empty list; the scheduler then treats the run as "skip" rather than marking every
   * local user as missing.
   *
   * <p>Scaling shape (5000-account hardening): roles are resolved <em>once per role</em> via {@link
   * #fetchRoleMemberships(Collection, String)} rather than once per user, and the Discord link is
   * read only for users NOT in {@code knownDiscordLinkedIds}. On a steady-state run that means a
   * handful of role-listing calls plus a Discord read only for accounts still missing a local link
   * (new members, or ones that never linked) — the linked majority is skipped entirely.
   *
   * @param appRoleNames the realm role names the app maps locally (from the local role catalog);
   *     these are matched case-insensitively against the realm's actual role names and only the
   *     matches are indexed against Keycloak, so default/technical realm roles never trigger a
   *     wasteful full-membership page. A role that exists locally but not in Keycloak is absent
   *     from the realm listing and simply contributes no memberships. Never {@code null}.
   * @param knownDiscordLinkedIds ids of users who already carry a local Discord link and therefore
   *     do NOT need their federated identity re-read this run; every other roster user (including
   *     brand-new ones absent locally) gets the read. Never {@code null}.
   * @return list of Keycloak users with their resolved realm role names and (for the non-skipped
   *     subset) Discord link, or empty on any failure
   */
  public List<KeycloakUserDto> fetchUsers(
      Collection<String> appRoleNames, Set<UUID> knownDiscordLinkedIds) {
    if (!properties.isEnabled() || properties.getAdminUrl() == null) {
      log.debug("Keycloak sync disabled or admin URL missing");
      return Collections.emptyList();
    }

    try {
      String token = getAccessToken();
      List<KeycloakUserDto> roster = fetchAllUsers(token);
      Map<UUID, Set<String>> rolesByUser = fetchRoleMemberships(appRoleNames, token);

      List<KeycloakUserDto> result = new ArrayList<>(roster.size());
      for (KeycloakUserDto u : roster) {
        Set<String> roles = rolesByUser.getOrDefault(u.id(), Collections.emptySet());
        // Incremental Discord back-fill: skip the per-user federated-identity read for accounts
        // that already carry a local link. syncUser treats the resulting null as "leave the
        // existing link alone", so skipping is safe and cannot wipe a real link. A relink to a
        // different Discord account is still caught at the linker's next login (the JWT claim
        // path), which is why the daily sync only needs to cover accounts with no local link yet.
        String discordUserId =
            (u.id() != null && !knownDiscordLinkedIds.contains(u.id()))
                ? fetchDiscordFederatedId(u.id(), token)
                : null;
        result.add(
            new KeycloakUserDto(
                u.id(), u.username(), u.email(), u.enabled(), roles, discordUserId));
      }
      return result;

    } catch (Exception e) {
      logFetchFailure(e);
      // REQ-OBS-011: the swallow returns an empty roster and the sync still records success, so
      // without this counter a Keycloak Admin-API outage is indistinguishable from a legitimately
      // empty roster (departed users would keep their local roles). KeycloakSyncFetchFailing
      // alerts.
      meterRegistry.counter(MetricNames.KEYCLOAK_SYNC_FETCH_FAILURES).increment();
      return Collections.emptyList();
    }
  }

  /**
   * Logs a swallowed {@link #fetchUsers(Collection, Set)} failure, upgrading the generic message to
   * an operator-actionable hint when the Admin API answered {@code 401}/{@code 403}. A persistent
   * authorization rejection is a permission misconfiguration, not a transient outage: since the
   * role-indexed refactor (ADR-0085 / REQ-SEC-043) the sync lists realm roles ({@code GET
   * /admin/realms/{realm}/roles}) and reads their members ({@code GET /roles/{name}/users}), which
   * require the {@code view-realm} realm-management role on top of the {@code view-users} the
   * roster listing already needs — so a service account provisioned with only {@code view-users}
   * fails every run once role-indexing shipped. Naming the missing grant makes the daily failure
   * diagnosable from a single log line; any other failure keeps the original generic message.
   *
   * @param e the exception caught while fetching users; never {@code null}.
   */
  private void logFetchFailure(@NotNull Exception e) {
    if (e instanceof RestClientResponseException rcre
        && (rcre.getStatusCode().value() == 401 || rcre.getStatusCode().value() == 403)) {
      log.error(
          "Keycloak Admin API rejected the user sync with {}: the '{}' service account is likely "
              + "missing the 'view-realm' realm-management role. Since the role-indexed sync lists "
              + "realm roles and reads their members, view-realm is needed beyond view-users. "
              + "Grant it in Keycloak; the run is skipped until then.",
          rcre.getStatusCode(),
          properties.getClientId(),
          e);
    } else {
      log.error("Failed to fetch users from Keycloak", e);
    }
  }

  /**
   * Pages through the Keycloak Admin API {@code GET /users} endpoint and accumulates the full user
   * list. The endpoint caps each response at a server-side maximum (~100 by default), so a single
   * unpaged call would return only the first page — and {@link
   * de.greluc.krt.profit.basetool.backend.task.UserSyncTask} would then wrongly flag every user
   * beyond that page as missing (a silent soft-delete past the cap). This loops {@code
   * first}/{@code max} (page size from {@link KeycloakSyncProperties#getPageSize()}) until a short
   * or empty page signals the end.
   *
   * @param token a valid admin access token.
   * @return every Keycloak user across all pages; never {@code null}, possibly empty.
   */
  private List<KeycloakUserDto> fetchAllUsers(String token) {
    int pageSize = properties.getPageSize();
    List<KeycloakUserDto> all = new ArrayList<>();
    int first = 0;
    while (true) {
      final int currentFirst = first;
      List<KeycloakUserDto> page =
          adminClient()
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/admin/realms/{realm}/users")
                          .queryParam("first", currentFirst)
                          .queryParam("max", pageSize)
                          .build(properties.getRealm()))
              .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + token)
              .retrieve()
              .body(new ParameterizedTypeReference<List<KeycloakUserDto>>() {});

      if (page == null || page.isEmpty()) {
        break;
      }
      all.addAll(page);
      if (page.size() < pageSize) {
        break;
      }
      first += pageSize;
    }
    return all;
  }

  /**
   * Builds the {@code userId -> realm role names} index by listing the members of each app-relevant
   * realm role once, rather than reading every user's role mappings individually.
   *
   * <p>This is the role-indexed inverse of the old per-user {@code GET
   * /users/{id}/role-mappings/realm} fan-out (which cost one Admin-API call per user). Both views
   * report <em>directly-assigned</em> realm roles — only far cheaper: the call count is bounded by
   * the number of mappable roles (a handful) times their page count, not by the user count.
   *
   * <p><strong>Directly-assigned is not the whole truth, and REQ-SEC-053 made the gap
   * load-bearing.</strong> The realm's default-role composite ({@code default-roles-iri}) grants
   * {@code KRT Member} to every account created in it, and a role held only through a composite
   * appears in <em>neither</em> view above. Until ADR-0159 that cost nothing visible: such an
   * account came back with an empty set, was mapped onto the authority-less {@code Guest} fallback,
   * and healed at its owner's next web login. With a role-less account now refused outright, the
   * same run would lock every composite-only member out overnight. {@link
   * #fetchDefaultRoleGrants(String)} therefore resolves what the default role grants and folds it
   * into every one of its members.
   *
   * <p>The mappable role names come from the local catalog ({@link
   * UserService#getMappableRoleNames()}); they are matched <em>case-insensitively</em> against the
   * realm's actual role names (resolved once via {@link #fetchRealmRoleNames(String)}) and only the
   * matches are queried, using Keycloak's own casing. This mirrors the interactive JWT path (which
   * maps via {@code findByNameIgnoreCase}) and removes the scheduled-vs-interactive casing
   * asymmetry: Keycloak's {@code /roles/{name}/users} lookup is case-sensitive, so passing a
   * differently-cased local name straight through would silently miss the role. Ubiquitous
   * default/technical realm roles ({@code default-roles-*}, {@code offline_access}, {@code
   * uma_authorization}) are not in the app's mappable set and are therefore never queried (no
   * wasteful full-membership page walk). The mappable set may contain local-only roles, because it
   * is simply the local catalog's names; those find no realm counterpart, which is expected and
   * must not be reported as an anomaly (see the intersection logging below).
   *
   * @param appRoleNames the realm role names to index; never {@code null}.
   * @param token a valid admin access token.
   * @return a mutable map of user id to the subset of {@code appRoleNames} each user holds (stored
   *     under the local catalog's casing); users with none simply do not appear and the caller
   *     defaults them to the empty set, which is now a refusal rather than a fallback role
   *     (REQ-SEC-053).
   * @throws IllegalStateException when the realm matches none of the app's roles — the run must be
   *     skipped rather than write a role-strip for every account (see below).
   */
  private Map<UUID, Set<String>> fetchRoleMemberships(
      Collection<String> appRoleNames, String token) {
    Map<UUID, Set<String>> byUser = new HashMap<>();
    Map<String, String> canonicalByLower = new HashMap<>();
    for (String appRoleName : appRoleNames) {
      if (appRoleName != null && !appRoleName.isBlank()) {
        canonicalByLower.put(appRoleName.toLowerCase(Locale.ROOT), appRoleName);
      }
    }
    if (canonicalByLower.isEmpty()) {
      return byUser;
    }
    List<String> realmRoleNames = fetchRealmRoleNames(token);
    int matched = 0;
    for (String keycloakRoleName : realmRoleNames) {
      String canonical = canonicalByLower.get(keycloakRoleName.toLowerCase(Locale.ROOT));
      if (canonical != null) {
        matched++;
        accumulateRoleMembers(keycloakRoleName, canonical, token, byUser);
      }
    }

    // REQ-SEC-053: everything the realm's default-role composite grants, credited to every one of
    // its members. Without this a member who holds KRT Member only through `default-roles-iri`
    // comes back with no roles at all.
    accumulateRoleMembers(
        defaultRoleName(), fetchDefaultRoleGrants(token, canonicalByLower), token, byUser);
    // Which of the app's roles the realm actually still knows is the input every downstream role
    // decision rests on, and it used to be invisible: a renamed realm role simply stops matching
    // here, the run still "succeeds", and every holder is quietly left with no role at all.
    // Counts only (role names are structural, but the numbers are what a dashboard needs).
    log.info(
        "Keycloak role index: {} of {} mappable app roles matched a realm role ({} realm roles"
            + " listed), {} users carry at least one.",
        matched,
        canonicalByLower.size(),
        realmRoleNames.size(),
        byUser.size());
    // Not a per-role "missing from the realm" warning: the local catalog may contain local-only
    // roles, so an unmatched app role is NOT by itself an anomaly and warning per role would fire
    // on every single run. The degenerate case — the realm matching NONE of the app's roles — is
    // unambiguous, and since REQ-SEC-053 it is no longer merely worth warning about: writing that
    // run would strip every account of every role and refuse the entire organisation with NO_ROLE
    // at once. It is a realm-side rename or a broken query, never a legitimate state, so the run
    // is ABORTED here and skipped by fetchUsers' top-level catch.
    //
    // Deliberately narrow. A single account resolving to no role IS legitimate — a leaver whose
    // realm roles were stripped — and is written through, so removing someone's roles in Keycloak
    // still removes their access. Only the whole-index failure is treated as "the realm did not
    // answer the question", which is what it is.
    if (matched == 0) {
      throw new IllegalStateException(
          "Keycloak role index: none of the "
              + canonicalByLower.size()
              + " mappable app roles matched a realm role; skipping the run rather than stripping"
              + " every account (REQ-SEC-053).");
    }
    return byUser;
  }

  /**
   * Lists the realm's actual role names via the paged {@code GET /admin/realms/{realm}/roles}
   * endpoint, so {@link #fetchRoleMemberships(Collection, String)} can match the local catalog
   * against Keycloak's own casing rather than assuming the two agree. The endpoint caps each
   * response at a server-side maximum, so this loops {@code first}/{@code max} (page size from
   * {@link KeycloakSyncProperties#getPageSize()}) until a short or empty page signals the end —
   * mirroring {@link #fetchAllUsers(String)}.
   *
   * <p>Not best-effort: any failure propagates to {@link #fetchUsers(Collection, Set)}'s top-level
   * catch, which returns an empty roster so the run is <em>skipped</em> (never a wipe, never a
   * degraded write) rather than proceeding with an unknown role set.
   *
   * @param token a valid admin access token.
   * @return every realm role name across all pages; never {@code null}, possibly empty.
   */
  private List<String> fetchRealmRoleNames(String token) {
    int pageSize = properties.getPageSize();
    List<String> names = new ArrayList<>();
    int first = 0;
    while (true) {
      final int currentFirst = first;
      List<Map<String, Object>> page =
          adminClient()
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/admin/realms/{realm}/roles")
                          .queryParam("first", currentFirst)
                          .queryParam("max", pageSize)
                          .build(properties.getRealm()))
              .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + token)
              .retrieve()
              .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});

      if (page == null || page.isEmpty()) {
        break;
      }
      for (Map<String, Object> role : page) {
        if (role.get("name") instanceof String name && !name.isBlank()) {
          names.add(name);
        }
      }
      if (page.size() < pageSize) {
        break;
      }
      first += pageSize;
    }
    return names;
  }

  /**
   * The realm's default-role composite, whose members are every account created in the realm.
   *
   * <p>Keycloak names it {@code default-roles-<realm>} and exposes it on the realm representation
   * as {@code defaultRole.name}. Derived rather than read, because reading it would cost a call to
   * {@code GET /admin/realms/{realm}} on every run for a value that is a documented naming
   * convention; a realm whose default role was renamed simply contributes no members here, which
   * degrades to the pre-ADR-0159 behaviour for composite-only members rather than to a wrong grant.
   *
   * @return the default role's name for the configured realm; never {@code null}.
   */
  private String defaultRoleName() {
    return "default-roles-" + properties.getRealm().toLowerCase(Locale.ROOT);
  }

  /**
   * Reads which of the app's mappable roles the realm's default-role composite grants.
   *
   * <p>{@code GET /roles/{defaultRole}/composites/realm} lists the realm roles the composite
   * confers. Only the ones the app maps are returned, under the local catalog's casing, so the
   * caller can credit them exactly like a directly-assigned role.
   *
   * <p>Best-effort by design, and it is the one call here that is: a realm without a default-role
   * composite answers {@code 404}, which is a legitimate configuration rather than a failure, and
   * neither test realm modelled the composite before WP-K1. Any other failure propagates, so a
   * broken Admin API still skips the run instead of writing a set that silently lacks every
   * composite-only grant.
   *
   * @param token a valid admin access token.
   * @param canonicalByLower the app's mappable role names, keyed by their lower-cased form.
   * @return the granted app role names under the local catalog's casing; never {@code null}.
   */
  private Set<String> fetchDefaultRoleGrants(String token, Map<String, String> canonicalByLower) {
    List<Map<String, Object>> composites;
    try {
      composites =
          adminClient()
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/admin/realms/{realm}/roles/{roleName}/composites/realm")
                          .build(properties.getRealm(), defaultRoleName()))
              .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + token)
              .retrieve()
              .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    } catch (HttpClientErrorException.NotFound notFound) {
      log.debug("Realm has no default-role composite '{}'; nothing to fold in.", defaultRoleName());
      return Set.of();
    }
    if (composites == null || composites.isEmpty()) {
      return Set.of();
    }
    Set<String> granted = new HashSet<>();
    for (Map<String, Object> composite : composites) {
      if (composite.get("name") instanceof String name && !name.isBlank()) {
        String canonical = canonicalByLower.get(name.toLowerCase(Locale.ROOT));
        if (canonical != null) {
          granted.add(canonical);
        }
      }
    }
    if (!granted.isEmpty()) {
      // Counts and role names only — both are structural, neither is PII. Worth INFO: this is the
      // grant that is invisible in every per-user and per-role view, so a reader diagnosing "why
      // does this member have KRT Member" has nothing else to go on.
      log.info(
          "Keycloak role index: the default-role composite '{}' grants {} mappable app role(s): {}",
          defaultRoleName(),
          granted.size(),
          granted);
    }
    return granted;
  }

  /**
   * Pages through {@code GET /roles/{queryRoleName}/users} and records {@code storedRoleName}
   * against every member id in {@code byUser}. The endpoint caps each response at a server-side
   * maximum, so this loops {@code first}/{@code max} (page size from {@link
   * KeycloakSyncProperties#getPageSize()}) until a short or empty page signals the end — mirroring
   * {@link #fetchAllUsers(String)}.
   *
   * <p><strong>Fault isolation (issue #1202 audit).</strong> Only a {@code 404} — the role vanished
   * between the realm-role listing and this member query (a benign TOCTOU) — is swallowed: the role
   * contributes no members and the run continues. Every <em>other</em> failure (5xx, 401/403,
   * timeout, connection reset, malformed body) is deliberately <em>not</em> caught here; it
   * propagates to {@link #fetchUsers(Collection, Set)}'s top-level catch, which returns an empty
   * roster so the whole run is <em>skipped</em>. This is a fail-safe: the earlier
   * swallow-every-exception behaviour turned a transient single-role failure into a silent
   * role-strip of every holder — mapping a brand-new admin onto the {@code Guest} fallback of the
   * time and creating it {@code PENDING} instead of {@code ACTIVE}, and mass-downgrading existing
   * admins — because the degraded role set was then persisted as a normal successful run.
   *
   * @param queryRoleName the realm role name as Keycloak spells it (used in the request path).
   * @param storedRoleName the local catalog's canonical name to record for each member.
   * @param token a valid admin access token.
   * @param byUser the accumulator to populate (user id &#8594; role names).
   */
  private void accumulateRoleMembers(
      String queryRoleName, String storedRoleName, String token, Map<UUID, Set<String>> byUser) {
    accumulateRoleMembers(queryRoleName, java.util.List.of(storedRoleName), token, byUser);
  }

  /**
   * Same, crediting <b>several</b> stored role names from one membership walk.
   *
   * <p>The default-role composite is why this overload exists. Its membership is, by definition,
   * every account in the realm, and calling the single-name form once per granted role re-walked
   * that whole list each time - the largest page walk the sync makes, repeated. One walk credits
   * them all.
   *
   * @param queryRoleName the realm role to read members of, in Keycloak's own casing
   * @param storedRoleNames the local catalogue names to credit each member with
   * @param token a valid admin access token
   * @param byUser the accumulator, keyed by Keycloak user id
   */
  private void accumulateRoleMembers(
      String queryRoleName,
      java.util.Collection<String> storedRoleNames,
      String token,
      Map<UUID, Set<String>> byUser) {
    if (storedRoleNames.isEmpty()) {
      return;
    }
    int pageSize = properties.getPageSize();
    int first = 0;
    while (true) {
      final int currentFirst = first;
      List<Map<String, Object>> page;
      try {
        page =
            adminClient()
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/admin/realms/{realm}/roles/{roleName}/users")
                            .queryParam("first", currentFirst)
                            .queryParam("max", pageSize)
                            .build(properties.getRealm(), queryRoleName))
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + token)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
      } catch (HttpClientErrorException.NotFound notFound) {
        // Benign TOCTOU: the role disappeared after the realm-role listing named it. No members to
        // contribute — skip this one role WITHOUT aborting the run (log the role name only, no
        // PII).
        log.debug("Realm role '{}' not found while reading members; skipping.", queryRoleName);
        return;
      }
      // Any OTHER failure is intentionally uncaught: it propagates to fetchUsers()'s top-level
      // catch
      // so the run is skipped rather than persisting a degraded, role-stripped set (see Javadoc).
      if (page == null || page.isEmpty()) {
        break;
      }
      for (Map<String, Object> member : page) {
        if (member.get("id") instanceof String idText) {
          try {
            byUser
                .computeIfAbsent(UUID.fromString(idText), k -> new HashSet<>())
                .addAll(storedRoleNames);
          } catch (IllegalArgumentException ignored) {
            // A non-UUID member id is a Keycloak configuration deviation; skip it rather than
            // aborting the whole role page (matches the fail-closed id handling elsewhere).
          }
        }
      }
      if (page.size() < pageSize) {
        break;
      }
      first += pageSize;
    }
  }

  /**
   * Reads the user's {@code discord} federated-identity link from {@code GET
   * /users/{id}/federated-identity} and returns its Discord snowflake, or {@code null} when the
   * user has no Discord link. The federated-identity link exists for <em>every</em> user who linked
   * Discord regardless of how (Discord registration, first-broker-login link, or account-console
   * linking) — which is why reading it here back-fills the local {@code discord_user_id} for
   * accounts that linked Discord <em>after</em> creation, the ones the import-time attribute never
   * covered (REQ-DATA-006). Best-effort: any failure (network, auth, malformed) is logged without
   * the id and yields {@code null}, so a transient Admin-API hiccup never throws — and {@link
   * UserService#syncUser(KeycloakUserDto)} treats a {@code null} as "leave the existing link
   * alone", never clearing it, so a hiccup cannot wipe a real link. The raw snowflake is never
   * logged.
   *
   * @param userId the Keycloak user id whose federated identities to read.
   * @param token a valid admin access token.
   * @return the linked Discord user id (snowflake), or {@code null} when absent or unreadable.
   */
  @Nullable
  private String fetchDiscordFederatedId(UUID userId, String token) {
    try {
      return fetchDiscordLink(userId, token).map(DiscordLink::userId).orElse(null);
    } catch (Exception e) {
      log.warn("Failed to fetch federated identities for user {}", userId, e);
      return null;
    }
  }

  /**
   * Reads a Keycloak user's {@code discord} federated-identity link (the snowflake plus the stored
   * Discord username), fetching its own admin token. This is the <em>write-path</em> counterpart of
   * the best-effort {@link #fetchDiscordFederatedId}: it is <strong>not</strong> best-effort — any
   * Admin-API failure propagates so the account-linking flow (REQ-SEC-026) surfaces a truthful
   * error rather than silently proceeding without a snowflake. It is the authoritative source of
   * the incoming Discord snowflake for a pending registration, so linking works even when the
   * optional {@code discord_user_id} claim mapper is absent (the id never reached {@code app_user})
   * — the federated link is always present for an account that logged in via Discord.
   *
   * <p>A {@code 404} (the Keycloak user itself no longer exists) is deliberately mapped to {@link
   * Optional#empty()} rather than propagated: it means there is no federated identity to read,
   * which lets the account-linking flow (REQ-SEC-026) fall back to the locally persisted {@code
   * discord_user_id} and recover a registration whose throwaway Keycloak user was already deleted
   * by an earlier partial failure. Every other Admin-API failure still propagates so a transient
   * hiccup surfaces a truthful error instead of silently discarding a link that does exist.
   *
   * @param keycloakUserId the Keycloak user id (== the app user id / JWT subject) to read
   * @return the {@code discord} link, or {@link Optional#empty()} when the user has no Discord link
   *     or no longer exists
   * @throws ExternalServiceException when the admin URL is unconfigured
   */
  public Optional<DiscordLink> readDiscordLink(@NotNull UUID keycloakUserId) {
    requireAdminUrl();
    try {
      return fetchDiscordLink(keycloakUserId, getAccessToken());
    } catch (HttpClientErrorException.NotFound userGone) {
      // The Keycloak user is gone (e.g. an earlier partial link already deleted the throwaway
      // registration user). No federated identity to read — report "no link" so the caller can fall
      // back to the local discord_user_id and recover. Log the id only (not PII).
      log.debug(
          "Keycloak user {} not found on discord-link read; treating as no link", keycloakUserId);
      return Optional.empty();
    }
  }

  /**
   * Attaches the {@code discord} federated identity to a Keycloak user via {@code POST
   * /users/{id}/federated-identity/discord}, requiring the {@code manage-users} realm-management
   * role. Idempotent: a {@code 409} is treated as success <em>only</em> when the user is already
   * linked to the <em>same</em> snowflake (a retry of this very link); a {@code 409} for a
   * <em>different</em> Discord account is a genuine conflict and is raised. The raw snowflake is
   * never logged.
   *
   * @param keycloakUserId the target Keycloak user id (the surviving existing account)
   * @param discordSnowflake the Discord user id (snowflake) to link; never {@code null}/blank
   * @param discordUsername the Discord username stored on the link; may be {@code null}/blank
   * @throws ExternalServiceException when the admin URL is unconfigured, or when the user is
   *     already linked to a different Discord account
   */
  public void linkDiscordIdentity(
      @NotNull UUID keycloakUserId,
      @NotNull String discordSnowflake,
      @Nullable String discordUsername) {
    requireAdminUrl();
    Map<String, String> body = new HashMap<>();
    body.put("identityProvider", DISCORD_IDP_ALIAS);
    body.put("userId", discordSnowflake);
    if (discordUsername != null && !discordUsername.isBlank()) {
      body.put("userName", discordUsername.trim());
    }
    String token = getAccessToken();
    try {
      adminClient()
          .post()
          .uri(
              "/admin/realms/{realm}/users/{id}/federated-identity/{provider}",
              properties.getRealm(),
              keycloakUserId,
              DISCORD_IDP_ALIAS)
          .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + token)
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException.Conflict conflict) {
      String existing =
          fetchDiscordLink(keycloakUserId, token).map(DiscordLink::userId).orElse(null);
      if (discordSnowflake.equals(existing)) {
        // Retry of this same link — the identity is already attached. Idempotent success.
        return;
      }
      throw new ExternalServiceException(
          "Keycloak user " + keycloakUserId + " is already linked to a different Discord account");
    }
  }

  /**
   * Live existence probe against {@code GET /admin/realms/{realm}/users/{id}} — the authoritative
   * answer to "does this account still exist in Keycloak?", as opposed to the locally cached {@code
   * app_user.in_keycloak} flag that a swallowed sync error can leave stale.
   *
   * <p>Read-only and deliberately <b>fail-closed</b>: only a clean {@code 404} reports absence.
   * Every other outcome — an unreachable Keycloak, an expired admin token, an unconfigured admin
   * URL, a {@code 5xx} — propagates, so a caller that gates an irreversible action on "the account
   * is gone" refuses instead of proceeding on a guess. {@link UserDeletionService#deleteUser(UUID)}
   * is that caller.
   *
   * @param keycloakUserId the Keycloak user id (== the app user id / JWT subject) to probe
   * @return {@code true} iff Keycloak positively confirms the user still exists
   * @throws ExternalServiceException when the admin URL is unconfigured
   * @throws org.springframework.web.client.RestClientException when Keycloak cannot be reached or
   *     answers with anything other than a success or a {@code 404}
   */
  public boolean userExists(@NotNull UUID keycloakUserId) {
    requireAdminUrl();
    try {
      adminClient()
          .get()
          .uri("/admin/realms/{realm}/users/{id}", properties.getRealm(), keycloakUserId)
          .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + getAccessToken())
          .retrieve()
          .toBodilessEntity();
      return true;
    } catch (HttpClientErrorException.NotFound absent) {
      // The only outcome that may be read as "gone". Log the id only (it is not PII).
      log.debug("Keycloak user {} confirmed absent", keycloakUserId);
      return false;
    }
  }

  /**
   * Reads a user's Keycloak username by id, or empty when Keycloak does not have that user.
   *
   * <p>Deliberately a <strong>users</strong>-scope call. The obvious way to identify a service
   * account is to ask the client that owns it ({@code GET /clients?clientId=…} then {@code
   * /clients/{id}/service-account-user}), and that is what this class did first — until production
   * answered {@code 403 Forbidden} on the clients endpoint: the backend's admin client is granted
   * user management, not client inspection. That 403 surfaced as an unexpected 500 on the member
   * deletion, which is a worse outcome than the problem it was solving.
   *
   * <p>The username is enough, <em>for an exactly-named configured client</em>. Measured against
   * Keycloak 26.7: creating an ordinary user called {@code service-account-foo} succeeds when no
   * client {@code foo} exists ({@code 201}), so the prefix alone proves nothing — but creating one
   * whose name collides with an existing service account is refused ({@code 409}), because
   * usernames are unique per realm and the real service account already holds it. So {@code
   * service-account-<configured clientId>} cannot be occupied by a hand-made account, which is the
   * property the caller needs.
   *
   * @param keycloakUserId the Keycloak user id
   * @return the username Keycloak reports, or empty when the user is absent
   * @throws ExternalServiceException when the admin URL is unconfigured
   */
  @NotNull
  public Optional<String> usernameOf(@NotNull UUID keycloakUserId) {
    requireAdminUrl();
    try {
      Map<String, Object> user =
          adminClient()
              .get()
              .uri("/admin/realms/{realm}/users/{id}", properties.getRealm(), keycloakUserId)
              .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + getAccessToken())
              .retrieve()
              .body(new ParameterizedTypeReference<Map<String, Object>>() {});
      if (user == null || user.get("username") == null) {
        return Optional.empty();
      }
      return Optional.of(user.get("username").toString());
    } catch (HttpClientErrorException.NotFound absent) {
      return Optional.empty();
    }
  }

  /**
   * Hard-deletes a Keycloak user via {@code DELETE /users/{id}}, requiring the {@code manage-users}
   * realm-management role. Idempotent: a {@code 404} (the user is already gone) is treated as
   * success. Used by the account-linking flow to dispose of the throwaway Discord-registered user
   * once its identity has been moved onto the surviving existing account (REQ-SEC-026).
   *
   * @param keycloakUserId the Keycloak user id to delete
   * @throws ExternalServiceException when the admin URL is unconfigured
   */
  public void deleteUser(@NotNull UUID keycloakUserId) {
    requireAdminUrl();
    try {
      adminClient()
          .delete()
          .uri("/admin/realms/{realm}/users/{id}", properties.getRealm(), keycloakUserId)
          .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + getAccessToken())
          .retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException.NotFound notFound) {
      // Already absent — idempotent success. Log the id only (it is not PII).
      log.debug("Keycloak user {} already absent on delete", keycloakUserId);
    }
  }

  /**
   * Reads and parses a user's {@code discord} federated-identity entry from {@code GET
   * /users/{id}/federated-identity}, shared by the best-effort sync read and the write-path {@link
   * #readDiscordLink}. Propagates Admin-API failures to the caller (the sync read wraps this in its
   * own swallow).
   *
   * @param userId the Keycloak user id whose federated identities to read
   * @param token a valid admin access token
   * @return the {@code discord} link, or {@link Optional#empty()} when the user has no Discord link
   */
  private Optional<DiscordLink> fetchDiscordLink(UUID userId, String token) {
    List<Map<String, Object>> identities =
        adminClient()
            .get()
            .uri(
                "/admin/realms/{realm}/users/{id}/federated-identity",
                properties.getRealm(),
                userId)
            .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + token)
            .retrieve()
            .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    if (identities == null) {
      return Optional.empty();
    }
    return identities.stream()
        .filter(i -> DISCORD_IDP_ALIAS.equals(i.get("identityProvider")))
        .map(KeycloakService::toDiscordLink)
        .filter(Objects::nonNull)
        .findFirst();
  }

  /**
   * Maps a raw federated-identity JSON map to a {@link DiscordLink}, or {@code null} when it
   * carries no usable snowflake ({@code userId} absent/blank).
   *
   * @param identity one entry from the federated-identity list
   * @return the parsed link, or {@code null} when the snowflake is missing/blank
   */
  @Nullable
  private static DiscordLink toDiscordLink(Map<String, Object> identity) {
    if (!(identity.get("userId") instanceof String rawId) || rawId.trim().isEmpty()) {
      return null;
    }
    String userName =
        (identity.get("userName") instanceof String rawName && !rawName.trim().isEmpty())
            ? rawName.trim()
            : null;
    return new DiscordLink(rawId.trim(), userName);
  }

  /**
   * Guards a write against an unconfigured admin URL, failing with a clear message rather than a
   * downstream NPE when {@link #adminClient()} would build against a {@code null} base URL.
   *
   * @throws ExternalServiceException when the Keycloak admin URL is not configured
   */
  private void requireAdminUrl() {
    if (properties.getAdminUrl() == null) {
      throw new ExternalServiceException("Keycloak admin URL is not configured");
    }
  }

  /**
   * A Keycloak {@code discord} federated-identity link: the Discord snowflake and the stored
   * Discord username (which may be absent). Never logged.
   *
   * @param userId the Discord user id (snowflake)
   * @param userName the stored Discord username, or {@code null} when the link carries none
   */
  public record DiscordLink(@NotNull String userId, @Nullable String userName) {}

  private String getAccessToken() {
    MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
    formData.add("grant_type", "client_credentials");
    formData.add("client_id", properties.getClientId());
    formData.add("client_secret", properties.getClientSecret());

    try {
      Map response =
          adminClient()
              .post()
              .uri("/realms/{realm}/protocol/openid-connect/token", properties.getRealm())
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(formData)
              .retrieve()
              .body(Map.class);

      if (response != null && response.containsKey("access_token")) {
        return (String) response.get("access_token");
      }

      throw new de.greluc.krt.profit.basetool.backend.exception.ExternalServiceException(
          "Could not retrieve access token from Keycloak. Response: " + response);
    } catch (RestClientResponseException e) {
      throw new de.greluc.krt.profit.basetool.backend.exception.ExternalServiceException(
          "Keycloak returned error: " + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e);
    }
  }
}
