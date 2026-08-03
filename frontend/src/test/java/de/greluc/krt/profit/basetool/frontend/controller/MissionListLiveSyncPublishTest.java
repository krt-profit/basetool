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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncLocalBus;
import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncTopicClass;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * Live multi-user sync for the {@code /missions} list (#1235, REQ-FE-015, ADR-0094).
 *
 * <p>The mission surface publishes <b>server-side</b> because create, core update and delete all
 * redirect: a client broadcast issued just before that navigation races the socket teardown. These
 * tests pin the delete path — the one core mutation whose handler takes no bound form — plus the
 * registry consistency of the topic and section strings the controller hardcodes.
 *
 * <p>The per-mission {@code mission:&#123;id&#125;} detail room is a <em>different</em> class and
 * is covered by the mission-detail tests; what matters here is that the two never collapse into
 * one.
 */
class MissionListLiveSyncPublishTest {

  /** The list section of the global {@code missions} room. */
  private static final List<String> LIST = List.of("list");

  private BackendApiClient backendApiClient;
  private LiveSyncLocalBus liveSyncLocalBus;
  private MissionWriteController controller;
  private RedirectAttributes redirectAttributes;

  @BeforeEach
  void setUp() {
    backendApiClient = mock(BackendApiClient.class);
    liveSyncLocalBus = mock(LiveSyncLocalBus.class);
    controller =
        new MissionWriteController(
            backendApiClient,
            mock(MessageSource.class),
            mock(MissionPageController.class),
            mock(FrontendAuthHelperService.class),
            liveSyncLocalBus);
    redirectAttributes = new RedirectAttributesModelMap();
  }

  @Test
  void missionsListTopic_isDistinctFromTheMissionDetailRoom() {
    // The `mission`/`missions` stem repeats the `order`/`orders` shape. If the two ever collapsed
    // onto one class, mission-detail presence frames (pseudonymous ids + callsigns) would be
    // relayed into a room the list page joins with no presence gate at all.
    assertThat(LiveSyncTopicClass.MISSIONS_LIST.prefix()).isEqualTo("missions");
    assertThat(LiveSyncTopicClass.MISSIONS_LIST.allowedSections()).containsExactlyElementsOf(LIST);
    assertThat(LiveSyncTopicClass.MISSIONS_LIST.presenceEnabled()).isFalse();
    assertThat(LiveSyncTopicClass.MISSION.prefix()).isEqualTo("mission");
    assertThat(LiveSyncTopicClass.MISSION.presenceEnabled()).isTrue();
    assertThat(LiveSyncTopicClass.MISSIONS_LIST.metricLabel())
        .as("distinct topic_class series on the ops dashboard (REQ-OBS-011)")
        .isNotEqualTo(LiveSyncTopicClass.MISSION.metricLabel());
  }

  @Test
  void deleteMission_publishesTheListSection() {
    String view = controller.deleteMission(UUID.randomUUID(), redirectAttributes);

    assertThat(view).isEqualTo("redirect:/missions");
    verify(liveSyncLocalBus).publish("missions", LIST);
  }

  @Test
  void deleteMission_onBackendFailure_doesNotPublish() {
    // The mission is still there; telling peers otherwise makes every open list re-fetch for
    // nothing and — worse — reads as a successful delete in the relay metrics.
    UUID id = UUID.randomUUID();
    doThrow(new RuntimeException("backend down")).when(backendApiClient).delete(anyString(), any());

    String view = controller.deleteMission(id, redirectAttributes);

    assertThat(view).isEqualTo("redirect:/missions/" + id);
    verify(liveSyncLocalBus, never()).publish(anyString(), any());
  }
}
