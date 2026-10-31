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

package de.greluc.krt.profit.basetool.frontend.joborder.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderAssigneeCandidateDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The relay of a job order's assignee picker (REQ-ORDERS-047). */
class JobOrderAssigneeCandidateProxyControllerTest {

  private static final UUID ORDER_ID = UUID.randomUUID();

  private final JobOrderBackendClient client = mock(JobOrderBackendClient.class);

  private final JobOrderAssigneeCandidateProxyController controller =
      new JobOrderAssigneeCandidateProxyController(client);

  @Test
  void relaysTheBackendCandidatesForTheQuery() {
    JobOrderAssigneeCandidateDto external =
        new JobOrderAssigneeCandidateDto(UUID.randomUUID(), "Vega", false, "GB", "Gamma Bravo");
    when(client.assigneeCandidates(ORDER_ID, "ve")).thenReturn(List.of(external));

    assertThat(controller.assigneeCandidates(ORDER_ID, "ve")).containsExactly(external);
  }

  @Test
  void aMissingQuerySearchesTheFirstAccounts() {
    when(client.assigneeCandidates(ORDER_ID, "")).thenReturn(List.of());

    assertThat(controller.assigneeCandidates(ORDER_ID, null)).isEmpty();
  }

  @Test
  void aBodylessBackendAnswerIsAnEmptyList() {
    when(client.assigneeCandidates(ORDER_ID, "x")).thenReturn(null);

    assertThat(controller.assigneeCandidates(ORDER_ID, "x")).isEmpty();
  }
}
