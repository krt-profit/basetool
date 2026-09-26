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

package de.greluc.krt.profit.basetool.backend.mapper;

import de.greluc.krt.profit.basetool.backend.model.P4kImportJob;
import de.greluc.krt.profit.basetool.backend.model.dto.P4kImportJobDto;
import de.greluc.krt.profit.basetool.backend.model.dto.P4kImportResultDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Converts {@link P4kImportJob} entities to {@link P4kImportJobDto}. Hand-written rather than a
 * MapStruct mapper on purpose: the job's {@code result_json} column is stored as plain JSON text
 * and must be <em>deserialized</em> back into a {@link P4kImportResultDto}, which MapStruct cannot
 * do, so the {@link ObjectMapper} is wired in by constructor and used in {@link #parseResult}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class P4kImportJobMapper {

  private final ObjectMapper objectMapper;

  /**
   * Maps one job entity to its boundary DTO, deserializing the stored result when the run has
   * succeeded.
   *
   * @param job the job entity
   * @return the DTO view
   */
  @NotNull
  public P4kImportJobDto toDto(@NotNull P4kImportJob job) {
    return new P4kImportJobDto(
        job.getId(),
        job.getKind(),
        job.getStatus(),
        job.isSeedNew(),
        job.getSourceFilename(),
        job.getFileSizeBytes(),
        job.getPreviewJobId(),
        parseResult(job.getResultJson()),
        job.getErrorMessage(),
        job.getCreatedAt(),
        job.getStartedAt(),
        job.getFinishedAt());
  }

  /**
   * Maps a list of job entities to DTOs, preserving order.
   *
   * @param jobs the job entities
   * @return the DTO views
   */
  @NotNull
  public List<P4kImportJobDto> toDtos(@NotNull List<P4kImportJob> jobs) {
    return jobs.stream().map(this::toDto).toList();
  }

  /**
   * Deserializes a stored {@code result_json} into a {@link P4kImportResultDto}; blank or
   * unparseable values yield {@code null}, the latter logged.
   *
   * @param resultJson the stored JSON, or {@code null}
   * @return the parsed result, or {@code null}
   */
  @Nullable
  private P4kImportResultDto parseResult(@Nullable String resultJson) {
    if (resultJson == null || resultJson.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(resultJson, P4kImportResultDto.class);
    } catch (JacksonException e) {
      log.warn("P4K import job: stored result_json could not be parsed — {}", e.getMessage());
      return null;
    }
  }
}
