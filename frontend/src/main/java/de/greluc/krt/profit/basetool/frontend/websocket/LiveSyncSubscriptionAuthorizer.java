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

package de.greluc.krt.profit.basetool.frontend.websocket;

import de.greluc.krt.profit.basetool.frontend.logging.ActiveSquadronRelayFilter;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Authorizes a {@code /ws/sync} subscribe to a live-sync topic (REQ-FE-015, ADR-0094), replaying
 * the access token and org-unit pin captured at the handshake as explicit headers.
 *
 * <p>Resource-scoped classes probe {@link LiveSyncTopicClass#authProbePath()}: 2xx allows, 403 or
 * 404 denies, anything else is resolved by {@link #failOpen(LiveSyncTopic)} (open, except closed
 * for presence-enabled classes). Global classes either require a {@link
 * LiveSyncTopicClass#capabilityField} or are authorized by the socket's authentication alone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveSyncSubscriptionAuthorizer {

  /**
   * The outcome of a subscribe-authorization check. {@link #DENY} is a permission verdict and
   * terminal for the tab; {@link #DENY_INDETERMINATE} is a fail-closed refusal the client retries
   * once. Callers use {@link #denied()}.
   */
  public enum Decision {
    /** The subscribe is authorized (or failed open on an indeterminate outcome). */
    ALLOW,
    /**
     * The subscribe is refused by an explicit authorization denial — a backend 403/404, a withheld
     * capability flag, or a locally role-gated room whose required role the caller does not hold.
     */
    DENY,
    /**
     * Refusal because the outcome was indeterminate and the topic class is presence-enabled, so it
     * fails closed; indicates a backend or token availability problem, not a permission verdict.
     */
    DENY_INDETERMINATE;

    /**
     * Checks whether this verdict refuses the subscribe.
     *
     * @return {@code true} for {@link #DENY} and {@link #DENY_INDETERMINATE}, {@code false} for
     *     {@link #ALLOW}
     */
    public boolean denied() {
      return this != ALLOW;
    }
  }

  /**
   * Returns the verdict for an indeterminate authorization outcome: open for a non-presence class,
   * closed for a presence-enabled one ({@link LiveSyncTopicClass#MISSION}), whose snapshot would
   * disclose who is editing.
   *
   * @param topic the topic whose class decides the fail direction
   * @return {@link Decision#ALLOW} for a non-presence class, {@link Decision#DENY_INDETERMINATE}
   *     for a presence one
   */
  @NotNull
  static Decision failOpen(@NotNull LiveSyncTopic topic) {
    return topic.topicClass().presenceEnabled() ? Decision.DENY_INDETERMINATE : Decision.ALLOW;
  }

  /**
   * Hard bound on a single authorization probe. A probe that has not answered within this window is
   * abandoned and the subscribe fails open, so one slow/hung backend read cannot pin an
   * auth-executor thread indefinitely.
   */
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

  /** Response type for a capabilities probe — a flat map of boolean capability flags. */
  private static final ParameterizedTypeReference<Map<String, Object>> CAPABILITIES_TYPE =
      new ParameterizedTypeReference<>() {};

  private final WebClient liveSyncAuthWebClient;

  /**
   * Decides whether a subscribe to {@code topic} is authorized: a per-resource read for
   * resource-scoped topics, a capability read for global topics with a {@link
   * LiveSyncTopicClass#capabilityField()}, and socket authentication alone otherwise.
   *
   * @param topic the parsed topic being subscribed to
   * @param accessToken the access token captured at handshake, or {@code null} (then fails open)
   * @param activeOrgUnitId the org-unit pin captured at handshake, relayed as {@code
   *     X-Active-Org-Unit-Id}, or {@code null}
   * @return {@link Decision#ALLOW}, including every fail-open case; {@link Decision#DENY} on an
   *     explicit 403/404 or withheld capability; or {@link Decision#DENY_INDETERMINATE} when a
   *     presence-enabled class failed closed
   */
  @NotNull
  public Decision authorize(
      @NotNull LiveSyncTopic topic, @Nullable String accessToken, @Nullable UUID activeOrgUnitId) {
    return authorize(topic, accessToken, activeOrgUnitId, null);
  }

  /**
   * Decides whether a subscribe to {@code topic} is authorized, additionally checking the captured
   * authorities for the locally role-gated {@code bank} and {@code orgunit-bank} rooms.
   *
   * @param topic the parsed topic being subscribed to
   * @param accessToken the access token captured at handshake, or {@code null}
   * @param activeOrgUnitId the org-unit pin captured at handshake, or {@code null}
   * @param authorities the authorities captured at handshake, or {@code null} (then a role-gated
   *     room fails open)
   * @return {@link Decision#ALLOW}, including every fail-open case; {@link Decision#DENY} on an
   *     explicit backend refusal, withheld capability or missing role; or {@link
   *     Decision#DENY_INDETERMINATE} when a presence-enabled class failed closed
   */
  @NotNull
  public Decision authorize(
      @NotNull LiveSyncTopic topic,
      @Nullable String accessToken,
      @Nullable UUID activeOrgUnitId,
      @Nullable Set<String> authorities) {
    Set<String> requiredAnyRole = topic.topicClass().requiredAnyRole();
    if (requiredAnyRole != null) {
      if (authorities == null) {
        return failOpen(topic);
      }
      for (String role : requiredAnyRole) {
        if (authorities.contains(role)) {
          return Decision.ALLOW;
        }
      }
      log.debug(
          "Live-sync subscribe denied for local-role topic {} (none of {} held)",
          topic.canonical(),
          requiredAnyRole);
      return Decision.DENY;
    }
    String probePath = topic.topicClass().authProbePath();
    if (probePath == null) {
      return Decision.ALLOW;
    }
    if (accessToken == null || accessToken.isBlank()) {
      return failOpen(topic);
    }
    if (topic.resourceId() != null) {
      String resource = topic.resourceId().toString();
      String fallbackTemplate = topic.topicClass().fallbackProbePath();
      String fallbackUri =
          fallbackTemplate == null ? null : fallbackTemplate.replace("{id}", resource);
      return probeResource(
          topic, probePath.replace("{id}", resource), fallbackUri, accessToken, activeOrgUnitId);
    }
    String capabilityField = topic.topicClass().capabilityField();
    if (capabilityField != null) {
      return probeCapability(topic, probePath, capabilityField, accessToken, activeOrgUnitId);
    }
    return Decision.ALLOW;
  }

  /**
   * Runs the primary authorization read and, only when it explicitly refuses (403/404), the
   * fallback read ({@link LiveSyncTopicClass#BANK_ACCOUNT}), which then decides.
   *
   * @param topic the topic, for logging
   * @param primaryUri the resolved primary resource read URI
   * @param fallbackUri the resolved fallback read URI, or {@code null} when the class has none
   * @param accessToken the captured bearer
   * @param activeOrgUnitId the captured pin, or {@code null}
   * @return the verdict
   */
  private Decision probeResource(
      LiveSyncTopic topic,
      String primaryUri,
      @Nullable String fallbackUri,
      String accessToken,
      UUID activeOrgUnitId) {
    Decision primary = probeOne(topic, primaryUri, accessToken, activeOrgUnitId);
    if (primary != Decision.DENY || fallbackUri == null) {
      return primary;
    }
    return probeOne(topic, fallbackUri, accessToken, activeOrgUnitId);
  }

  /**
   * Runs a single per-resource authorization read: a 2xx allows, an explicit 403/404 denies,
   * anything else (401/5xx/timeout/transport) fails open.
   *
   * @param topic the topic (for logging)
   * @param uri the resolved resource read URI
   * @param accessToken the captured bearer
   * @param activeOrgUnitId the captured pin, or {@code null}
   * @return the verdict
   */
  private Decision probeOne(
      LiveSyncTopic topic, String uri, String accessToken, UUID activeOrgUnitId) {
    try {
      liveSyncAuthWebClient
          .get()
          .uri(uri)
          .headers(headers -> applyAuth(headers, accessToken, activeOrgUnitId))
          .retrieve()
          .toBodilessEntity()
          .block(PROBE_TIMEOUT);
      return Decision.ALLOW;
    } catch (WebClientResponseException e) {
      int status = e.getStatusCode().value();
      if (status == 403 || status == 404) {
        log.debug(
            "Live-sync subscribe denied for topic {} (backend {})", topic.canonical(), status);
        return Decision.DENY;
      }
      log.debug(
          "Live-sync subscribe indeterminate on transient backend status {} for topic {}",
          status,
          topic.canonical());
      return failOpen(topic);
    } catch (RuntimeException e) {
      log.debug(
          "Live-sync subscribe authorization probe failed for topic {} (fail-open direction by"
              + " class)",
          topic.canonical(),
          e);
      return failOpen(topic);
    }
  }

  /**
   * Runs a capability read and requires {@code field} to be {@code true}; a withheld capability
   * denies, and a failed read fails open.
   *
   * @param topic the topic, for logging
   * @param path the capabilities endpoint
   * @param field the boolean capability field that must be {@code true}
   * @param accessToken the captured bearer
   * @param activeOrgUnitId the captured pin, or {@code null}
   * @return the verdict
   */
  private Decision probeCapability(
      LiveSyncTopic topic, String path, String field, String accessToken, UUID activeOrgUnitId) {
    try {
      Map<String, Object> capabilities =
          liveSyncAuthWebClient
              .get()
              .uri(path)
              .headers(headers -> applyAuth(headers, accessToken, activeOrgUnitId))
              .retrieve()
              .bodyToMono(CAPABILITIES_TYPE)
              .block(PROBE_TIMEOUT);
      boolean granted = capabilities != null && Boolean.TRUE.equals(capabilities.get(field));
      if (!granted) {
        log.debug(
            "Live-sync subscribe denied for global topic {} (capability {} not granted)",
            topic.canonical(),
            field);
        return Decision.DENY;
      }
      return Decision.ALLOW;
    } catch (RuntimeException e) {
      log.debug(
          "Live-sync capability probe failed for topic {} (fail-open direction by class)",
          topic.canonical(),
          e);
      return failOpen(topic);
    }
  }

  /**
   * Sets the captured bearer and (when present) the active-org-unit pin header on an outbound
   * probe.
   *
   * @param headers the request headers to mutate
   * @param accessToken the captured bearer
   * @param activeOrgUnitId the captured pin, or {@code null}
   */
  private static void applyAuth(
      @NotNull HttpHeaders headers, String accessToken, @Nullable UUID activeOrgUnitId) {
    headers.setBearerAuth(accessToken);
    if (activeOrgUnitId != null) {
      headers.set(ActiveSquadronRelayFilter.ACTIVE_ORG_UNIT_HEADER, activeOrgUnitId.toString());
    }
  }
}
