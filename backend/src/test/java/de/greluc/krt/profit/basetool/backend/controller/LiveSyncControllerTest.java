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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import de.greluc.krt.profit.basetool.backend.dto.LiveSyncChangedRequest;
import de.greluc.krt.profit.basetool.backend.exception.CoreProblemCode;
import de.greluc.krt.profit.basetool.backend.exception.GlobalExceptionHandler;
import de.greluc.krt.profit.basetool.backend.kernel.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.kernel.ProblemResponseFactory;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.service.LiveSyncRelayService;
import de.greluc.krt.profit.basetool.backend.service.LiveSyncStreamService;
import de.greluc.krt.profit.basetool.backend.service.LiveSyncSubscriptionAuthorizer;
import de.greluc.krt.profit.basetool.backend.web.CurrentUserArgumentResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

    assertThatExceptionOfType(ResponseStatusException.class)
        .isThrownBy(() -> controller.stream(ALICE, String.join(",", many), response()))
        .satisfies(error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  @DisplayName("a member moving through the app accumulates rooms and still gets a stream")
  void aMultiScreenUnionIsAccepted() {
    when(authorizer.maySubscribe(any())).thenReturn(true);
    when(streamService.subscribe(eq(ALICE), anyList())).thenReturn(new SseEmitter());
    List<String> union = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      union.add("mission:" + new UUID(0L, i));
    }

    controller.stream(ALICE, String.join(",", union), response());

    verify(streamService).subscribe(eq(ALICE), anyList());
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
  @DisplayName("an unknown topic is a 400 problem and never reaches the relay")
  void anUnknownTopicIsRejected() throws Exception {
    MockHttpServletResponse response = signal("not-a-room", "stock");

    assertProblem(response, HttpStatus.BAD_REQUEST, CoreProblemCode.BAD_REQUEST);
    verify(relayService, never()).publishFromClient(any(), any(), anyList());
  }

  @Test
  @DisplayName("a frame with no known section is a 400 problem, so a client bug is visible")
  void noKnownSectionIsRejected() throws Exception {
    when(relayService.publishFromClient(eq(ALICE), any(), anyList()))
        .thenReturn(LiveSyncRelayService.Outcome.NO_KNOWN_SECTIONS);

    assertProblem(
        signal("inventory", "nonsense"), HttpStatus.BAD_REQUEST, CoreProblemCode.BAD_REQUEST);
  }

  @Test
  @DisplayName("both buckets answer a 429 problem, so the client drops the frame")
  void rateLimitedSignalsAreTooManyRequests() throws Exception {
    when(relayService.publishFromClient(eq(ALICE), any(), anyList()))
        .thenReturn(LiveSyncRelayService.Outcome.SUBJECT_RATE_LIMITED)
        .thenReturn(LiveSyncRelayService.Outcome.TOPIC_RATE_LIMITED);

    for (int i = 0; i < 2; i++) {
      MockHttpServletResponse response = signal("inventory", "stock");
      assertProblem(response, HttpStatus.TOO_MANY_REQUESTS, CoreProblemCode.RATE_LIMIT_EXCEEDED);
      assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }
  }

  /**
   * Posts one {@code changed} frame as {@link #ALICE} through MockMvc, with the real exception
   * handler and the real bundles.
   *
   * @param topic the frame's topic
   * @param section the frame's one section
   * @return the response
   * @throws Exception if the request cannot be performed
   */
  private MockHttpServletResponse signal(String topic, String section) throws Exception {
    AppProblemProperties problemProperties =
        new AppProblemProperties("https://profit-base.online/problems/");
    ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
    messageSource.setBasename("messages");
    messageSource.setDefaultEncoding("UTF-8");
    messageSource.setFallbackToSystemLocale(false);
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new GlobalExceptionHandler(
                    problemProperties,
                    new ProblemResponseFactory(problemProperties),
                    messageSource,
                    new SimpleMeterRegistry()))
            .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
            .build();
    Jwt jwt =
        new Jwt(
            "token",
            Instant.now(),
            Instant.now().plusSeconds(300),
            Map.of("alg", "none"),
            Map.of("sub", ALICE.toString()));
    return mockMvc
        .perform(
            post("/api/v1/live-sync/changed")
                .principal(new JwtAuthenticationToken(jwt, List.of()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"topic\":\"" + topic + "\",\"sections\":[\"" + section + "\"]}"))
        .andReturn()
        .getResponse();
  }

  /**
   * Asserts the response is a localized problem with the given status and code.
   *
   * @param response the response
   * @param status the expected status
   * @param code the expected code
   * @throws Exception if the body cannot be read
   */
  private static void assertProblem(
      MockHttpServletResponse response, HttpStatus status, CoreProblemCode code) throws Exception {
    assertThat(response.getStatus()).isEqualTo(status.value());
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    JsonNode body = new ObjectMapper().readTree(response.getContentAsString());
    assertThat(body.path("code").asString(null)).isEqualTo(code.code());
    assertThat(body.path("correlationId").asString("")).isNotBlank();
    assertThat(body.path("title").asString("")).isNotBlank().doesNotStartWith("problem.");
    assertThat(body.path("detail").asString("")).isNotBlank().doesNotStartWith("problem.");
  }

  private static MockHttpServletResponse response() {
    return new MockHttpServletResponse();
  }
}
