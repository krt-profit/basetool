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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Read-only client for the Keycloak Admin REST API used by the scheduled user sync.
 *
 * <p>Obtains an admin access token via the {@code client_credentials} grant against the realm's
 * {@code openid-connect/token} endpoint, then pages through {@code /admin/realms/{realm}/users} and
 * joins each user record with the user's realm role mappings. Failures are swallowed at the top
 * level: the scheduler treats an empty list as "skip this run" and never wipes local users based on
 * it (see {@link UserService#markMissingUsers}).
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
   * admin URL is non-null (see {@link #fetchUsers()}), so building the lightweight client per call
   * is safe — the expensive TLS context lives in {@link #trustedRequestFactory} and is reused.
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
   * Fetches the entire realm user catalog plus role mappings.
   *
   * <p>Short-circuits to an empty list when sync is disabled or the admin URL is unconfigured — a
   * missing setting must NOT trigger an exception that would leak the configuration shape to the
   * sync task. Any unexpected error (network, auth, malformed payload) is logged and the method
   * returns an empty list; the scheduler then treats the run as "skip" rather than marking every
   * local user as missing.
   *
   * @return list of Keycloak users with their resolved realm role names, or empty on any failure
   */
  public List<KeycloakUserDto> fetchUsers() {
    if (!properties.isEnabled() || properties.getAdminUrl() == null) {
      log.debug("Keycloak sync disabled or admin URL missing");
      return Collections.emptyList();
    }

    try {
      String token = getAccessToken();

      return fetchAllUsers(token).stream()
          .map(
              u -> {
                Set<String> roles = fetchUserRoles(u.id(), token);
                String discordUserId = fetchDiscordFederatedId(u.id(), token);
                return new KeycloakUserDto(
                    u.id(), u.username(), u.email(), u.enabled(), roles, discordUserId);
              })
          .toList();

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

  private Set<String> fetchUserRoles(UUID userId, String token) {
    try {
      List<Map<String, Object>> roles =
          adminClient()
              .get()
              .uri(
                  "/admin/realms/{realm}/users/{id}/role-mappings/realm",
                  properties.getRealm(),
                  userId)
              .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + token)
              .retrieve()
              .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});

      if (roles == null) {
        return Collections.emptySet();
      }
      return roles.stream()
          .map(r -> (String) r.get("name"))
          .filter(Objects::nonNull)
          .collect(Collectors.toSet());
    } catch (Exception e) {
      log.warn("Failed to fetch roles for user {}", userId, e);
      return Collections.emptySet();
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
