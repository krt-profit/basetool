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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import de.greluc.krt.profit.basetool.frontend.mission.model.CreateMissionTemplateRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionTemplateOwnerDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionTemplateSummaryDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.UpdateMissionTemplateRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pins mission template request scope and revision at the backend HTTP boundary. */
class MissionTemplateBackendClientTest {

  private static final String BASE = "/api/v1/mission-templates";
  private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID ID = UUID.fromString("99999999-8888-7777-6666-555555555555");

  /** Lists templates for the requested owner and loads their planning details by id. */
  @Test
  void readsOwnersListAndDetail() {
    try (BackendClientHarness backend = BackendClientHarness.start()) {
      var client = new MissionTemplateBackendClient(backend.backendApiClient());
      backend.answerJson(
          """
          [{"id":"%s","name":"Unit A"}]
          """
              .formatted(OWNER));
      assertThat(client.owners()).containsExactly(new MissionTemplateOwnerDto(OWNER, "Unit A"));
      backend.expect("GET", BASE + "/owning-org-units", null);

      backend.answerJson(
          """
          [{"id":"%s","owningOrgUnitId":"%s","name":"Patrol","version":4}]
          """
              .formatted(ID, OWNER));
      assertThat(client.list(OWNER))
          .containsExactly(new MissionTemplateSummaryDto(ID, OWNER, "Patrol", 4));
      backend.expect("GET", BASE + "?owningOrgUnitId=" + OWNER, null);

      backend.answerJson(template(4));
      assertThat(client.detail(ID).version()).isEqualTo(4);
      backend.expect("GET", BASE + "/" + ID, null);
    }
  }

  /** Carries the explicit owner on create and the client's revision on update and delete. */
  @Test
  void sendsOwnerAndExpectedRevisions() {
    try (BackendClientHarness backend = BackendClientHarness.start()) {
      var client = new MissionTemplateBackendClient(backend.backendApiClient());
      backend.answerJson(template(0));
      assertThat(
              client
                  .create(
                      new CreateMissionTemplateRequest(
                          OWNER, "Patrol", List.of(), List.of(), List.of()))
                  .id())
          .isEqualTo(ID);
      assertThat(backend.expect("POST", BASE).getBody().readUtf8())
          .contains("\"owningOrgUnitId\":\"" + OWNER + "\"");

      backend.answerJson(template(1));
      assertThat(
              client
                  .update(
                      ID,
                      new UpdateMissionTemplateRequest(
                          0L, "Patrol", List.of(), List.of(), List.of()))
                  .version())
          .isEqualTo(1);
      assertThat(backend.expect("PUT", BASE + "/" + ID).getBody().readUtf8())
          .contains("\"version\":0");

      backend.answerEmpty();
      client.delete(ID, 1);
      backend.expect("DELETE", BASE + "/" + ID + "?version=1", null);
    }
  }

  /** Sends a picked owner only when the mission create form has one. */
  @Test
  void readsApplicationChoicesAndPreviewInTheRequestedScope() {
    try (BackendClientHarness backend = BackendClientHarness.start()) {
      var client = new MissionTemplateBackendClient(backend.backendApiClient());
      String choices = "/api/v1/missions/template-choices";
      String preview = "/api/v1/missions/template-preview/" + ID;
      String owner = "?owningOrgUnitId=" + OWNER;

      backend.answerJson("[]");
      assertThat(client.choices(null)).isEmpty();
      backend.expect("GET", choices, null);

      backend.answerJson("[]");
      assertThat(client.choices(OWNER)).isEmpty();
      backend.expect("GET", choices + owner, null);

      backend.answerJson(template(3));
      assertThat(client.preview(ID, null).version()).isEqualTo(3);
      backend.expect("GET", preview, null);

      backend.answerJson(template(4));
      assertThat(client.preview(ID, OWNER).version()).isEqualTo(4);
      backend.expect("GET", preview + owner, null);
    }
  }

  private static String template(long version) {
    return """
    {"id":"%s","owningOrgUnitId":"%s","name":"Patrol","version":%d,
     "objectives":[],"steps":[],"units":[]}
    """
        .formatted(ID, OWNER, version);
  }
}
