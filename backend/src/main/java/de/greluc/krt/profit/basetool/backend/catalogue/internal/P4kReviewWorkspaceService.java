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
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Lists review groups and verifies their decisions against the current catalogue. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class P4kReviewWorkspaceService {

  private final P4kReviewCaseRepository cases;
  private final P4kReviewService reviewService;
  private final List<P4kReviewActionProvider> providers;
  private final SyncCoordinator syncCoordinator;
  private final P4kCatalogueRevision revision;
  private final P4kCatalogueChange catalogueChange;
  private final CatalogueAdminLiveSync adminLiveSync;
  private final AuditRecorder auditRecorder;

  /** The transaction proxy used after the coordinator lock is held. */
  private final ObjectProvider<P4kReviewWorkspaceService> self;

  /**
   * Lists groups that match the selected kind and reason.
   *
   * @param kind an optional kind filter
   * @param reason an optional reason filter
   * @param readiness an optional next-action filter
   * @param pageable the page of groups
   * @return group summaries in review order
   */
  public Page<P4kReviewGroupDto> groups(
      @Nullable P4kKind kind,
      @Nullable P4kReviewReason reason,
      @Nullable P4kReviewReadiness readiness,
      @NotNull Pageable pageable) {
    List<P4kReviewCaseSource> open = loadSources(kind, reason);
    Map<UUID, P4kReviewReadiness> current = summarize(open);
    List<P4kReviewGroupDto> summaries =
        P4kReviewWorkspaceGroups.from(open, kind, reason).stream()
            .map(group -> group.summary(current))
            .filter(group -> readiness == null || group.readiness() == readiness)
            .sorted(Comparator.comparing(P4kReviewGroupDto::readiness))
            .toList();
    return page(summaries, pageable);
  }

  private Map<UUID, P4kReviewReadiness> summarize(List<P4kReviewCaseSource> open) {
    Map<UUID, P4kReviewReadiness> summary = new HashMap<>();
    for (P4kReviewActionProvider provider : providers) {
      List<P4kReviewCaseSource> supported =
          open.stream().filter(entry -> provider.supports(entry.review().kind())).toList();
      if (!supported.isEmpty()) {
        summary.putAll(provider.summarize(supported));
      }
    }
    return summary;
  }

  /**
   * Lists the open members of a group.
   *
   * @param groupId the group
   * @param pageable the page of cases
   * @return the group's case snapshots
   */
  public Page<P4kReviewCaseDto> groupMembers(@NotNull UUID groupId, @NotNull Pageable pageable) {
    return page(
        findGroup(groupId).map(P4kReviewWorkspaceGroups.Group::members).orElseGet(List::of),
        pageable);
  }

  /**
   * Reads a case and its currently applicable proposals.
   *
   * @param caseId the open case
   * @return the source evidence and proposals
   */
  public P4kReviewWorkspaceDetailDto detail(@NotNull UUID caseId) {
    P4kReviewCase reviewCase = requireCase(caseId);
    if (reviewCase.getStatus() != P4kReviewStatus.OPEN) {
      throw new NotFoundException("The review case is no longer open.");
    }
    P4kReviewCaseSource entry = view(reviewCase);
    P4kReviewActionProvider provider = provider(entry.review().kind());
    P4kReviewOptions options =
        provider == null
            ? new P4kReviewOptions(P4kReviewReadiness.CHECK_REQUIRED, null, List.of())
            : provider.options(entry.review(), entry.recordPath());
    return new P4kReviewWorkspaceDetailDto(entry.review(), entry.recordPath(), options);
  }

  /**
   * Applies a current proposal under the same lock as import and source synchronization.
   *
   * @param caseId the open case
   * @param request the selected proposal and its versions
   * @param adminUserId the administrator confirming the action
   * @return the completed case
   */
  @Transactional(propagation = Propagation.SUPPORTS)
  public P4kReviewCaseDto apply(
      @NotNull UUID caseId, @NotNull P4kReviewApplyRequest request, @NotNull UUID adminUserId) {
    AtomicReference<P4kReviewCaseDto> result = new AtomicReference<>();
    exclusively(() -> result.set(self.getObject().applyWithinLock(caseId, request, adminUserId)));
    return Objects.requireNonNull(result.get());
  }

  /**
   * Revalidates and applies a proposal in the coordinator's transaction.
   *
   * @param caseId the open case
   * @param request the selected proposal and its versions
   * @param adminUserId the administrator confirming the action
   * @return the completed case
   */
  @Transactional
  public P4kReviewCaseDto applyWithinLock(
      @NotNull UUID caseId, @NotNull P4kReviewApplyRequest request, @NotNull UUID adminUserId) {
    P4kReviewCase reviewCase = openCase(caseId);
    OptimisticLock.checkRequired(
        reviewCase.getVersion(), request.version(), P4kReviewCase.class, caseId);
    if (reviewCase.isShipRepairPending()
        && request.action() != P4kReviewAction.CANCEL_SHIP_REPAIR) {
      throw new BusinessConflictException("The ship identity repair is waiting for an import.");
    }
    P4kReviewActionProvider provider = provider(reviewCase.getKind());
    if (provider == null || request.action() == P4kReviewAction.INSPECT) {
      throw new BadRequestException("The selected review action cannot be applied.");
    }
    P4kReviewCaseSource entry = view(reviewCase);
    P4kReviewCandidate candidate =
        provider.options(entry.review(), entry.recordPath()).candidates().stream()
            .filter(option ->
                option.action() == request.action() && option.targetId().equals(request.targetId()))
            .findFirst()
            .orElseThrow(() -> new BusinessConflictException("The review proposal has changed."));
    OptimisticLock.check(
        candidate.targetVersion(),
        request.targetVersion(),
        P4kReviewCandidate.class,
        candidate.targetId());
    OptimisticLock.check(
        candidate.subjectVersion(),
        request.subjectVersion(),
        P4kReviewCandidate.class,
        reviewCase.getRowId());
    provider.apply(entry.review(), entry.recordPath(), candidate, adminUserId);

    AuditEventType eventType =
        switch (candidate.action()) {
          case CORRECT_P4K_GUID -> AuditEventType.CATALOGUE_P4K_REVIEW_REPAIR_QUEUED;
          case CANCEL_SHIP_REPAIR -> AuditEventType.CATALOGUE_P4K_REVIEW_REPAIR_CANCELLED;
          default -> AuditEventType.CATALOGUE_P4K_REVIEW_APPLIED;
        };
    if (eventType == AuditEventType.CATALOGUE_P4K_REVIEW_APPLIED) {
      reviewCase.setStatus(P4kReviewStatus.REVIEWED);
      reviewCase.setResolution(P4kReviewResolution.APPLIED);
      reviewCase.setResolvedBy(adminUserId);
      reviewCase.setResolvedAt(Instant.now());
      catalogueChange.announce();
    }
    cases.saveAndFlush(reviewCase);
    revision.changed();
    adminLiveSync.reviewChanged();
    auditRecorder.record(
        eventType,
        reviewCase.getId(),
        reviewCase.getGuid().toString(),
        null,
        AuditDetails.of("kind", reviewCase.getKind().name())
            .with("reason", reviewCase.getReason().name())
            .with("action", candidate.action().name())
            .with("targetId", candidate.targetId()));
    return view(reviewCase).review();
  }

  /**
   * Applies one decision to the selected members of the same current group.
   *
   * @param groupId the group
   * @param request the decision and the members shown in the preview
   * @param adminUserId the administrator confirming the decision
   */
  @Transactional(propagation = Propagation.SUPPORTS)
  public void resolveBatch(
      @NotNull UUID groupId, @NotNull P4kReviewBatchRequest request, @NotNull UUID adminUserId) {
    exclusively(() -> self.getObject().resolveBatchWithinLock(groupId, request, adminUserId));
  }

  /**
   * Validates the entire selection before writing any case in the group.
   *
   * @param groupId the group
   * @param request the decision and the selected case versions
   * @param adminUserId the administrator confirming the decision
   */
  @Transactional
  public void resolveBatchWithinLock(
      @NotNull UUID groupId, @NotNull P4kReviewBatchRequest request, @NotNull UUID adminUserId) {
    P4kReviewWorkspaceGroups.Group group =
        findGroup(groupId)
            .orElseThrow(
                () -> new BusinessConflictException("The review group is no longer open."));
    P4kReviewGroupDto summary = group.summary();
    if (!summary.batchable()
        || (request.resolution() != P4kReviewResolution.KEEP
            && (request.resolution() != P4kReviewResolution.HIDE
                || summary.reason() != P4kReviewReason.SEEDED_NOT_CREATABLE))) {
      throw new BadRequestException("The group does not support this shared decision.");
    }
    if (request.members().stream().map(P4kReviewBatchMember::id).distinct().count()
        != request.members().size()) {
      throw new BadRequestException("A review case can appear only once in a batch.");
    }
    var byId =
        group.members().stream()
            .collect(Collectors.toMap(P4kReviewCaseDto::id, Function.identity()));
    for (P4kReviewBatchMember member : request.members()) {
      P4kReviewCaseDto current = byId.get(member.id());
      if (current == null) {
        throw new BusinessConflictException("The selected review cases have changed.");
      }
      OptimisticLock.checkRequired(
          current.version(), member.version(), P4kReviewCase.class, member.id());
    }
    for (P4kReviewBatchMember member : request.members()) {
      reviewService.resolveWithinLock(
          member.id(), request.resolution(), null, null, member.version(), adminUserId);
    }
  }

  private void exclusively(Runnable work) {
    if (!syncCoordinator.tryRunExclusively(P4kReviewService.RESOLVE_LOCK_LABEL, work)) {
      throw new BusinessConflictException(
          "A P4K import or a Wiki or UEX sync is running; review after it finishes.");
    }
  }

  private P4kReviewCase openCase(UUID caseId) {
    P4kReviewCase review = requireCase(caseId);
    if (review.getStatus() != P4kReviewStatus.OPEN) {
      throw new BusinessConflictException("The review case is already resolved.");
    }
    return review;
  }

  private P4kReviewCase requireCase(UUID caseId) {
    return Entities.require(
        cases.findById(caseId), () -> "P4K review case " + caseId + " was not found.");
  }

  private P4kReviewCaseSource view(P4kReviewCase reviewCase) {
    return reviewService.workspaceCases(List.of(reviewCase)).getFirst();
  }

  @Nullable
  private P4kReviewActionProvider provider(P4kKind kind) {
    return providers.stream().filter(provider -> provider.supports(kind)).findFirst().orElse(null);
  }

  private Optional<P4kReviewWorkspaceGroups.Group> findGroup(UUID id) {
    return loadGroups(null, null).stream().filter(group -> group.id().equals(id)).findFirst();
  }

  private List<P4kReviewWorkspaceGroups.Group> loadGroups(
      @Nullable P4kKind kind, @Nullable P4kReviewReason reason) {
    return P4kReviewWorkspaceGroups.from(loadSources(kind, reason), kind, reason);
  }

  private List<P4kReviewCaseSource> loadSources(
      @Nullable P4kKind kind, @Nullable P4kReviewReason reason) {
    List<P4kReviewCase> open =
        cases
            .findByStatusOrderByCreatedAtAscIdAsc(P4kReviewStatus.OPEN, Pageable.unpaged())
            .getContent()
            .stream()
            .filter(review -> kind == null || review.getKind() == kind)
            .filter(review -> reason == null || review.getReason() == reason)
            .toList();
    return reviewService.workspaceCases(open);
  }

  private static <T> Page<T> page(List<T> values, Pageable pageable) {
    int start = (int) Math.min(pageable.getOffset(), values.size());
    int end = start + Math.min(pageable.getPageSize(), values.size() - start);
    return new PageImpl<>(values.subList(start, end), pageable, values.size());
  }
}
