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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.model.dto.ImportIssueCode;
import de.greluc.krt.profit.basetool.frontend.model.dto.ImportIssueDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ImportIssueSeverity;
import de.greluc.krt.profit.basetool.frontend.model.dto.ImportSuggestionDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;

/**
 * Verifies that the refinery-import flash map survives the Redis session serializer (covers
 * REQ-REFINERY-015): its keys come back as strings, so the proxy keys by decimal string, and the
 * issue records stay intact.
 */
class RedisSessionImportFlashRoundTripTest {

  private final GenericJacksonJsonRedisSerializer serializer =
      new GenericJacksonJsonRedisSerializer(
          RedisSessionConfig.buildSessionJsonMapper(getClass().getClassLoader()));

  @Test
  void importFlashPayloadSurvivesTheJsonSessionRoundTripWithStringKeys() {
    UUID suggestionId = UUID.randomUUID();
    ImportIssueDto issue =
        new ImportIssueDto(
            "goods[1].inputMaterial",
            "E2E IMPRT MATERAIL",
            ImportIssueCode.UNMATCHED_MATERIAL,
            ImportIssueSeverity.WARNING,
            null,
            List.of(new ImportSuggestionDto(suggestionId, "E2E Import Material", 0.84)));
    Map<String, List<ImportIssueDto>> rowIssues = new LinkedHashMap<>();
    rowIssues.put("1", List.of(issue));
    Map<String, Object> flashShape = new HashMap<>();
    flashShape.put("importRowIssues", rowIssues);
    flashShape.put("importIssues", List.of(issue));

    Object back = serializer.deserialize(serializer.serialize(flashShape));

    assertThat(back).isInstanceOf(Map.class);
    Map<?, ?> backFlash = (Map<?, ?>) back;
    Map<?, ?> backRows = (Map<?, ?>) backFlash.get("importRowIssues");
    assertThat(new ArrayList<Object>(backRows.keySet()))
        .as("string-form index keys must survive the JSON session round trip unchanged")
        .containsExactly("1");
    Object backIssue = ((List<?>) backRows.get("1")).getFirst();
    assertThat(backIssue)
        .as("issue records must deserialize back into the typed DTO, not a generic map")
        .isInstanceOf(ImportIssueDto.class);
    ImportIssueDto typed = (ImportIssueDto) backIssue;
    assertThat(typed.code()).isEqualTo(ImportIssueCode.UNMATCHED_MATERIAL);
    assertThat(typed.severity()).isEqualTo(ImportIssueSeverity.WARNING);
    assertThat(typed.suggestions()).hasSize(1);
    assertThat(typed.suggestions().getFirst().id()).isEqualTo(suggestionId);
    assertThat(typed.suggestions().getFirst().score()).isEqualTo(0.84);
  }

  @Test
  void integerKeyedMapComesBackStringKeyed() {
    Map<Integer, String> intKeyed = new LinkedHashMap<>();
    intKeyed.put(1, "flagged");

    Object back = serializer.deserialize(serializer.serialize(intKeyed));

    assertThat(new ArrayList<Object>(((Map<?, ?>) back).keySet()))
        .as("JSON map keys are strings — Integer keys cannot survive the session round trip")
        .containsExactly("1");
  }
}
