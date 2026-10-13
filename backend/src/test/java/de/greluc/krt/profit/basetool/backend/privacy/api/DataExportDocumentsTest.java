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

package de.greluc.krt.profit.basetool.backend.privacy.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import tools.jackson.databind.json.JsonMapper;

/** Unit tests for {@link DataExportDocuments}: the indented JSON and the download filename. */
class DataExportDocumentsTest {

  private static final Instant GENERATED_AT = Instant.parse("2026-10-13T10:15:30Z");

  @Test
  void json_isIndentedOnePerLineAndStillTheSameDocument() throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", 7);
    row.put("note", "hello");
    DataExportService.DataExport export =
        new DataExportService.DataExport(
            UUID.randomUUID(),
            "Alice",
            GENERATED_AT,
            false,
            List.of("backups", "keycloakAccountRecord"),
            List.of(new DataExportService.ExportSection("profile", "ART_15", List.of(row), false)));
    JsonMapper mapper = JsonMapper.builder().build();

    String json = new String(DataExportDocuments.json(mapper, export), StandardCharsets.UTF_8);

    assertThat(json).contains("\n  \"subjectHandle\" : \"Alice\"");
    assertThat(json).contains("\n    \"backups\",\n    \"keycloakAccountRecord\"\n  ]");
    assertThat(json).doesNotContain("\r");
    assertThat(mapper.readTree(json)).isEqualTo(mapper.readTree(mapper.writeValueAsString(export)));
  }

  @Test
  void contentDisposition_namesTheFileAfterTheAccountAndTheDate() {
    ContentDisposition disposition =
        parse(DataExportDocuments.contentDisposition(export("Alice"), "pdf"));

    assertThat(disposition.getFilename()).isEqualTo("Datenexport Alice 26-10-13.pdf");
    assertThat(disposition.isAttachment()).isTrue();
  }

  @Test
  void contentDisposition_readsTheDateInTheOrganisationsZone() {
    DataExportService.DataExport lateEvening =
        new DataExportService.DataExport(
            UUID.randomUUID(),
            "Alice",
            Instant.parse("2026-12-31T23:30:00Z"),
            false,
            List.of(),
            List.of());

    ContentDisposition disposition =
        parse(DataExportDocuments.contentDisposition(lateEvening, "json"));

    assertThat(disposition.getFilename()).isEqualTo("Datenexport Alice 27-01-01.json");
  }

  @Test
  void contentDisposition_keepsUmlautsAndReplacesWhatAFilesystemRefuses() {
    ContentDisposition disposition =
        parse(DataExportDocuments.contentDisposition(export("Jörg/..\\\"x\"*?\n"), "pdf"));

    assertThat(disposition.getFilename()).isEqualTo("Datenexport Jörg_..__x____ 26-10-13.pdf");
  }

  @Test
  void contentDisposition_fallsBackWhenTheNameIsBlankOrAbsent() {
    assertThat(parse(DataExportDocuments.contentDisposition(export(null), "pdf")).getFilename())
        .isEqualTo("Datenexport Konto 26-10-13.pdf");
    assertThat(parse(DataExportDocuments.contentDisposition(export("  . "), "pdf")).getFilename())
        .isEqualTo("Datenexport Konto 26-10-13.pdf");
  }

  @Test
  void contentDisposition_capsAVeryLongName() {
    String filename =
        parse(DataExportDocuments.contentDisposition(export("x".repeat(500)), "pdf")).getFilename();

    assertThat(filename).isEqualTo("Datenexport " + "x".repeat(80) + " 26-10-13.pdf");
  }

  private static DataExportService.DataExport export(String handle) {
    return new DataExportService.DataExport(
        UUID.randomUUID(), handle, GENERATED_AT, false, List.of(), List.of());
  }

  private static ContentDisposition parse(String header) {
    return ContentDisposition.parse(header);
  }
}
