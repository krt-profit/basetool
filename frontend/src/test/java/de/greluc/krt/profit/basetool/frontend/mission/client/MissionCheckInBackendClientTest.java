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

package de.greluc.krt.profit.basetool.frontend.mission.client;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pins the requests {@link MissionCheckInBackendClient} sends (REQ-MISSION-032). */
class MissionCheckInBackendClientTest {

  private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final String PARTICIPANTS = "/api/v1/missions/" + ID + "/participants";

  private BackendClientHarness backend;
  private MissionCheckInBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new MissionCheckInBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void checkInAllPostsWithoutABody() {
    backend.answerEmpty();

    client.checkInAll(ID);

    backend.expect("POST", PARTICIPANTS + "/check-in-all", null);
  }

  @Test
  void checkOutAllPostsWithoutABody() {
    backend.answerEmpty();

    client.checkOutAll(ID);

    backend.expect("POST", PARTICIPANTS + "/check-out-all", null);
  }
}
