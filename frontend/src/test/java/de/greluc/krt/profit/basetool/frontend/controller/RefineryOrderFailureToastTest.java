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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests which toast the no-JS refinery create and edit handlers flash on a backend refusal, via
 * {@link RefineryOrderWriteController#failureToastKey}: a mission the owner does not take part in
 * gets its own message (REQ-SEC-042), every other failure the generic one.
 */
class RefineryOrderFailureToastTest {

  private static BackendServiceException refusal(int status, String code) {
    return new BackendServiceException(
        "refused", null, status, code, null, List.of(), "backend detail");
  }

  @Test
  void missionParticipantRequired_namesTheMissionField() {
    assertEquals(
        "error.refineryorder.mission.participant_required",
        RefineryOrderWriteController.failureToastKey(
            refusal(400, BackendServiceException.CODE_MISSION_PARTICIPANT_REQUIRED),
            "error.refineryorder.create.failed"));
  }

  @Test
  void anyOtherBadRequest_keepsTheGenericMessage() {
    assertEquals(
        "error.refineryorder.update.failed",
        RefineryOrderWriteController.failureToastKey(
            refusal(400, "BAD_REQUEST"), "error.refineryorder.update.failed"));
  }

  @Test
  void aServerError_keepsTheGenericMessage() {
    assertEquals(
        "error.refineryorder.create.failed",
        RefineryOrderWriteController.failureToastKey(
            refusal(500, "INTERNAL_ERROR"), "error.refineryorder.create.failed"));
  }
}
