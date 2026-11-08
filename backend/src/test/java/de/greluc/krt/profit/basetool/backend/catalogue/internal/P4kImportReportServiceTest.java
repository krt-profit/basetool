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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.json.JsonMapper;

/** A preview's report is unpacked once and paged from the decoded rows (REQ-P4K-015). */
class P4kImportReportServiceTest {

  private static final UUID JOB = UUID.fromString("00000000-0000-4000-8000-0000000000b1");

  private P4kImportJobService jobService;
  private P4kPreviewReportCodec codec;
  private P4kImportReportService service;

  @BeforeEach
  void setUp() {
    jobService = mock(P4kImportJobService.class);
    codec = new P4kPreviewReportCodec(JsonMapper.builder().build());
    service =
        new P4kImportReportService(
            mock(P4kImportRunRepository.class),
            mock(P4kImportChangeRepository.class),
            jobService,
            codec);
  }

  private static P4kImportChangeDto unmatched(int i) {
    return new P4kImportChangeDto(
        P4kKind.ITEM,
        P4kChangeType.UNMATCHED,
        UUID.randomUUID(),
        null,
        null,
        null,
        "Class_" + i,
        "Item " + i,
        null,
        false);
  }

  @Test
  void pagingAPreview_unpacksItsReportOnce() {
    List<P4kImportChangeDto> rows =
        IntStream.range(0, 30).mapToObj(P4kImportReportServiceTest::unmatched).toList();
    when(jobService.loadPreviewReport(JOB)).thenReturn(Optional.of(codec.encode(rows)));

    var first = service.previewChanges(JOB, null, null, PageRequest.of(0, 25));
    var second = service.previewChanges(JOB, P4kKind.ITEM, null, PageRequest.of(1, 25));

    assertThat(first.getContent()).isEqualTo(rows.subList(0, 25));
    assertThat(second.getContent()).isEqualTo(rows.subList(25, 30));
    assertThat(second.getTotalElements()).isEqualTo(30);
    verify(jobService, times(1)).loadPreviewReport(JOB);
  }

  @Test
  void aJobWithoutAPreviewReport_isNotFound_andIsAskedAgainNextTime() {
    when(jobService.loadPreviewReport(JOB)).thenReturn(Optional.empty());

    for (int attempt = 0; attempt < 2; attempt++) {
      assertThatThrownBy(() -> service.previewChanges(JOB, null, null, PageRequest.of(0, 25)))
          .isInstanceOf(NotFoundException.class);
    }
    verify(jobService, times(2)).loadPreviewReport(JOB);
  }
}
