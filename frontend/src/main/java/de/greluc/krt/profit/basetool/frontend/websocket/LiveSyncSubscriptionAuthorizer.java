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
 * Authorizes a {@code /ws/sync} <em>subscribe</em> to a resource-scoped live-sync topic
 * (REQ-FE-015, ADR-0094).
 *
 * <p>The multiplexed {@code /ws/sync} socket authorizes each topic when its {@code subscribe} frame
 * arrives — on a WebSocket message / auth-executor thread that has <b>no servlet request
 * context</b>, so the request-context-bound {@code
 * ServletOAuth2AuthorizedClientExchangeFilterFunction} of the normal client cannot resolve a bearer
 * there. This authorizer therefore replays the OAuth2 access token and the active-org-unit pin that
 * {@code LiveSyncSyncHandshakeInterceptor} captured on the handshake's servlet thread as explicit
 * headers on a filter-less {@code liveSyncAuthWebClient} — the same read-only-snapshot pattern the
 * notification SSE relay uses ({@code NotificationPageController.stream}).
 *
 * <p><b>Decision.</b> For a resource-scoped class the authorizer issues the class's {@link
 * LiveSyncTopicClass#authProbePath()} read (e.g. {@code GET /api/v1/operations/{id}}): a 2xx allows
 * the subscribe, an explicit {@code 403}/{@code 404} denies it, and anything <em>indeterminate</em>
 * — a {@code 401} from an expired captured token, a {@code 5xx}, a timeout, a transport error — is
 * resolved by {@link #failOpen(LiveSyncTopic)}: <b>open</b> for a non-presence class, <b>closed</b>
 * for a presence-enabled one. Failing open is safe for a non-presence class because no resource
 * data ever crosses the socket — a subscriber only receives opaque section keys, and every fragment
 * it then re-pulls is independently authorized per viewer through the servlet path (with a fresh
 * token and pin), so a stale-token or backend-blip false allow leaks at most "some section of
 * resource X changed", never its contents. A presence-enabled class ({@link
 * LiveSyncTopicClass#MISSION}) fails <b>closed</b> instead: its allowed subscribe immediately emits
 * an editor-presence snapshot (ids + callsigns), which is cross-user identity data the opaque-keys
 * argument does not cover (F1). A global-room class either requires a capability (a capabilities
 * read whose {@link LiveSyncTopicClass#capabilityField} must be {@code true} — a withheld flag,
 * e.g. a non-profit requester lacking {@code canViewJobOrders}, denies; a failed read fails open)
 * or, with no probe path at all, is authorized by the socket's authentication alone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveSyncSubscriptionAuthorizer {

  /**
   * The outcome of a subscribe-authorization check.
   *
   * <p>The two refusals are kept apart because they mean opposite things operationally: {@link
   * #DENY} is a real permission verdict a user hit, {@link #DENY_INDETERMINATE} is the tool failing
   * closed while it could not tell. They behave identically on the wire (both refuse the subscribe,
   * and a refused subscribe is terminal for that tab), so callers gate on {@link #denied()} rather
   * than comparing constants; only the deny metric's {@code reason} tag and the log level differ.
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
     * The subscribe is refused because the authorization outcome was <em>indeterminate</em> (no
     * captured token, a transient 401/5xx/timeout/transport failure, auth-executor saturation, or a
     * probe that threw) and the topic class is presence-enabled, so it fails <b>closed</b> (F1).
     * Not a permission verdict: a rising rate here is a backend/token availability problem, and one
     * such deny costs the tab live updates for that topic for the rest of the session.
     */
    DENY_INDETERMINATE;

    /**
     * Whether this verdict refuses the subscribe, i.e. is either refusal rather than {@link
     * #ALLOW}. Call sites that only need "join the room or not" use this so a further deny flavour
     * can be added without them silently starting to admit it.
     *
     * @return {@code true} for {@link #DENY} and {@link #DENY_INDETERMINATE}, {@code false} for
     *     {@link #ALLOW}
     */
    public boolean denied() {
      return this != ALLOW;
    }
  }

  /**
   * The verdict for an <em>indeterminate</em> authorization outcome — no captured token, a
   * transient backend failure (401/5xx/timeout/transport), auth-executor saturation, or a probe
   * that threw: fail <b>open</b> for a non-presence class, fail <b>closed</b> for a
   * presence-enabled one (F1).
   *
   * <p>Failing open is safe for a non-presence class because only opaque section keys ever cross
   * the socket and every fragment the subscriber then re-pulls is independently re-authorized per
   * viewer. But a presence-enabled class ({@link LiveSyncTopicClass#MISSION}) immediately emits a
   * presence snapshot carrying each editor's pseudonymous id and callsign, so an indeterminate
   * <em>allow</em> there would disclose <em>who</em> is editing a resource the caller may not be
   * able to read — a cross-user identity leak the opaque-keys argument does not cover. Such a topic
   * therefore fails closed: an indeterminate verdict denies rather than admits.
   *
   * <p>The fail-closed refusal is {@link Decision#DENY_INDETERMINATE}, never {@link Decision#DENY}:
   * it is an availability symptom, not a permission verdict, and collapsing the two makes a backend
   * outage read exactly like users hitting permission boundaries.
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
   * Decides whether a subscribe to {@code topic} is authorized for the socket owner, replaying the
   * captured OAuth2 token and active-org-unit pin as explicit headers. Dispatches by class: a
   * resource-scoped topic runs a per-resource read; a global topic with a {@link
   * LiveSyncTopicClass#capabilityField()} runs a capabilities read and requires that flag; any
   * other global topic (no probe path) is authorized by the socket authentication alone.
   *
   * @param topic the parsed topic being subscribed to
   * @param accessToken the OAuth2 access token captured at handshake, or {@code null} if none was
   *     available (then the subscribe fails open)
   * @param activeOrgUnitId the active-org-unit pin captured at handshake, relayed as {@code
   *     X-Active-Org-Unit-Id} so the probe scopes exactly like the page's own read, or {@code null}
   * @return {@link Decision#ALLOW} to accept the subscribe (including every fail-open case), {@link
   *     Decision#DENY} on an explicit backend 403/404 (resource topic) or a withheld capability
   *     (global topic), or {@link Decision#DENY_INDETERMINATE} when a presence-enabled class failed
   *     closed on an indeterminate outcome
   */
  @NotNull
  public Decision authorize(
      @NotNull LiveSyncTopic topic, @Nullable String accessToken, @Nullable UUID activeOrgUnitId) {
    return authorize(topic, accessToken, activeOrgUnitId, null);
  }

  /**
   * Decides whether a subscribe to {@code topic} is authorized, additionally consulting the
   * authorities captured at handshake for a locally role-gated global room (the {@code bank} staff
   * and {@code orgunit-bank} rooms).
   *
   * @param topic the parsed topic being subscribed to
   * @param accessToken the OAuth2 access token captured at handshake, or {@code null}
   * @param activeOrgUnitId the active-org-unit pin captured at handshake, or {@code null}
   * @param authorities the authorities captured at handshake for a local role check, or {@code
   *     null} when none were captured (then a locally role-gated room fails open)
   * @return {@link Decision#ALLOW} to accept the subscribe (including every fail-open case), {@link
   *     Decision#DENY} on an explicit backend refusal (resource/dual-resource topic), a withheld
   *     capability (capability topic) or a missing required role (local topic), or {@link
   *     Decision#DENY_INDETERMINATE} when a presence-enabled class failed closed on an
   *     indeterminate outcome
   */
  @NotNull
  public Decision authorize(
      @NotNull LiveSyncTopic topic,
      @Nullable String accessToken,
      @Nullable UUID activeOrgUnitId,
      @Nullable Set<String> authorities) {
    Set<String> requiredAnyRole = topic.topicClass().requiredAnyRole();
    if (requiredAnyRole != null) {
      // Local, backend-free role check against the handshake-captured authorities (bank staff /
      // orgunit-bank). A missing capture is indeterminate — fail open for these non-presence rooms
      // (opaque keys only; each fragment re-pull re-authorizes per viewer through the servlet
      // path).
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
      // Authenticated-only global room: nothing to probe.
      return Decision.ALLOW;
    }
    if (accessToken == null || accessToken.isBlank()) {
      // No captured token snapshot (e.g. a session whose token lapsed — the snapshot is never
      // refreshed, so this is a steady state, not a blip): indeterminate. Non-presence classes fail
      // open (opaque keys only; each fragment re-pull re-authorizes); a presence class fails closed
      // so a lapsed-token caller cannot pull the editor-identity snapshot of a mission it can't
      // read
      // (F1).
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
    // A global class with a probe path but neither an id nor a capability field is a
    // misconfiguration
    // rather than a runtime state; authenticated access suffices.
    return Decision.ALLOW;
  }

  /**
   * Runs a per-resource authorization read, with a fallback: the primary read decides unless it
   * <b>explicitly</b> refuses (403/404), in which case the {@code fallbackUri} (when present)
   * decides — so a dual-read class ({@link LiveSyncTopicClass#BANK_ACCOUNT}) is denied only when
   * both reads explicitly refuse. A primary 2xx or a primary transient failure (fail-open) short-
   * circuits without touching the fallback.
   *
   * @param topic the topic (for logging)
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
      // ALLOW (2xx or a transient fail-open) is final; an explicit DENY with no fallback is final.
      return primary;
    }
    // The primary explicitly refused (403/404); the org-unit fallback read decides — a 2xx there
    // allows, a second explicit refusal denies, a transient failure fails open.
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
   * Runs a capability authorization read (a global class): reads the capabilities response and
   * requires {@code field} to be {@code true}. A withheld capability is an explicit DENY; any
   * failure to read the capabilities (401/5xx/timeout/transport) fails open — the DENY signal is
   * the flag being {@code false}, not the HTTP status, and the queue fragment re-authorizes per
   * viewer anyway.
   *
   * @param topic the topic (for logging)
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
      HttpHeaders headers, String accessToken, @Nullable UUID activeOrgUnitId) {
    headers.setBearerAuth(accessToken);
    if (activeOrgUnitId != null) {
      headers.set(ActiveSquadronRelayFilter.ACTIVE_ORG_UNIT_HEADER, activeOrgUnitId.toString());
    }
  }
}
