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

package de.greluc.krt.profit.basetool.frontend.kernel.livesync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;

/**
 * Unit tests for {@link LiveSyncRestartNotice}: it closes the sockets through the handler and stops
 * before the web server's graceful shutdown (REQ-FE-040).
 */
class LiveSyncRestartNoticeTest {

  private final LiveSyncWebSocketHandler handler = mock(LiveSyncWebSocketHandler.class);
  private final LiveSyncRestartNotice notice = new LiveSyncRestartNotice(handler);

  @Test
  void stop_closesTheSocketsForTheRestart() {
    notice.stop();

    verify(handler).closeAllForRestart();
  }

  @Test
  void start_admitsSocketsAgain() {
    notice.start();

    verify(handler).acceptSocketsAgain();
  }

  @Test
  void isRunning_followsWhetherTheHandlerAdmitsSockets() {
    when(handler.admitsSockets()).thenReturn(true, false);

    assertThat(notice.isRunning()).isTrue();
    assertThat(notice.isRunning()).isFalse();
  }

  @Test
  void stopsBeforeTheWebServersGracefulShutdown() {
    WebServerGracefulShutdownLifecycle gracefulShutdown =
        new WebServerGracefulShutdownLifecycle(mock(WebServer.class));

    assertThat(notice.getPhase())
        .as("a higher phase stops first")
        .isGreaterThan(gracefulShutdown.getPhase());
    assertThat(notice.isAutoStartup()).isTrue();
  }
}
