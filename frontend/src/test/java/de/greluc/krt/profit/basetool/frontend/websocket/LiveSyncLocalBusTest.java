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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LiveSyncLocalBus}: the server-side publish seam delegates to the handler's
 * server-originated publish path.
 */
class LiveSyncLocalBusTest {

  @Test
  void publish_delegatesToHandlerServerPublish() {
    LiveSyncWebSocketHandler handler = mock(LiveSyncWebSocketHandler.class);
    LiveSyncLocalBus bus = new LiveSyncLocalBus(handler);

    bus.publish("orders", List.of("queue"));

    verify(handler).publishFromServer("orders", List.of("queue"));
  }
}
