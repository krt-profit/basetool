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

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link DataExportDocuments}: the indented RFC 8259 JSON (REQ-API-027) and the
 * download filename.
 */
class DataExportDocumentsTest {

  private static final Instant GENERATED_AT = Instant.parse("2026-10-13T10:15:30Z");

  private static final DataExportInformation NO_INFORMATION =
      new DataExportInformation(List.of(), Map.of(), Map.of(), List.of(), "", "", "", "", Map.of());

  private static DataExportService.ExportSection section(Map<String, Object> row) {
    return new DataExportService.ExportSection(
        "profile", "ART_15", "ACCOUNT", "ACCOUNT_LIFETIME", List.of("MEMBER"), List.of(row), false);
  }

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
            NO_INFORMATION,
            List.of(section(row)));
    JsonMapper mapper = JsonMapper.builder().build();

    String json = new String(DataExportDocuments.json(mapper, export), StandardCharsets.UTF_8);

    assertThat(json).contains("\n  \"subjectHandle\" : \"Alice\"");
    assertThat(json).contains("\n    \"backups\",\n    \"keycloakAccountRecord\"\n  ]");
    assertThat(json).doesNotContain("\r");
    assertThat(mapper.readTree(json)).isEqualTo(mapper.readTree(mapper.writeValueAsString(export)));
  }

  @Test
  void json_isRfc8259Utf8WithoutBomUniqueNamesAndOnlyFiniteNumbers() throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("ratio", Double.NaN);
    row.put("name", "Jörg – €");
    DataExportService.DataExport export =
        new DataExportService.DataExport(
            UUID.randomUUID(),
            "Jörg",
            GENERATED_AT,
            false,
            List.of(),
            NO_INFORMATION,
            List.of(section(row)));

    byte[] json = DataExportDocuments.json(JsonMapper.builder().build(), export);

    assertThat(json[0]).as("no byte order mark (RFC 8259 8.1)").isEqualTo((byte) '{');
    String text =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(json))
            .toString();
    JsonMapper strict =
        JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    assertThat(strict.readTree(json).isObject()).isTrue();
    assertThat(text)
        .contains("\"ratio\" : \"NaN\"")
        .contains("\"name\" : \"Jörg – €\"")
        .contains("\"generatedAt\" : \"2026-10-13T10:15:30Z\"");
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
            NO_INFORMATION,
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
        UUID.randomUUID(), handle, GENERATED_AT, false, List.of(), NO_INFORMATION, List.of());
  }

  private static ContentDisposition parse(String header) {
    return ContentDisposition.parse(header);
  }
}
