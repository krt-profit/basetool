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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;

/**
 * Pins {@link ServerConfiguredKeyspaceNotificationsAction}, the startup step under a per-service
 * Redis ACL user (REQ-SEC-068, ADR-0207): one {@code PING}, no {@code CONFIG}, and a startup that
 * still fails when the session store does not answer (ADR-0084).
 */
class ServerConfiguredKeyspaceNotificationsActionTest {

  private final ServerConfiguredKeyspaceNotificationsAction action =
      new ServerConfiguredKeyspaceNotificationsAction("basetool-frontend");

  /**
   * A healthy store answers {@code PONG}, and {@code PING} is the only thing sent: {@code
   * serverCommands()} — the way to {@code CONFIG GET} — is never even asked for.
   */
  @Test
  void aPongIsEnoughAndNothingButPingIsSent() {
    RedisConnection connection = mock(RedisConnection.class);
    when(connection.ping()).thenReturn("PONG");

    assertThatCode(() -> action.configure(connection)).doesNotThrowAnyException();

    verify(connection).ping();
    verifyNoMoreInteractions(connection);
  }

  /**
   * Verifies that a {@code DataAccessException} from the {@code PING} propagates unchanged, so the
   * startup fails.
   */
  @Test
  void aFailedPingFailsTheStartup() {
    RedisConnection connection = mock(RedisConnection.class);
    RedisConnectionFailureException failure =
        new RedisConnectionFailureException("Unable to connect to Redis");
    when(connection.ping()).thenThrow(failure);

    assertThatThrownBy(() -> action.configure(connection)).isSameAs(failure);
  }

  /**
   * A reply that is not {@code PONG} — {@code null} included, which is what a connection in
   * pipeline or transaction mode returns — is not taken as a healthy store.
   */
  @Test
  void anythingButPongFailsTheStartup() {
    RedisConnection connection = mock(RedisConnection.class);
    when(connection.ping()).thenReturn(null);

    assertThatThrownBy(() -> action.configure(connection))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PONG");
  }
}
