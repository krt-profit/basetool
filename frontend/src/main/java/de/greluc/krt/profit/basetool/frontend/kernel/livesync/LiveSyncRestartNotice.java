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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.SmartLifecycle;

/**
 * Tells every open page that this replica restarts (REQ-FE-040): in the default phase, which stops
 * before the web server's graceful shutdown, it closes each {@code /ws/sync} socket with {@code
 * 1012 Service Restart} and refuses new ones, so the client shows its maintenance banner at once.
 */
@Slf4j
@RequiredArgsConstructor
public class LiveSyncRestartNotice implements SmartLifecycle {

  /** The handler owning this replica's sockets. */
  private final @NotNull LiveSyncWebSocketHandler handler;

  /** Admits sockets again; the handler admits them from construction on. */
  @Override
  public void start() {
    handler.acceptSocketsAgain();
  }

  /** Closes every socket with {@code 1012} and refuses new ones, and logs how many it closed. */
  @Override
  public void stop() {
    int closed = handler.closeAllForRestart();
    log.info("Closed {} live-sync socket(s) with 1012 ahead of the graceful shutdown", closed);
  }

  /**
   * Reports whether the handler admits sockets, which it does until {@link #stop()}.
   *
   * @return {@code true} until the replica shuts down
   */
  @Override
  public boolean isRunning() {
    return handler.admitsSockets();
  }
}
