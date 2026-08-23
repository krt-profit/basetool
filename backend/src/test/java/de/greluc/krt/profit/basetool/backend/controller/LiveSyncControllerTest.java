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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.dto.LiveSyncChangedRequest;
import de.greluc.krt.profit.basetool.backend.service.LiveSyncRelayService;
import de.greluc.krt.profit.basetool.backend.service.LiveSyncStreamService;
import de.greluc.krt.profit.basetool.backend.service.LiveSyncSubscriptionAuthorizer;
import de.greluc.krt.profit.basetool.backend.support.LiveSyncTopic;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The bridge's two endpoints: what they accept, what they refuse, and how loudly (ADR-0143). */
@ExtendWith(MockitoExtension.class)
class LiveSyncControllerTest {

  private static final UUID ALICE = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID MISSION_ID = UUID.fromString("8f14e45f-ceea-467a-9c5b-5f1f52a3a1c2");

  @Mock private LiveSyncStreamService streamService;
  @Mock private LiveSyncSubscriptionAuthorizer authorizer;
  @Mock private LiveSyncRelayService relayService;

  @InjectMocks private LiveSyncController controller;

  @Test
  @DisplayName("the accepted topics are handed to the registry, the refused one is dropped")
  void refusedTopicsAreDroppedRatherThanFatal() {
    when(authorizer.maySubscribe(any()))
        .thenAnswer(
            call -> !"mission".equals(((LiveSyncTopic) call.getArgument(0)).topicClass().prefix()));
    when(streamService.subscribe(eq(ALICE), anyList())).thenReturn(new SseEmitter());

    controller.stream(ALICE, "inventory,mission:" + MISSION_ID + ",materialboard", response());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<LiveSyncTopic>> accepted = ArgumentCaptor.forClass(List.class);
    verify(streamService).subscribe(eq(ALICE), accepted.capture());
    // A stream that asked for three rooms and got two opens with two — the client is told which,
    // and treats the third as poll-only rather than believing it is live.
    assertThat(accepted.getValue())
        .extracting(LiveSyncTopic::canonical)
        .containsExactly("inventory", "materialboard");
  }

  @Test
  @DisplayName("a topic naming no room at all is dropped the same way a refused one is")
  void unparseableTopicsAreDropped() {
    when(authorizer.maySubscribe(any())).thenReturn(true);
    when(streamService.subscribe(eq(ALICE), anyList())).thenReturn(new SseEmitter());

    controller.stream(ALICE, "inventory,not-a-room,bank", response());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<LiveSyncTopic>> accepted = ArgumentCaptor.forClass(List.class);
    verify(streamService).subscribe(eq(ALICE), accepted.capture());
    assertThat(accepted.getValue())
        .extracting(LiveSyncTopic::canonical)
        .containsExactly("inventory");
  }

  @Test
  @DisplayName("a stream where nothing was accepted is refused rather than opened empty")
  void nothingAcceptedIsForbidden() {
    lenient().when(authorizer.maySubscribe(any())).thenReturn(false);

    assertThatExceptionOfType(AccessDeniedException.class)
        .isThrownBy(() -> controller.stream(ALICE, "inventory", response()));
    verify(streamService, never()).subscribe(any(), anyList());
  }

  @Test
  @DisplayName("too many topics is refused, never silently truncated")
  void tooManyTopicsIsRefused() {
    List<String> many = new ArrayList<>();
    for (int i = 0; i <= LiveSyncController.MAX_TOPICS_PER_STREAM; i++) {
      many.add("mission:" + new UUID(0L, i));
    }

    // Truncating would leave the tail screens silently non-live, which is the failure shape this
    // whole design is trying to avoid.
    assertThatExceptionOfType(ResponseStatusException.class)
        .isThrownBy(() -> controller.stream(ALICE, String.join(",", many), response()))
        .satisfies(error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  @DisplayName("duplicates and blanks in the parameter do not count against the cap")
  void duplicatesAreCollapsed() {
    when(authorizer.maySubscribe(any())).thenReturn(true);
    when(streamService.subscribe(eq(ALICE), anyList())).thenReturn(new SseEmitter());

    controller.stream(ALICE, "inventory,,inventory, materialboard ,", response());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<LiveSyncTopic>> accepted = ArgumentCaptor.forClass(List.class);
    verify(streamService).subscribe(eq(ALICE), accepted.capture());
    assertThat(accepted.getValue()).hasSize(2);
  }

  @Test
  @DisplayName("the no-buffering header rides with the stream, not with the vhost")
  void theStreamSetsTheNoBufferingHeader() {
    when(authorizer.maySubscribe(any())).thenReturn(true);
    when(streamService.subscribe(eq(ALICE), anyList())).thenReturn(new SseEmitter());
    HttpServletResponse response = response();

    controller.stream(ALICE, "inventory", response);

    // Without it an nginx buffers a body that trickles a few bytes every twenty seconds, and the
    // symptom reads as "live sync does not work on this network".
    assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
  }

  @Test
  @DisplayName("a relayed signal answers 202 — it is a signal, not a transaction")
  void anAcceptedSignalIsAccepted() {
    when(relayService.publishFromClient(eq(ALICE), any(), anyList()))
        .thenReturn(LiveSyncRelayService.Outcome.ACCEPTED);

    assertThat(
            controller
                .changed(ALICE, new LiveSyncChangedRequest("inventory", List.of("stock")))
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
  }

  @Test
  @DisplayName("an unknown topic is a 400 and never reaches the relay")
  void anUnknownTopicIsRejected() {
    assertThat(
            controller
                .changed(ALICE, new LiveSyncChangedRequest("not-a-room", List.of("stock")))
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    verify(relayService, never()).publishFromClient(any(), any(), anyList());
  }

  @Test
  @DisplayName("a frame with no known section is a 400, so a client bug is visible")
  void noKnownSectionIsRejected() {
    when(relayService.publishFromClient(eq(ALICE), any(), anyList()))
        .thenReturn(LiveSyncRelayService.Outcome.NO_KNOWN_SECTIONS);

    assertThat(
            controller
                .changed(ALICE, new LiveSyncChangedRequest("inventory", List.of("nonsense")))
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  @DisplayName("both buckets answer 429, so the client drops the frame instead of retrying")
  void rateLimitedSignalsAreTooManyRequests() {
    when(relayService.publishFromClient(eq(ALICE), any(), anyList()))
        .thenReturn(LiveSyncRelayService.Outcome.SUBJECT_RATE_LIMITED)
        .thenReturn(LiveSyncRelayService.Outcome.TOPIC_RATE_LIMITED);

    LiveSyncChangedRequest request = new LiveSyncChangedRequest("inventory", List.of("stock"));
    assertThat(controller.changed(ALICE, request).getStatusCode())
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(controller.changed(ALICE, request).getStatusCode())
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  private static MockHttpServletResponse response() {
    return new MockHttpServletResponse();
  }
}
