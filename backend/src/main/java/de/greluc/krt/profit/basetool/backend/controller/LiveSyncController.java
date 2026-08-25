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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.dto.LiveSyncChangedRequest;
import de.greluc.krt.profit.basetool.backend.service.LiveSyncRelayService;
import de.greluc.krt.profit.basetool.backend.service.LiveSyncStreamService;
import de.greluc.krt.profit.basetool.backend.service.LiveSyncSubscriptionAuthorizer;
import de.greluc.krt.profit.basetool.backend.support.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.web.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The app's live-sync bridge: one SSE stream to receive {@code changed} frames, one endpoint to
 * emit them (ADR-0143).
 *
 * <p>Together they close the gap the web has been covering alone since ADR-0094 — a browser's edit
 * now reaches the app, and the app's edit now reaches every open browser, because both directions
 * ride the frontend's own Redis channel with the frontend's own payload.
 *
 * <p>Neither endpoint carries domain data. The stream emits room names and opaque section keys; the
 * publish endpoint accepts the same. Everything a client does with a frame goes through the
 * ordinary, separately authorized read it would have performed anyway.
 */
@RestController
@RequestMapping("/api/v1/live-sync")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Live sync", description = "Real-time change signals for the native app.")
@SecurityRequirement(name = "bearerAuth")
@Slf4j
public class LiveSyncController {

  /**
   * Topics one stream may name.
   *
   * <p><strong>This is a per-client budget, not a per-screen one.</strong> The first revision sized
   * it at 8 against "what the busiest screen needs — a detail room, its list room and the global
   * inventory room", and that reasoning was wrong about the client it serves: the app holds
   * <em>one</em> stream and asks for the union of every screen currently observing, so a member
   * moving through the app accumulates rooms from screens still on the back stack. In production
   * one member's app crossed 8, and because the endpoint refuses the whole request rather than the
   * surplus, live sync was dead on <em>every</em> screen for as long as the app stayed open — it
   * re-asked on the reconnect backoff and was refused each time.
   *
   * <p>16 matches {@code LiveSyncWebSocketHandler.MAX_TOPICS_PER_SESSION}, the web relay's cap for
   * the same multiplexed-union shape. Both exist for the same reason — a crafted request must not
   * make the server run unbounded authorization reads for one connection — and they should not
   * disagree about the number, because the two clients subscribe alike.
   */
  static final int MAX_TOPICS_PER_STREAM = 16;

  private final LiveSyncStreamService streamService;
  private final LiveSyncSubscriptionAuthorizer authorizer;
  private final LiveSyncRelayService relayService;

  /**
   * Opens the caller's live-sync stream over the topics they name and are allowed to join.
   *
   * <p>The topic set is fixed for the stream's life; a client that navigates closes this stream and
   * opens another. That is the whole subscription protocol, and it is why there is no {@code
   * subscribe} frame to get out of sync after a reconnect.
   *
   * <p>A topic the caller may not join, or that names no room this backend serves, is <b>dropped
   * from the set</b> and the stream opens without it — the accepted list goes out in the first
   * {@code subscribed} event so a client can tell a live room from one it will never hear about,
   * and fall back to polling for that screen rather than trusting a stream that will stay silent.
   * Only a request where <em>nothing</em> was accepted is refused, because an emitter with no rooms
   * is a connection held open for nothing.
   *
   * <p><strong>{@code X-Accel-Buffering: no} is load-bearing</strong>, for the same reason it is on
   * the notification stream: an nginx buffering the response holds a body that trickles a few bytes
   * every twenty seconds, and the symptom is "live sync does not work on this network" rather than
   * a proxy setting.
   *
   * @param sub the caller's id, from the JWT subject claim
   * @param topics the rooms to join, comma-separated
   * @param response the servlet response, used only for the no-buffering header
   * @return the SSE emitter, already carrying its {@code subscribed} event
   * @throws ResponseStatusException 400 if more topics than {@link #MAX_TOPICS_PER_STREAM} are
   *     named — refused rather than truncated, because silently dropping the tail would leave a
   *     screen half-live with nothing to notice it by
   * @throws AccessDeniedException if not one named topic was accepted
   */
  @GetMapping("/stream")
  @Operation(summary = "Subscribe to live change signals for a set of topics (Server-Sent Events).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "SSE stream opened."),
    @ApiResponse(responseCode = "400", description = "Too many topics named."),
    @ApiResponse(responseCode = "401", description = "Authentication required."),
    @ApiResponse(responseCode = "403", description = "No named topic was accepted.")
  })
  public SseEmitter stream(
      @CurrentUserId UUID sub,
      @Parameter(description = "Comma-separated topics, e.g. `missions,mission:<uuid>`.")
          @RequestParam("topics")
          String topics,
      HttpServletResponse response) {
    List<String> requested = splitTopics(topics);
    if (requested.size() > MAX_TOPICS_PER_STREAM) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "At most " + MAX_TOPICS_PER_STREAM + " topics per stream");
    }
    List<LiveSyncTopic> accepted = new ArrayList<>();
    for (String raw : requested) {
      LiveSyncTopic topic = LiveSyncTopic.parse(raw);
      if (topic == null) {
        authorizer.recordInvalidTopic();
      } else if (authorizer.maySubscribe(topic)) {
        accepted.add(topic);
      }
    }
    if (accepted.isEmpty()) {
      throw new AccessDeniedException("No live-sync topic in the request was accepted");
    }
    response.setHeader("X-Accel-Buffering", "no");
    return streamService.subscribe(sub, accepted);
  }

  /**
   * Announces that the caller changed a room, so its other viewers re-fetch.
   *
   * <p>Answers {@code 202} — the frame is a best-effort signal, not a transaction. The caller's own
   * mutation has already succeeded by the time this is sent, and whether peers were reached says
   * nothing about it; a client that treated a failure here as a failed write would show an error
   * for a change that is in the database.
   *
   * <p>A rate-limited frame is {@code 429} and a client must simply drop it rather than retry: the
   * buckets exist to bound the re-fetch herd, and a retry would defeat exactly the bound it hit.
   *
   * @param sub the caller's id, from the JWT subject claim
   * @param request the room and the regions that changed
   * @return {@code 202} when relayed, {@code 400} when nothing in it named a real room or region,
   *     {@code 429} when a bucket refused it
   */
  @PostMapping("/changed")
  @Operation(summary = "Announce a change so other viewers of the same room re-fetch.")
  @ApiResponses({
    @ApiResponse(responseCode = "202", description = "Signal relayed."),
    @ApiResponse(responseCode = "400", description = "Unknown topic, or no known section."),
    @ApiResponse(responseCode = "401", description = "Authentication required."),
    @ApiResponse(responseCode = "429", description = "Signal rate exceeded; drop the frame.")
  })
  public ResponseEntity<Void> changed(
      @CurrentUserId UUID sub, @Valid @RequestBody LiveSyncChangedRequest request) {
    LiveSyncTopic topic = LiveSyncTopic.parse(request.topic());
    if (topic == null) {
      authorizer.recordInvalidTopic();
      return ResponseEntity.badRequest().build();
    }
    LiveSyncRelayService.Outcome outcome =
        relayService.publishFromClient(sub, topic, request.sections());
    return switch (outcome) {
      case ACCEPTED -> ResponseEntity.accepted().build();
      case NO_KNOWN_SECTIONS -> ResponseEntity.badRequest().build();
      case SUBJECT_RATE_LIMITED, TOPIC_RATE_LIMITED ->
          ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
    };
  }

  /**
   * Splits the {@code topics} parameter, dropping blanks and duplicates while keeping order.
   *
   * @param raw the parameter value
   * @return the requested topic strings, still unparsed
   */
  @NotNull
  private static List<String> splitTopics(@NotNull String raw) {
    LinkedHashSet<String> unique = new LinkedHashSet<>();
    for (String part : raw.split(",", -1)) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        unique.add(trimmed);
      }
    }
    return List.copyOf(unique);
  }
}
