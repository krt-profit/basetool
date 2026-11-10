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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.kernel.ReportGenerationException;
import java.io.EOFException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Failed snapshots and renderers release private data without recording a successful export. */
class BankReportSpoolTest {
  private final BankExportProperties properties =
      new BankExportProperties(Path.of(System.getProperty("java.io.tmpdir")));

  @Test
  void theExportDirectoryIsPrivateAndCloseIsIdempotent() throws Exception {
    BankReportSpool spool = BankReportSpool.create(properties);
    Path directory = spool.file("rows").getParent();
    try {
      if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
        assertThat(Files.getPosixFilePermissions(directory))
            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
      }
      Files.write(spool.file("rows"), new byte[] {1});
      spool.close();
      spool.close();
      assertThat(directory).doesNotExist();
    } finally {
      spool.close();
    }
  }

  @Test
  void aTruncatedFrameHeaderIsAnErrorRatherThanAnIncompleteSuccessfulReport() throws Exception {
    try (BankReportSpool spool = BankReportSpool.create(properties)) {
      Files.write(spool.file("rows"), new byte[] {0, 0, 0});
      assertThatThrownBy(() ->
              BankReportSpool.read(
                  spool.file("rows"),
                  mock(ObjectMapper.class),
                  BankReportSpool.Lines.class,
                  _ -> {}))
          .isInstanceOf(ReportGenerationException.class)
          .hasCauseInstanceOf(EOFException.class);
    }
  }

  @Test
  void failedRenderingRemovesTheSpoolAndRecordsNoExportSuccess() {
    BankReportSnapshotReader reader = mock(BankReportSnapshotReader.class);
    BankReportRenderer renderer = mock(BankReportRenderer.class);
    BankAuditService audit = mock(BankAuditService.class);
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    UUID accountId = UUID.randomUUID();
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    Instant to = from.plusSeconds(1);
    BankReportSpool.Section section =
        new BankReportSpool.Section(
            new BankReportSpool.Account(accountId, "SYNTHETIC", "Synthetic"),
            "rows-0",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            0);
    when(reader.statement(any(), eq(accountId), eq(from), eq(to), eq(false))).thenReturn(section);
    IllegalStateException failed = new IllegalStateException("synthetic renderer failure");
    doThrow(failed)
        .when(renderer)
        .statement(any(), eq(section), eq(from), eq(to), eq(null), eq(false));
    BankStatementReportService service =
        new BankStatementReportService(reader, renderer, properties, audit, transactions);
    assertThatThrownBy(() -> service.generateStatement(accountId, from, to, null)).isSameAs(failed);
    ArgumentCaptor<BankReportSpool> captured = ArgumentCaptor.forClass(BankReportSpool.class);
    verify(reader).statement(captured.capture(), eq(accountId), eq(from), eq(to), eq(false));
    assertThat(captured.getValue().file("rows-0").getParent()).doesNotExist();
    verifyNoInteractions(audit, transactions);
  }
}
