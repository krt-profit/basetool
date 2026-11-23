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

package de.greluc.krt.profit.basetool.backend.joborder.api.events;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationRevision;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The create, need-change and finished notices of a client's write carry the coded application
 * parameter, the application's display name captured when the notice is built; the web's carry
 * none, so they keep the type's own wording (REQ-NOTIF-028, REQ-ORDERS-078).
 */
class JobOrderApplicationNoticeTest {

  private static final UUID ORDER = UUID.randomUUID();
  private static final UUID ACTOR = UUID.randomUUID();
  private static final OrgUnitRef UNIT = new OrgUnitRef(UUID.randomUUID(), OrgUnitKind.SQUADRON);
  private static final NotificationRevision REVISION = new NotificationRevision(0L, 0L);

  @Test
  void aClientsCreateNamesTheApplication() {
    assertThat(created("VerseKit").renderParams())
        .containsEntry("viaCode", "CLIENT")
        .containsEntry("app", "VerseKit");
  }

  @Test
  void aWebCreateNamesNoApplication() {
    assertThat(created(null).renderParams()).doesNotContainKeys("viaCode", "app");
  }

  @Test
  void aClientsRequesterChangeNamesTheApplication() {
    assertNamesApplication(requesterChanged("VerseKit"));
    assertThat(requesterChanged(null).renderParams()).doesNotContainKeys("viaCode", "app");
  }

  @Test
  void aClientsResponsibleChangeNamesTheApplication() {
    assertNamesApplication(
        JobOrderNotices.needChanged(ORDER, 12, "Ada", Map.of(), REVISION, ACTOR, "VerseKit"));
    assertThat(
            JobOrderNotices.needChanged(ORDER, 12, "Ada", Map.of(), REVISION, ACTOR, null)
                .renderParams())
        .doesNotContainKeys("viaCode", "app");
  }

  @Test
  void aClientsCancelNamesTheApplicationBesideTheStatus() {
    NotificationEvent finished =
        JobOrderNotices.finished(
            ORDER, 12, "Ada", Map.of(), "ALP", "CANCELLED", Set.of(), ACTOR, "VerseKit");

    assertNamesApplication(finished);
    assertThat(finished.renderParams()).containsEntry("statusCode", "CANCELLED");
  }

  @Test
  void aWebCancelNamesNoApplication() {
    assertThat(
            JobOrderNotices.finished(
                    ORDER, 12, "Ada", Map.of(), "ALP", "CANCELLED", Set.of(), ACTOR, null)
                .renderParams())
        .doesNotContainKeys("viaCode", "app");
  }

  private static void assertNamesApplication(NotificationEvent event) {
    assertThat(event.renderParams())
        .containsEntry("viaCode", "CLIENT")
        .containsEntry("app", "VerseKit");
  }

  private static JobOrderCreatedEvent created(@Nullable String app) {
    return new JobOrderCreatedEvent(
        ORDER, 12, "Ada", UNIT, "ALP", UNIT, "AUEC", ACTOR, REVISION, app);
  }

  private static JobOrderUpdatedByRequesterEvent requesterChanged(@Nullable String app) {
    return new JobOrderUpdatedByRequesterEvent(
        ORDER, 12, "Ada", UNIT, "ALP", UNIT, "BRV", ACTOR, REVISION, app);
  }
}
