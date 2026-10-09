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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeBlueprintEnvelopeReader;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeDraftService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBlueprintDraftDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportPreviewDto;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins how the blueprint upload preview routes a file: the {@code basetool.blueprints} envelope of
 * the exchange contract through the exchange's blueprint draft (REQ-XCH-019), refusing another
 * major format version and a broken envelope, and every other shape through the export parser
 * (REQ-INV-014).
 */
class BlueprintUploadPreviewServiceTest {

  private static final UUID OWNER = UUID.randomUUID();

  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  private final BlueprintImportService importService = mock(BlueprintImportService.class);

  private final ExchangeDraftService draftService = mock(ExchangeDraftService.class);

  private final BlueprintImportPreviewDto parsed = mock(BlueprintImportPreviewDto.class);

  private final BlueprintImportPreviewDto drafted = mock(BlueprintImportPreviewDto.class);

  private BlueprintUploadPreviewService service;

  @BeforeEach
  void setUp() {
    when(importService.previewImport(eq(OWNER), any())).thenReturn(parsed);
    when(draftService.blueprints(eq(OWNER), any())).thenReturn(drafted);
    service =
        new BlueprintUploadPreviewService(
            objectMapper,
            importService,
            new ExchangeBlueprintEnvelopeReader(objectMapper, validator, draftService));
  }

  @Test
  void anEmptyFileGoesToTheExportParser() {
    assertThat(service.preview(OWNER, file(""))).isSameAs(parsed);
    verifyNoInteractions(draftService);
  }

  @Test
  void aFileThatIsNoJsonGoesToTheExportParser() {
    assertThat(service.preview(OWNER, file("not json {"))).isSameAs(parsed);
    verifyNoInteractions(draftService);
  }

  @Test
  void aJsonObjectOfAnotherFormatGoesToTheExportParser() {
    assertThat(service.preview(OWNER, file("{\"format\":\"something.else\",\"items\":[]}")))
        .isSameAs(parsed);
    assertThat(service.preview(OWNER, file("[1,2]"))).isSameAs(parsed);
    verifyNoInteractions(draftService);
  }

  @Test
  void anEnvelopeOfAnotherMajorVersionIsRefused() {
    assertThatThrownBy(
            () ->
                service.preview(
                    OWNER,
                    file(
                        "{\"format\":\"basetool.blueprints\",\"formatVersion\":\"2.0\","
                            + "\"items\":[]}")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("error.personalBlueprint.formatVersionUnsupported");
    verifyNoInteractions(draftService, importService);
  }

  @Test
  void aBrokenEnvelopeIsRefused() {
    for (String body :
        new String[] {
          "{\"format\":\"basetool.blueprints\",\"formatVersion\":\"1.0\"}",
          "{\"format\":\"basetool.blueprints\",\"formatVersion\":\"v1\",\"items\":[]}",
          "{\"format\":\"basetool.blueprints\",\"items\":[null]}",
          "{\"format\":\"basetool.blueprints\",\"items\":\"x\"}"
        }) {
      assertThatThrownBy(() -> service.preview(OWNER, file(body)))
          .as(body)
          .isInstanceOf(BadRequestException.class)
          .hasMessage("error.personalBlueprint.import.invalidEnvelope");
    }
    verifyNoInteractions(draftService, importService);
  }

  @Test
  void aValidEnvelopeIsPreviewedAsTheExchangeDraftDoes() {
    BlueprintImportPreviewDto result =
        service.preview(
            OWNER,
            file(
                "{\"format\":\"basetool.blueprints\",\"formatVersion\":\"1.7\",\"extra\":1,"
                    + "\"items\":[{\"ref\":{\"bt\":\"abc\"},\"acquiredAt\":"
                    + "\"2026-09-20T10:00:00Z\"},{\"ref\":{\"name\":\"Pistol\"}}]}"));

    assertThat(result).isSameAs(drafted);
    ArgumentCaptor<ExchangeBlueprintDraftDto> envelope =
        ArgumentCaptor.forClass(ExchangeBlueprintDraftDto.class);
    verify(draftService).blueprints(eq(OWNER), envelope.capture());
    assertThat(envelope.getValue().formatVersion()).isEqualTo("1.7");
    assertThat(envelope.getValue().items()).hasSize(2);
    verifyNoInteractions(importService);
  }

  @Test
  void anEnvelopeWithoutAVersionIsPreviewed() {
    assertThat(service.preview(OWNER, file("{\"format\":\"basetool.blueprints\",\"items\":[]}")))
        .isSameAs(drafted);
  }

  private static MockMultipartFile file(String body) {
    return new MockMultipartFile(
        "file", "blueprints.json", "application/json", body.getBytes(StandardCharsets.UTF_8));
  }
}
