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
 * Verifies that the Personensuche audit row records the search term's length and never the term
 * itself (REQ-SEC-060, REQ-AUDIT-001).
 */
@ExtendWith(MockitoExtension.class)
class PersonSearchServiceAuditTest {

  private static final String TERM = "SomeVeryDistinctiveHandle";

  @Mock private AuditService auditService;

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
    assertThat(payload).contains("termLength=" + TERM.length());
    assertThat(payload).contains("hits=2").contains("truncated=true");
  }

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
