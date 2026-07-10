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
import de.greluc.krt.profit.basetool.backend.model.dto.KeycloakUserDto;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
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
 * Read-only client for the Keycloak Admin REST API used by the scheduled user sync.
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
   */
  public KeycloakService(KeycloakSyncProperties properties, SslBundles sslBundles) {
    this.properties = properties;
    this.trustedRequestFactory = buildTrustedRequestFactory(sslBundles);
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
   *     wasteful full-membership page. A role that exists locally but not in Keycloak (e.g. the
   *     local-only {@code Guest} fallback) is absent from the realm listing and simply contributes
   *     no memberships. Never {@code null}.
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
      log.error("Failed to fetch users from Keycloak", e);
      return Collections.emptyList();
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
   * report <em>directly-assigned</em> realm roles, so the reconstructed sets are equivalent to what
   * the per-user path returned — only far cheaper: the call count is bounded by the number of
   * mappable roles (a handful) times their page count, not by the user count.
   *
   * <p>The mappable role names come from the local catalog ({@link
   * UserService#getMappableRoleNames()}); they are matched <em>case-insensitively</em> against the
   * realm's actual role names (resolved once via {@link #fetchRealmRoleNames(String)}) and only the
   * matches are queried, using Keycloak's own casing. This mirrors the interactive JWT path (which
   * maps via {@code findByNameIgnoreCase}) and removes the scheduled-vs-interactive casing
   * asymmetry: Keycloak's {@code /roles/{name}/users} lookup is case-sensitive, so passing a
   * differently-cased local name straight through would silently miss the role. Local-only roles
   * such as the {@code Guest} fallback, and ubiquitous default/technical realm roles ({@code
   * default-roles-*}, {@code offline_access}, {@code uma_authorization}), are not in the app's
   * mappable set and are therefore never queried (no wasteful full-membership page walk).
   *
   * @param appRoleNames the realm role names to index; never {@code null}.
   * @param token a valid admin access token.
   * @return a mutable map of user id to the subset of {@code appRoleNames} each user holds (stored
   *     under the local catalog's casing); users with none simply do not appear (the caller
   *     defaults them to the empty set, which {@link UserService#syncUser(KeycloakUserDto)} maps to
   *     the {@code Guest} fallback).
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
    for (String keycloakRoleName : fetchRealmRoleNames(token)) {
      String canonical = canonicalByLower.get(keycloakRoleName.toLowerCase(Locale.ROOT));
      if (canonical != null) {
        accumulateRoleMembers(keycloakRoleName, canonical, token, byUser);
      }
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
   * role-strip of every holder — mapping a brand-new admin to the {@code Guest} fallback and
   * creating it {@code PENDING} instead of {@code ACTIVE}, and mass-downgrading existing admins —
   * because the degraded role set was then persisted as a normal successful run.
   *
   * @param queryRoleName the realm role name as Keycloak spells it (used in the request path).
   * @param storedRoleName the local catalog's canonical name to record for each member.
   * @param token a valid admin access token.
   * @param byUser the accumulator to populate (user id &#8594; role names).
   */
  private void accumulateRoleMembers(
      String queryRoleName, String storedRoleName, String token, Map<UUID, Set<String>> byUser) {
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
                .add(storedRoleName);
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
        return null;
      }
      return identities.stream()
          .filter(i -> DISCORD_IDP_ALIAS.equals(i.get("identityProvider")))
          .map(i -> (String) i.get("userId"))
          .filter(Objects::nonNull)
          .map(String::trim)
          .filter(id -> !id.isEmpty())
          .findFirst()
          .orElse(null);
    } catch (Exception e) {
      log.warn("Failed to fetch federated identities for user {}", userId, e);
      return null;
    }
  }

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
