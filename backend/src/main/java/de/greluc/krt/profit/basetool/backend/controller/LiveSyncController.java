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
 * The app's live-sync bridge: one SSE stream to receive {@code changed} frames and one endpoint to
 * emit them over the frontend's Redis channel (ADR-0143).
 *
 * <p>Neither endpoint carries domain data, only room names and opaque section keys; clients
 * re-fetch through the ordinary authorized reads.
 */
@RestController
@RequestMapping("/api/v1/live-sync")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Live sync", description = "Real-time change signals for the native app.")
@SecurityRequirement(name = "bearer-jwt")
@Slf4j
public class LiveSyncController {

  /**
   * Maximum number of topics one stream may name.
   *
   * <p>A per-client budget covering the union of all rooms the app observes; it matches {@code
   * LiveSyncWebSocketHandler.MAX_TOPICS_PER_SESSION} and bounds the authorization reads one
   * connection can trigger.
   */
  static final int MAX_TOPICS_PER_STREAM = 16;

  private final LiveSyncStreamService streamService;
  private final LiveSyncSubscriptionAuthorizer authorizer;
  private final LiveSyncRelayService relayService;

  /**
   * Opens the caller's live-sync stream over the named topics the caller may join.
   *
   * <p>The topic set is fixed for the stream's life. Topics the caller may not join, or that name
   * no room this backend serves, are dropped; the accepted list is sent in the first {@code
   * subscribed} event. The response carries {@code X-Accel-Buffering: no} so proxies do not buffer
   * the stream.
   *
   * @param sub the caller's id, from the JWT subject claim
   * @param topics the rooms to join, comma-separated
   * @param response the servlet response, used only for the no-buffering header
   * @return the SSE emitter, already carrying its {@code subscribed} event
   * @throws ResponseStatusException 400 if more than {@link #MAX_TOPICS_PER_STREAM} topics are
   *     named
   * @throws AccessDeniedException if no named topic was accepted
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
   * <p>Best-effort signal sent after the caller's mutation succeeded; a {@code 429} must be
   * dropped, not retried.
   *
   * @param sub the caller's id, from the JWT subject claim
   * @param request the room and the regions that changed
   * @return {@code 202} when relayed, {@code 400} when it named no real room or region, {@code 429}
   *     when rate-limited
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
      @CurrentUserId UUID sub, @NotNull @Valid @RequestBody LiveSyncChangedRequest request) {
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
