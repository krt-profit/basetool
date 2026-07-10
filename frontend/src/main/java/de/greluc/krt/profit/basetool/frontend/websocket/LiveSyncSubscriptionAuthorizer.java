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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Authorizes a {@code /ws/sync} <em>subscribe</em> to a resource-scoped live-sync topic
 * (REQ-FE-015, ADR-0092).
 *
 * <p>The legacy per-resource sockets authorize at handshake time, on the servlet thread, through
 * the ordinary {@code BackendApiClient} (see {@code LiveSyncLegacyHandshakeInterceptor}). The
 * multiplexed {@code /ws/sync} socket instead authorizes each topic when its {@code subscribe}
 * frame arrives — on a WebSocket message / auth-executor thread that has <b>no servlet request
 * context</b>, so the request-context-bound {@code
 * ServletOAuth2AuthorizedClientExchangeFilterFunction} of the normal client cannot resolve a bearer
 * there. This authorizer therefore replays the OAuth2 access token and the active-org-unit pin that
 * {@code LiveSyncSyncHandshakeInterceptor} captured on the handshake's servlet thread as explicit
 * headers on a filter-less {@code liveSyncAuthWebClient} — the same read-only-snapshot pattern the
 * notification SSE relay uses ({@code NotificationPageController.stream}).
 *
 * <p><b>Decision.</b> For a resource-scoped class the authorizer issues the class's {@link
 * LiveSyncTopicClass#authProbePath()} read (e.g. {@code GET /api/v1/operations/{id}}): a 2xx allows
 * the subscribe, an explicit {@code 403}/{@code 404} denies it, and anything else — a {@code 401}
 * from an expired captured token, a {@code 5xx}, a timeout, a transport error — <b>fails open</b>.
 * Failing open is safe because no resource data ever crosses the socket: a subscriber only receives
 * opaque section keys, and every fragment it then re-pulls is independently authorized per viewer
 * through the servlet path (with a fresh token and pin), so a stale-token or backend-blip false
 * allow leaks at most "some section of resource X changed", never its contents. A global-room class
 * ({@code authProbePath == null}) is authorized by the socket's authentication alone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveSyncSubscriptionAuthorizer {

  /** The outcome of a subscribe-authorization check. */
  public enum Decision {
    /** The subscribe is authorized (or failed open). */
    ALLOW,
    /** The subscribe is refused by an explicit backend authorization denial. */
    DENY
  }

  /**
   * Hard bound on a single authorization probe. A probe that has not answered within this window is
   * abandoned and the subscribe fails open, so one slow/hung backend read cannot pin an
   * auth-executor thread indefinitely.
   */
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

  private final WebClient liveSyncAuthWebClient;

  /**
   * Decides whether a subscribe to {@code topic} is authorized for the socket owner, replaying the
   * captured OAuth2 token and active-org-unit pin as explicit headers.
   *
   * @param topic the parsed topic being subscribed to
   * @param accessToken the OAuth2 access token captured at handshake, or {@code null} if none was
   *     available (then the subscribe fails open)
   * @param activeOrgUnitId the active-org-unit pin captured at handshake, relayed as {@code
   *     X-Active-Org-Unit-Id} so the probe scopes exactly like the page's own read, or {@code null}
   * @return {@link Decision#ALLOW} to accept the subscribe (including every fail-open case), or
   *     {@link Decision#DENY} on an explicit backend 403/404
   */
  @NotNull
  public Decision authorize(
      @NotNull LiveSyncTopic topic, @Nullable String accessToken, @Nullable UUID activeOrgUnitId) {
    String probePath = topic.topicClass().authProbePath();
    if (probePath == null || topic.resourceId() == null) {
      // Global room: the socket is already authenticated; a per-role local check is layered on when
      // such a class ships (bank staff, org-unit bank). Nothing to probe here.
      return Decision.ALLOW;
    }
    if (accessToken == null || accessToken.isBlank()) {
      // No captured token snapshot (e.g. a session that lost its authorized client): fail open. The
      // subscriber still only ever receives opaque keys; each fragment re-pull re-authorizes.
      return Decision.ALLOW;
    }
    String uri = probePath.replace("{id}", topic.resourceId().toString());
    try {
      liveSyncAuthWebClient
          .get()
          .uri(uri)
          .headers(
              headers -> {
                headers.setBearerAuth(accessToken);
                if (activeOrgUnitId != null) {
                  headers.set(
                      ActiveSquadronRelayFilter.ACTIVE_ORG_UNIT_HEADER, activeOrgUnitId.toString());
                }
              })
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
          "Live-sync subscribe allowed despite transient backend status {} for topic {}",
          status,
          topic.canonical());
      return Decision.ALLOW;
    } catch (RuntimeException e) {
      log.debug(
          "Live-sync subscribe authorization probe failed for topic {}; allowing (fail-open)",
          topic.canonical(),
          e);
      return Decision.ALLOW;
    }
  }
}
