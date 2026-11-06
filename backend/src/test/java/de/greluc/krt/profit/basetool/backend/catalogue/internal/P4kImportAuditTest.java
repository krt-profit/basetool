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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDomain;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventDto;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/** A P4K APPLY records exactly one {@code CATALOGUE} event for its run (REQ-P4K-046). */
@SpringBootTest
@Transactional
class P4kImportAuditTest {

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private AuditService auditService;

  /**
   * The {@code CATALOGUE_P4K_APPLIED} events recorded so far.
   *
   * @return the events, newest first
   */
  private List<AuditEventDto> appliedEvents() {
    return auditService
        .getEvents(
            AuditDomain.CATALOGUE,
            null,
            null,
            null,
            AuditEventType.CATALOGUE_P4K_APPLIED,
            null,
            PageRequest.of(0, 50))
        .getContent();
  }

  @Test
  void anApply_recordsOneEventWithTheRunTheBuildAndTheCountsPerKind() {
    P4kImportRuns runs = new P4kImportRuns(service, jobRepository);
    int before = appliedEvents().size();

    P4kImportResultDto result = runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    List<AuditEventDto> events = appliedEvents();
    assertThat(events).hasSize(before + 1);
    AuditEventDto event =
        events.stream().filter(e -> result.runId().equals(e.subjectId())).findFirst().orElseThrow();
    assertThat(event.subjectLabel()).isEqualTo(result.build().version());
    assertThat(event.targetUserId()).isNull();
    assertThat(event.details())
        .startsWith(
            "branch="
                + result.build().branch()
                + " change="
                + result.build().changeNumber()
                + " createItemsAndShips=false manufacturers=[m=")
        .contains(" items=[m=" + result.items().matched() + ",")
        .contains(" ships=[", " commodities=[", " blueprints=[");
  }

  @Test
  void aPreview_recordsNoEvent() {
    P4kImportRuns runs = new P4kImportRuns(service, jobRepository);
    int before = appliedEvents().size();

    runs.preview(P4kTestCatalogues.fixtureBytes());

    assertThat(appliedEvents()).hasSize(before);
  }
}
