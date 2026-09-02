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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.AuditDomain;
import de.greluc.krt.profit.basetool.backend.model.AuditEvent;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.dto.AuditEventDto;
import de.greluc.krt.profit.basetool.backend.repository.AuditEventRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * Integration tests for the generic activity-audit query against the real Testcontainers PostgreSQL
 * — the path the mocked {@code AuditServiceTest} cannot cover (REQ-AUDIT-001). Pins that {@code
 * findFiltered} runs without a "could not determine data type of parameter" failure when filters
 * are absent (all-null) and when populated, the exact calls the admin viewer makes; the {@code
 * (CAST(:param AS type) IS NULL OR ...)} pattern is what makes that work on PostgreSQL. Also pins
 * the MANDATORY-propagation invariant: {@code record()} outside a transaction throws (no silent
 * gaps).
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditQueryIntegrationTest {

  @Autowired private AuditService auditService;
  @Autowired private AuditEventRepository auditEventRepository;

  @Test
  void getEvents_withAllNullFilters_runsAndReturnsAPage() {
    // Given an audit row exists for the queried domain.
    seedInventoryAuditRow();
    PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "occurredAt"));

    // When — every optional filter absent, exactly the admin viewer's default call.
    Page<AuditEventDto> page =
        auditService.getEvents(AuditDomain.INVENTORY, null, null, null, null, null, pageable);

    // Then it does not throw (the CAST null-guards hold on PostgreSQL) and yields the seeded event.
    assertNotNull(page);
    assertTrue(page.getTotalElements() >= 1, "the seeded inventory event produced an audit row");
  }

  @Test
  void getEvents_withEveryFilterSet_runsWithoutTypeInferenceError() {
    // Given
    seedInventoryAuditRow();
    PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "occurredAt"));

    // When — every filter populated (the other branch of each CAST guard).
    Page<AuditEventDto> page =
        auditService.getEvents(
            AuditDomain.INVENTORY,
            Instant.now().minus(1, ChronoUnit.DAYS),
            Instant.now().plus(1, ChronoUnit.DAYS),
            UUID.randomUUID(),
            AuditEventType.INVENTORY_ITEM_CREATED,
            "basetool-android",
            pageable);

    // Then the query executes (the random actor id simply matches nothing).
    assertNotNull(page);
  }

  @Test
  void record_outsideTransaction_throwsBecauseMandatory() {
    // REQ-AUDIT-001 headline guarantee: record() is @Transactional(propagation = MANDATORY), so an
    // audit write can only happen inside the business transaction (no silent gaps). Calling it with
    // no active transaction must fail fast — a refactor to REQUIRED would silently reintroduce
    // gaps.
    assertThrows(
        IllegalTransactionStateException.class,
        () ->
            auditService.record(
                AuditEventType.INVENTORY_ITEM_CREATED, null, "no-tx", null, "should not persist"));
  }

  @Test
  void getEvents_clientFilter_selectsOnlyThatClientsRows() {
    // REQ-AUDIT-005 / GHSA-2vq5-8p8w-5r64: the whole point of the column is that a reviewer can
    // separate two clients' rows. Two rows differing ONLY in their client -- same domain, same
    // event type, same actor -- so a filter that silently did nothing would still look right on a
    // one-row fixture and fails here.
    seedInventoryAuditRowFrom("basetool-frontend");
    seedInventoryAuditRowFrom("basetool-android");
    PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "occurredAt"));

    // When
    Page<AuditEventDto> fromApp =
        auditService.getEvents(
            AuditDomain.INVENTORY, null, null, null, null, "basetool-android", pageable);

    // Then only the app's row comes back, and it carries the label the filter selected on.
    assertEquals(1, fromApp.getTotalElements());
    assertEquals("basetool-android", fromApp.getContent().getFirst().clientId());
  }

  @Test
  void getEvents_blankClientFilter_meansNoFilterRatherThanNoMatches() {
    // The viewer's "all clients" option submits the select's empty value rather than omitting the
    // parameter. Passed through it would match rows whose client_id is literally '' -- none -- and
    // read to the admin as "this area has no events" instead of as "no filter".
    seedInventoryAuditRowFrom("basetool-frontend");
    PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "occurredAt"));

    // When
    Page<AuditEventDto> unfiltered =
        auditService.getEvents(AuditDomain.INVENTORY, null, null, null, null, "  ", pageable);

    // Then
    assertTrue(unfiltered.getTotalElements() >= 1, "a blank client filter must not exclude rows");
  }

  private void seedInventoryAuditRow() {
    seedInventoryAuditRowFrom(null);
  }

  /**
   * Persists one inventory audit row attributed to the given client.
   *
   * @param clientId the bounded client label, or {@code null} for a pre-V237-shaped row
   */
  private void seedInventoryAuditRowFrom(String clientId) {
    auditEventRepository.save(
        AuditEvent.builder()
            .occurredAt(Instant.now())
            .domain(AuditDomain.INVENTORY)
            .eventType(AuditEventType.INVENTORY_ITEM_CREATED)
            .actorHandle("integration-test")
            .subjectLabel("Quantanium @ Port Olisar")
            .details("qty=5.0")
            .clientId(clientId)
            .build());
  }
}
