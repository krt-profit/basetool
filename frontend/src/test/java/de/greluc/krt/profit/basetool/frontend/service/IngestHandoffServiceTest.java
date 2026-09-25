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

package de.greluc.krt.profit.basetool.frontend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintImportPreviewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.HandoffKind;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryImportDraftDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.StagedHandoff;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for the one-click ingest handoff reader: single-use consume, per-subject scoping, kind
 * matching and graceful degradation (REQ-INGEST-003, REQ-INGEST-004).
 */
class IngestHandoffServiceTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> ops = mock(ValueOperations.class);

  private StringRedisTemplate template;
  private IngestHandoffService service;

  @BeforeEach
  void setUp() {
    template = mock(StringRedisTemplate.class);
    when(template.opsForValue()).thenReturn(ops);
    service = new IngestHandoffService(template);
  }

  private static String staged(HandoffKind kind, String draftJson) {
    return MAPPER.writeValueAsString(new StagedHandoff(kind, draftJson));
  }

  @Test
  void shouldConsumeRefineryDraft() {
    when(ops.getAndDelete("ingest:handoff:user-1:abc123"))
        .thenReturn(
            staged(
                HandoffKind.REFINERY, "{\"goodsMatched\":2,\"goodsTotal\":3,\"rowsSkipped\":1}"));

    Optional<RefineryImportDraftDto> result =
        service.consume("user-1", "abc123", HandoffKind.REFINERY, RefineryImportDraftDto.class);

    assertThat(result).isPresent();
    assertThat(result.get().goodsMatched()).isEqualTo(2);
    assertThat(result.get().goodsTotal()).isEqualTo(3);
  }

  @Test
  void shouldConsumeBlueprintPreview() {
    when(ops.getAndDelete("ingest:handoff:user-1:bp1"))
        .thenReturn(
            staged(
                HandoffKind.BLUEPRINT,
                "{\"total\":5,\"matched\":3,\"matchedByAlias\":0,\"suggested\":1,"
                    + "\"unmatched\":1,\"alreadyOwned\":0,\"entries\":[]}"));

    Optional<BlueprintImportPreviewDto> result =
        service.consume("user-1", "bp1", HandoffKind.BLUEPRINT, BlueprintImportPreviewDto.class);

    assertThat(result).isPresent();
    assertThat(result.get().total()).isEqualTo(5);
  }

  @Test
  void shouldReturnEmptyWhenNothingStaged() {
    when(ops.getAndDelete(anyString())).thenReturn(null);

    assertThat(service.consume("user-1", "abc", HandoffKind.REFINERY, RefineryImportDraftDto.class))
        .isEmpty();
  }

  @Test
  void shouldReturnEmptyWhenKindMismatch() {
    when(ops.getAndDelete("ingest:handoff:user-1:abc"))
        .thenReturn(staged(HandoffKind.BLUEPRINT, "{\"total\":1}"));

    assertThat(service.consume("user-1", "abc", HandoffKind.REFINERY, RefineryImportDraftDto.class))
        .isEmpty();
  }

  @Test
  void shouldReturnEmptyOnMalformedValue() {
    when(ops.getAndDelete("ingest:handoff:user-1:abc")).thenReturn("not-json");

    assertThat(service.consume("user-1", "abc", HandoffKind.REFINERY, RefineryImportDraftDto.class))
        .isEmpty();
  }

  @Test
  void shouldRejectMalformedHandoffIdWithoutTouchingRedis() {
    assertThat(
            service.consume(
                "user-1", "../evil", HandoffKind.REFINERY, RefineryImportDraftDto.class))
        .isEmpty();
    verify(ops, never()).getAndDelete(anyString());
  }

  @Test
  void shouldReturnEmptyForBlankSubject() {
    assertThat(service.consume("", "abc", HandoffKind.REFINERY, RefineryImportDraftDto.class))
        .isEmpty();
    verify(ops, never()).getAndDelete(anyString());
  }
}
