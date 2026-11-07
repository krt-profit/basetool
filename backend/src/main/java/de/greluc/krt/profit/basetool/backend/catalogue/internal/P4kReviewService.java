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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kKeyedRow;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The P4K review list (REQ-P4K-016, ADR-0336): lists the open cases and resolves one as keep or
 * hide. A hide stamps {@code p4k_hidden_at} on the row, which drops it out of every picker like the
 * sweep's stamp (REQ-P4K-013), and no later match lifts it. Each resolution stores the admin and
 * the time and records one {@code CATALOGUE} audit event (REQ-P4K-046).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class P4kReviewService {

  private final P4kReviewCaseRepository caseRepository;
  private final P4kImportChangeRepository changeRepository;
  private final EntityManager entityManager;
  private final AuditRecorder auditRecorder;

  /**
   * Pages the open cases, oldest first.
   *
   * @param pageable the page
   * @return the page of cases with their record and row names
   */
  @NotNull
  public Page<P4kReviewCaseDto> openCases(@NotNull Pageable pageable) {
    Page<P4kReviewCase> page =
        caseRepository.findByStatusOrderByCreatedAtAscIdAsc(P4kReviewStatus.OPEN, pageable);
    Map<UUID, String> rowNames = rowNames(page.getContent());
    Map<RecordKey, P4kImportChange> records = reviewRows(page.getContent());
    return page.map(c -> toDto(c, rowNames, records));
  }

  /**
   * Resolves an open case as keep or hide.
   *
   * @param caseId the case
   * @param resolution keep or hide
   * @param version the case version the admin saw
   * @param adminId the resolving admin
   * @return the resolved case
   * @throws NotFoundException when no case has the id
   * @throws BadRequestException when the case is already resolved or the resolution is {@code
   *     RESOLVED}, which only a run sets
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   */
  @Transactional
  @NotNull
  public P4kReviewCaseDto resolve(
      @NotNull UUID caseId,
      @NotNull P4kReviewResolution resolution,
      long version,
      @NotNull UUID adminId) {
    AuditEventType auditType =
        switch (resolution) {
          case KEEP -> AuditEventType.CATALOGUE_P4K_REVIEW_KEPT;
          case HIDE -> AuditEventType.CATALOGUE_P4K_REVIEW_HIDDEN;
          case RESOLVED ->
              throw new BadRequestException("A review case is resolved as keep or hide.");
        };
    P4kReviewCase reviewCase =
        Entities.require(
            caseRepository.findById(caseId), () -> "P4K review case " + caseId + " was not found.");
    OptimisticLock.checkRequired(reviewCase.getVersion(), version, P4kReviewCase.class, caseId);
    if (reviewCase.getStatus() != P4kReviewStatus.OPEN) {
      throw new BadRequestException("The review case is already resolved.");
    }
    Instant now = Instant.now();
    reviewCase.setStatus(P4kReviewStatus.REVIEWED);
    reviewCase.setResolution(resolution);
    reviewCase.setResolvedBy(adminId);
    reviewCase.setResolvedAt(now);
    if (resolution == P4kReviewResolution.HIDE && reviewCase.getRowId() != null) {
      hide(reviewCase.getKind(), reviewCase.getRowId(), now);
    }
    caseRepository.saveAndFlush(reviewCase);
    auditRecorder.record(
        auditType,
        reviewCase.getId(),
        reviewCase.getGuid().toString(),
        null,
        AuditDetails.of("kind", reviewCase.getKind().name())
            .with("reason", reviewCase.getReason().name())
            .with("rowId", reviewCase.getRowId()));
    List<P4kReviewCase> resolved = List.of(reviewCase);
    return toDto(reviewCase, rowNames(resolved), reviewRows(resolved));
  }

  /**
   * Hides a row; a row that no longer exists leaves nothing to hide.
   *
   * @param kind the kind
   * @param rowId the row
   * @param now the time of the hide
   */
  private void hide(@NotNull P4kKind kind, @NotNull UUID rowId, @NotNull Instant now) {
    entityManager
        .createQuery(P4kSweepTarget.of(kind).rowsByIdQuery(), P4kKeyedRow.class)
        .setParameter("ids", List.of(rowId))
        .getResultList()
        .forEach(row -> row.setP4kHiddenAt(now));
  }

  /**
   * Reads the names of the rows a page of cases names, one query per kind.
   *
   * @param cases the cases
   * @return the row names by row id
   */
  @NotNull
  private Map<UUID, String> rowNames(@NotNull List<P4kReviewCase> cases) {
    Map<P4kKind, Set<UUID>> idsByKind = new EnumMap<>(P4kKind.class);
    for (P4kReviewCase c : cases) {
      if (c.getRowId() != null) {
        idsByKind.computeIfAbsent(c.getKind(), _ -> new HashSet<>()).add(c.getRowId());
      }
    }
    Map<UUID, String> names = new HashMap<>();
    idsByKind.forEach((kind, ids) ->
        entityManager
            .createQuery(P4kSweepTarget.of(kind).rowNamesQuery(), Object[].class)
            .setParameter("ids", ids)
            .getResultList()
            .forEach(tuple -> names.put((UUID) tuple[0], (String) tuple[1])));
    return names;
  }

  /**
   * Reads the {@code REVIEW} report rows of the last runs that found a page of cases, which name
   * the record.
   *
   * @param cases the cases
   * @return the report rows by kind, GUID, reason and run
   */
  @NotNull
  private Map<RecordKey, P4kImportChange> reviewRows(@NotNull List<P4kReviewCase> cases) {
    Set<UUID> runs = cases.stream().map(P4kReviewCase::getLastRunId).collect(Collectors.toSet());
    if (runs.isEmpty()) {
      return Map.of();
    }
    Set<UUID> guids = cases.stream().map(P4kReviewCase::getGuid).collect(Collectors.toSet());
    return changeRepository
        .findByChangeTypeAndRunIdInAndGuidIn(P4kChangeType.REVIEW, runs, guids)
        .stream()
        .collect(
            Collectors.toMap(
                c -> new RecordKey(c.getKind(), c.getGuid(), c.getFieldName(), c.getRunId()),
                c -> c,
                (first, _) -> first));
  }

  /**
   * Maps a case.
   *
   * @param c the case
   * @param rowNames the row names by row id
   * @param records the {@code REVIEW} report rows by kind, GUID, reason and run
   * @return its DTO
   */
  @NotNull
  private static P4kReviewCaseDto toDto(
      @NotNull P4kReviewCase c,
      @NotNull Map<UUID, String> rowNames,
      @NotNull Map<RecordKey, P4kImportChange> records) {
    P4kImportChange record =
        records.get(
            new RecordKey(c.getKind(), c.getGuid(), c.getReason().name(), c.getLastRunId()));
    return new P4kReviewCaseDto(
        c.getId(),
        Objects.requireNonNullElse(c.getVersion(), 0L),
        c.getKind(),
        c.getGuid(),
        c.getReason(),
        c.getStatus(),
        record == null ? null : record.getClassName(),
        record == null ? null : record.getNameEn(),
        c.getRowId(),
        c.getRowId() == null ? null : rowNames.get(c.getRowId()),
        c.getOtherRowId(),
        c.getFirstRunId(),
        c.getLastRunId(),
        c.getCreatedAt(),
        c.getResolution(),
        c.getResolvedAt());
  }

  /**
   * The key of a {@code REVIEW} report row.
   *
   * @param kind the kind
   * @param guid the GUID
   * @param reason the review reason's name
   * @param runId the run
   */
  private record RecordKey(
      @NotNull P4kKind kind, @Nullable UUID guid, @Nullable String reason, @Nullable UUID runId) {}
}
