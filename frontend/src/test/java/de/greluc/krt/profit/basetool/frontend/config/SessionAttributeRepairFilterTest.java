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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.session.web.http.SessionRepositoryFilter;

/**
 * {@link SessionAttributeRepairFilter} must remove exactly what the session read dropped, on the
 * request that dropped it, and must never carry a name across to the next request on the same
 * pooled thread.
 */
class SessionAttributeRepairFilterTest {

  private static final String ATTRIBUTE =
      "org.apache.tomcat.websocket.server.WsHttpSessionBindingListener";

  private final SessionAttributeRepairFilter filter = new SessionAttributeRepairFilter();
  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final HttpServletResponse response = mock(HttpServletResponse.class);
  private final HttpSession session = mock(HttpSession.class);

  @BeforeEach
  void setUp() {
    SessionAttributeRepairQueue.clear();
  }

  @AfterEach
  void tearDown() {
    SessionAttributeRepairQueue.clear();
  }

  /**
   * A chain that records a drop the way the session read does, part-way through the request.
   *
   * @return a chain that queues {@link #ATTRIBUTE} for repair.
   */
  private static FilterChain droppingChain() {
    return (req, res) -> SessionAttributeRepairQueue.record(ATTRIBUTE);
  }

  @Test
  void aDroppedAttributeIsRemovedFromTheSession() throws Exception {
    when(request.getSession(false)).thenReturn(session);

    filter.doFilter(request, response, droppingChain());

    verify(session).removeAttribute(ATTRIBUTE);
    assertThat(SessionAttributeRepairQueue.drain()).as("the queue is drained").isEmpty();
  }

  @Test
  void aRequestThatDroppedNothingNeverTouchesTheSession() throws Exception {
    filter.doFilter(request, response, (req, res) -> {});

    // Not even getSession(false): a repair pass must not be a reason to materialise a session
    // reference on requests that had no failure, which is every request in steady state.
    verify(request, never()).getSession(false);
    verify(session, never()).removeAttribute(anyString());
  }

  @Test
  void aNameLeftBehindByAnEarlierRequestIsDiscardedRatherThanApplied() throws Exception {
    // Tomcat pools request threads. Applying a leftover name would remove an attribute from a
    // DIFFERENT member's session, so the queue is cleared on entry as well as drained on exit.
    SessionAttributeRepairQueue.record("stale.attribute.from.a.previous.request");
    when(request.getSession(false)).thenReturn(session);

    filter.doFilter(request, response, (req, res) -> {});

    verify(request, never()).getSession(false);
    verify(session, never()).removeAttribute(anyString());
  }

  @Test
  void aSessionInvalidatedDuringTheRequestIsNotAnError() throws Exception {
    // Logout invalidates the session inside the chain; the poisoned hash is deleted with it.
    when(request.getSession(false)).thenReturn(null);

    assertThatCode(() -> filter.doFilter(request, response, droppingChain()))
        .doesNotThrowAnyException();
    assertThat(SessionAttributeRepairQueue.drain()).isEmpty();
  }

  @Test
  void anInvalidationRacingTheRepairIsSwallowed() throws Exception {
    when(request.getSession(false)).thenReturn(session);
    doThrow(new IllegalStateException("session already invalidated"))
        .when(session)
        .removeAttribute(ATTRIBUTE);

    assertThatCode(() -> filter.doFilter(request, response, droppingChain()))
        .doesNotThrowAnyException();
  }

  @Test
  void theRepairStillRunsWhenTheChainThrows() throws Exception {
    when(request.getSession(false)).thenReturn(session);
    FilterChain failing =
        (req, res) -> {
          SessionAttributeRepairQueue.record(ATTRIBUTE);
          throw new IllegalArgumentException("downstream blew up");
        };

    assertThatCode(() -> filter.doFilter(request, response, failing))
        .isInstanceOf(IllegalArgumentException.class);

    // A request that ends in a 500 is exactly a request whose session was read, so skipping the
    // repair there would leave the poison in place for the next one.
    verify(session).removeAttribute(ATTRIBUTE);
  }

  @Test
  void theFilterSitsImmediatelyInsideSpringSessionsOwnFilter() {
    // Must be greater than SessionRepositoryFilter's order, or the session is not wrapped yet when
    // the repair runs — and small enough that everything downstream is still inside the finally.
    assertThat(filter.getOrder()).isGreaterThan(SessionRepositoryFilter.DEFAULT_ORDER);
    assertThat(filter.getOrder()).isEqualTo(SessionRepositoryFilter.DEFAULT_ORDER + 10);
  }
}
