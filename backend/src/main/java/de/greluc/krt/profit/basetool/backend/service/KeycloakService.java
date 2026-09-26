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
 * Client for the Keycloak Admin REST API: the read-only scheduled user sync plus the Discord
 * account-linking writes {@link #linkDiscordIdentity}, {@link #unlinkDiscordIdentity} and {@link
 * #deleteUser} (REQ-SEC-026), which need the {@code manage-users} role.
 *
 * <p>Authenticates with {@code client_credentials}, pages all users and resolves role membership
 * per role rather than per user; the Discord link is read only for users without a local one. On
 * failure the sync returns an empty list, which the scheduler treats as "skip this run".
 */
@Service
@Slf4j
public class KeycloakService {

  /**
   * RFC 6750 {@code Bearer} prefix for the admin access token, kept in one place so the token is
   * never concatenated by hand where it could reach a log.
   */
  private static final String BEARER_PREFIX = "Bearer ";

  /**
   * Name of the Spring SSL bundle whose truststore pins the self-signed certificate the production
   * Keycloak presents on its internal {@code https://keycloak:18443} admin connector. Defined in
   * {@code application-prod.yml}; absent in dev/test, where the admin URL is plain HTTP.
   */
  private static final String KEYCLOAK_TRUST_BUNDLE = "keycloak-trust";

  /**
   * Alias of the Discord identity provider in the realm; must match the Keycloak configuration.
   * Used to pick the Discord entry from a user's federated identities (REQ-DATA-006).
   */
  private static final String DISCORD_IDP_ALIAS = "discord";

  private final KeycloakSyncProperties properties;

  /** Micrometer registry for the {@code basetool_keycloak_sync_fetch_failures_total} counter. */
  private final MeterRegistry meterRegistry;

  /**
   * The Keycloak Admin API client, built once from the observed builder so every call is metered
   * and traced (REQ-OBS-009). Uses the truststore-pinned factory when {@link
   * #KEYCLOAK_TRUST_BUNDLE} is configured, otherwise the JVM default trust.
   */
  private final RestClient adminClient;

  /**
   * Wires the sync properties and builds the Keycloak Admin API client with its TLS trust.
   *
   * @param properties the {@code app.keycloak.sync.*} configuration
   * @param restClientBuilder a fresh, observed, prototype-scoped builder from {@code
   *     RestClientConfig}
   * @param sslBundles the SSL bundles, consulted for {@link #KEYCLOAK_TRUST_BUNDLE}
   * @param meterRegistry registry for the {@code basetool_keycloak_sync_fetch_failures_total}
   *     counter
   */
  public KeycloakService(
      @NotNull KeycloakSyncProperties properties,
      RestClient.@NotNull Builder restClientBuilder,
      @NotNull SslBundles sslBundles,
      MeterRegistry meterRegistry) {
    this.properties = properties;
    this.meterRegistry = meterRegistry;
    if (properties.adminUrl() != null) {
      restClientBuilder.baseUrl(properties.adminUrl());
    }
    ClientHttpRequestFactory trustedRequestFactory = buildTrustedRequestFactory(sslBundles);
    if (trustedRequestFactory != null) {
      restClientBuilder.requestFactory(trustedRequestFactory);
    }
    this.adminClient = restClientBuilder.build();
  }

  /**
   * Resolves the truststore-pinned request factory via {@link
   * KeycloakTrustSupport#trustedRequestFactory}. Hostname verification stays on, so the pinned
   * certificate must carry {@code dns:keycloak} in its SAN.
   *
   * @param sslBundles the registered Spring SSL bundles
   * @return a pinned request factory, or {@code null} to use the default {@link RestClient}
   * @throws IllegalStateException if the bundle exists but no TLS context can be built from it
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
   * Fetches all realm users with their app-relevant realm roles and, for users not yet linked
   * locally, their Discord link.
   *
   * <p>Returns an empty list when sync is disabled, the admin URL is unset, or any error occurs;
   * the scheduler then skips the run instead of marking users missing.
   *
   * @param appRoleNames the locally mapped role names, matched case-insensitively against the
   *     realm; never {@code null}
   * @param knownDiscordLinkedIds users whose Discord link need not be re-read; never {@code null}
   * @return the users with their role names and Discord link where read, or empty on failure
   */
  @NotNull
  public List<KeycloakUserDto> fetchUsers(
      Collection<String> appRoleNames, Set<UUID> knownDiscordLinkedIds) {
    if (!properties.enabled() || properties.adminUrl() == null) {
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
      meterRegistry.counter(MetricNames.KEYCLOAK_SYNC_FETCH_FAILURES).increment();
      return Collections.emptyList();
    }
  }

  /**
   * Logs a swallowed {@link #fetchUsers(Collection, Set)} failure, naming the missing {@code
   * view-realm} / {@code view-users} grant when the Admin API answered {@code 401} or {@code 403}
   * (REQ-SEC-043).
   *
   * @param e the exception caught while fetching users; never {@code null}
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
          properties.clientId(),
          e);
    } else {
      log.error("Failed to fetch users from Keycloak", e);
    }
  }

  /**
   * Pages through {@code GET /users} with {@code first}/{@code max} until a short or empty page, so
   * users beyond the server's page cap are never mistaken for missing.
   *
   * @param token a valid admin access token
   * @return every Keycloak user; never {@code null}, possibly empty
   */
  @NotNull
  private List<KeycloakUserDto> fetchAllUsers(String token) {
    int pageSize = properties.pageSize();
    List<KeycloakUserDto> all = new ArrayList<>();
    int first = 0;
    while (true) {
      final int currentFirst = first;
      List<KeycloakUserDto> page =
          adminClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/admin/realms/{realm}/users")
                          .queryParam("first", currentFirst)
                          .queryParam("max", pageSize)
                          .build(properties.realm()))
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
   * realm role once, plus the roles granted through the default-role composite via {@link
   * #fetchDefaultRoleGrants(String)} (REQ-SEC-053).
   *
   * <p>Local role names are matched case-insensitively against {@link #fetchRealmRoleNames(String)}
   * and queried in Keycloak's casing; local-only roles simply find no members.
   *
   * @param appRoleNames the realm role names to index; never {@code null}
   * @param token a valid admin access token
   * @return a mutable map of user id to held role names in local casing; users with none are absent
   * @throws IllegalStateException when the realm matches none of the app's roles, so the run is
   *     skipped
   */
  @NotNull
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

    accumulateRoleMembers(
        defaultRoleName(), fetchDefaultRoleGrants(token, canonicalByLower), token, byUser);
    log.info(
        "Keycloak role index: {} of {} mappable app roles matched a realm role ({} realm roles"
            + " listed), {} users carry at least one.",
        matched,
        canonicalByLower.size(),
        realmRoleNames.size(),
        byUser.size());
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
   * Lists all realm role names via the paged {@code GET /admin/realms/{realm}/roles} endpoint. Not
   * best-effort: a failure propagates so the whole sync run is skipped.
   *
   * @param token a valid admin access token
   * @return every realm role name; never {@code null}, possibly empty
   */
  @NotNull
  private List<String> fetchRealmRoleNames(String token) {
    int pageSize = properties.pageSize();
    List<String> names = new ArrayList<>();
    int first = 0;
    while (true) {
      final int currentFirst = first;
      List<Map<String, Object>> page =
          adminClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/admin/realms/{realm}/roles")
                          .queryParam("first", currentFirst)
                          .queryParam("max", pageSize)
                          .build(properties.realm()))
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
   * Returns the name of the realm's default-role composite, derived by Keycloak's {@code
   * default-roles-<realm>} convention; a renamed default role simply contributes no members.
   *
   * @return the default role's name for the configured realm; never {@code null}
   */
  @NotNull
  private String defaultRoleName() {
    return "default-roles-" + properties.realm().toLowerCase(Locale.ROOT);
  }

  /**
   * Returns which of the app's mappable roles the realm's default-role composite grants, read from
   * {@code GET /roles/{defaultRole}/composites/realm}. A {@code 404} (no composite) yields an empty
   * set; any other failure propagates.
   *
   * @param token a valid admin access token
   * @param canonicalByLower the app's mappable role names, keyed by lower-cased form
   * @return the granted app role names in local casing; never {@code null}
   */
  @NotNull
  private Set<String> fetchDefaultRoleGrants(String token, Map<String, String> canonicalByLower) {
    List<Map<String, Object>> composites;
    try {
      composites =
          adminClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/admin/realms/{realm}/roles/{roleName}/composites/realm")
                          .build(properties.realm(), defaultRoleName()))
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
      log.info(
          "Keycloak role index: the default-role composite '{}' grants {} mappable app role(s): {}",
          defaultRoleName(),
          granted.size(),
          granted);
    }
    return granted;
  }

  /**
   * Pages through {@code GET /roles/{queryRoleName}/users} and records {@code storedRoleName} for
   * every member. A {@code 404} (role vanished) contributes nothing; every other failure propagates
   * so the run is skipped rather than persisting a degraded role set.
   *
   * @param queryRoleName the role name as Keycloak spells it
   * @param storedRoleName the local canonical name to record
   * @param token a valid admin access token
   * @param byUser the accumulator (user id &#8594; role names)
   */
  private void accumulateRoleMembers(
      String queryRoleName, String storedRoleName, String token, Map<UUID, Set<String>> byUser) {
    accumulateRoleMembers(queryRoleName, List.of(storedRoleName), token, byUser);
  }

  /**
   * Credits several stored role names from one walk of a role's members, used for the default-role
   * composite whose membership is every account.
   *
   * @param queryRoleName the realm role to read members of, in Keycloak's own casing
   * @param storedRoleNames the local catalogue names to credit each member with
   * @param token a valid admin access token
   * @param byUser the accumulator, keyed by Keycloak user id
   */
  private void accumulateRoleMembers(
      String queryRoleName,
      Collection<String> storedRoleNames,
      String token,
      Map<UUID, Set<String>> byUser) {
    if (storedRoleNames.isEmpty()) {
      return;
    }
    int pageSize = properties.pageSize();
    int first = 0;
    while (true) {
      final int currentFirst = first;
      List<Map<String, Object>> page;
      try {
        page =
            adminClient
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/admin/realms/{realm}/roles/{roleName}/users")
                            .queryParam("first", currentFirst)
                            .queryParam("max", pageSize)
                            .build(properties.realm(), queryRoleName))
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + token)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
      } catch (HttpClientErrorException.NotFound notFound) {
        log.debug("Realm role '{}' not found while reading members; skipping.", queryRoleName);
        return;
      }
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
   * Reads the user's Discord snowflake from their {@code discord} federated identity, back-filling
   * the local link (REQ-DATA-006). Best-effort: any failure is logged without the id and yields
   * {@code null}, which the sync treats as "keep the existing link".
   *
   * @param userId the Keycloak user id
   * @param token a valid admin access token
   * @return the linked Discord snowflake, or {@code null} when absent or unreadable
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
   * Reads a Keycloak user's {@code discord} federated identity (snowflake and username) for the
   * account-linking flow (REQ-SEC-026). Not best-effort: failures propagate, except a {@code 404}
   * for a missing user, which yields empty.
   *
   * @param keycloakUserId the Keycloak user id (equal to the app user id / JWT subject)
   * @return the {@code discord} link, or {@link Optional#empty()} when the user has no link or no
   *     longer exists
   * @throws ExternalServiceException when the admin URL is unconfigured
   */
  public Optional<DiscordLink> readDiscordLink(@NotNull UUID keycloakUserId) {
    requireAdminUrl();
    try {
      return fetchDiscordLink(keycloakUserId, getAccessToken());
    } catch (HttpClientErrorException.NotFound userGone) {
      log.debug(
          "Keycloak user {} not found on discord-link read; treating as no link", keycloakUserId);
      return Optional.empty();
    }
  }

  /**
   * Attaches the {@code discord} federated identity to a Keycloak user. Idempotent: a {@code 409}
   * is success only if the user is already linked to the same snowflake. The snowflake is never
   * logged.
   *
   * @param keycloakUserId the target Keycloak user id
   * @param discordSnowflake the Discord snowflake to link; never {@code null} or blank
   * @param discordUsername the Discord username stored on the link; may be {@code null} or blank
   * @throws ExternalServiceException when the admin URL is unconfigured or the user is linked to a
   *     different Discord account
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
      adminClient
          .post()
          .uri(
              "/admin/realms/{realm}/users/{id}/federated-identity/{provider}",
              properties.realm(),
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
        return;
      }
      throw new ExternalServiceException(
          "Keycloak user " + keycloakUserId + " is already linked to a different Discord account");
    }
  }

  /**
   * Detaches the {@code discord} federated identity from a Keycloak user. Must precede linking the
   * identity to another user, because Keycloak does not prevent two holders and then fails every
   * Discord login of that member. Idempotent: a {@code 404} is success.
   *
   * @param keycloakUserId the Keycloak user to detach the identity from
   * @throws ExternalServiceException when the admin URL is unconfigured
   */
  public void unlinkDiscordIdentity(@NotNull UUID keycloakUserId) {
    requireAdminUrl();
    String token = getAccessToken();
    try {
      adminClient
          .delete()
          .uri(
              "/admin/realms/{realm}/users/{id}/federated-identity/{provider}",
              properties.realm(),
              keycloakUserId,
              DISCORD_IDP_ALIAS)
          .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + token)
          .retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException.NotFound notFound) {
      log.debug(
          "Keycloak user {} has no discord link to detach; treating as success", keycloakUserId);
    }
  }

  /**
   * Live check whether a Keycloak user still exists. Fail-closed: only a {@code 404} reports
   * absence; every other failure propagates.
   *
   * @param keycloakUserId the Keycloak user id (equal to the app user id / JWT subject)
   * @return {@code true} iff Keycloak confirms the user exists
   * @throws ExternalServiceException when the admin URL is unconfigured
   * @throws org.springframework.web.client.RestClientException when Keycloak cannot be reached or
   *     answers other than success or {@code 404}
   */
  public boolean userExists(@NotNull UUID keycloakUserId) {
    requireAdminUrl();
    try {
      adminClient
          .get()
          .uri("/admin/realms/{realm}/users/{id}", properties.realm(), keycloakUserId)
          .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + getAccessToken())
          .retrieve()
          .toBodilessEntity();
      return true;
    } catch (HttpClientErrorException.NotFound absent) {
      log.debug("Keycloak user {} confirmed absent", keycloakUserId);
      return false;
    }
  }

  /**
   * Reads a user's Keycloak username by id through the users scope, which the admin client is
   * granted. Since usernames are realm-unique, {@code service-account-<clientId>} cannot be taken
   * by a hand-made account.
   *
   * @param keycloakUserId the Keycloak user id
   * @return the username, or empty when the user is absent
   * @throws ExternalServiceException when the admin URL is unconfigured
   */
  @NotNull
  public Optional<String> usernameOf(@NotNull UUID keycloakUserId) {
    requireAdminUrl();
    try {
      Map<String, Object> user =
          adminClient
              .get()
              .uri("/admin/realms/{realm}/users/{id}", properties.realm(), keycloakUserId)
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
   * Hard-deletes a Keycloak user, used to dispose of the throwaway Discord-registered user after
   * account linking (REQ-SEC-026). Idempotent: a {@code 404} is success.
   *
   * @param keycloakUserId the Keycloak user id to delete
   * @throws ExternalServiceException when the admin URL is unconfigured
   */
  public void deleteUser(@NotNull UUID keycloakUserId) {
    requireAdminUrl();
    try {
      adminClient
          .delete()
          .uri("/admin/realms/{realm}/users/{id}", properties.realm(), keycloakUserId)
          .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + getAccessToken())
          .retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException.NotFound notFound) {
      log.debug("Keycloak user {} already absent on delete", keycloakUserId);
    }
  }

  /**
   * Reads and parses a user's {@code discord} federated-identity entry; Admin API failures
   * propagate to the caller.
   *
   * @param userId the Keycloak user id
   * @param token a valid admin access token
   * @return the {@code discord} link, or {@link Optional#empty()} when the user has none
   */
  private Optional<DiscordLink> fetchDiscordLink(UUID userId, String token) {
    List<Map<String, Object>> identities =
        adminClient
            .get()
            .uri("/admin/realms/{realm}/users/{id}/federated-identity", properties.realm(), userId)
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
   * downstream NPE when {@link #adminClient} would resolve a relative path against no base URL at
   * all.
   *
   * @throws ExternalServiceException when the Keycloak admin URL is not configured
   */
  private void requireAdminUrl() {
    if (properties.adminUrl() == null) {
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
    formData.add("client_id", properties.clientId());
    formData.add("client_secret", properties.clientSecret());

    try {
      Map response =
          adminClient
              .post()
              .uri("/realms/{realm}/protocol/openid-connect/token", properties.realm())
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(formData)
              .retrieve()
              .body(Map.class);

      if (response != null && response.containsKey("access_token")) {
        return (String) response.get("access_token");
      }

      throw new ExternalServiceException(
          "Could not retrieve access token from Keycloak. Response: " + response);
    } catch (RestClientResponseException e) {
      throw new ExternalServiceException(
          "Keycloak returned error: " + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e);
    }
  }
}
