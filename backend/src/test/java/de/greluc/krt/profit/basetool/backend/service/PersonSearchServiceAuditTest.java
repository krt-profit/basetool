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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonSearchHitDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * What the Personensuche writes into its audit row (REQ-SEC-060, REQ-AUDIT-001).
 *
 * <p>The rule this pins down is the kind a later refactor "tidies up": the payload carries the
 * term's <b>length</b> and never the term. A trail of every name an admin searched for would be a
 * second store of exactly the data the search exists to help remove — so the one field that must
 * never appear is the one the caller passes in.
 *
 * <p>It used to be asserted through the controller, against a mocked {@code AuditService}. That
 * moved here when the record call moved into the service, because the controller can no longer make
 * it: {@link AuditService#record} is {@code MANDATORY}-propagated and a request handler has no
 * transaction to satisfy it with.
 */
@ExtendWith(MockitoExtension.class)
class PersonSearchServiceAuditTest {

  private static final String TERM = "SomeVeryDistinctiveHandle";

  @Mock private AuditService auditService;

  // covers REQ-SEC-060 — the recorded payload carries the term's length, never the term
  @Test
  void recordSearch_recordsTheLengthAndNeverTheTerm() {
    PersonSearchService service = new PersonSearchService(auditService);

    service.recordSearch(
        TERM, new PersonSearchService.PersonSearchResult(List.of(hit(), hit()), true, List.of()));

    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(eq(AuditEventType.PERSON_SEARCH_PERFORMED), any(), any(), any(), details.capture());

    String payload = details.getValue().toString();
    assertThat(payload).as("the term is somebody's name").doesNotContain(TERM);
    // The length is the whole point: 25 characters, not the 25 characters.
    assertThat(payload).contains("termLength=" + TERM.length());
    assertThat(payload).contains("hits=2").contains("truncated=true");
  }

  // covers REQ-SEC-060 — the length is of the trimmed term, as the search itself uses it
  @Test
  void recordSearch_measuresTheTrimmedTerm() {
    PersonSearchService service = new PersonSearchService(auditService);

    service.recordSearch(
        "  " + TERM + "  ",
        new PersonSearchService.PersonSearchResult(List.of(), false, List.of()));

    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService).record(any(), any(), any(), any(), details.capture());

    assertThat(details.getValue().toString())
        .as("padding the box would otherwise change the recorded length")
        .contains("termLength=" + TERM.length());
  }

  /**
   * One arbitrary hit, since only the number of them reaches the payload.
   *
   * @return the hit
   */
  private static PersonSearchHitDto hit() {
    return new PersonSearchHitDto("MEMBER", "app_user", "username", null, "snippet", "label");
  }
}
