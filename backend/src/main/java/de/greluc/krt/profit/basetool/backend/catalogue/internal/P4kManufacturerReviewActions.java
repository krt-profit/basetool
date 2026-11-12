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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.UexKeyRef;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Proposes reviewed manufacturer aliases, name decisions and evidence-backed duplicate merges. */
@Component
@RequiredArgsConstructor
class P4kManufacturerReviewActions implements P4kReviewActionProvider {

  private final ManufacturerRepository manufacturers;
  private final ManufacturerP4kAliasRepository p4kAliases;
  private final ManufacturerScwikiIdentityRepository wikiIdentities;
  private final ManufacturerUexCompanyRepository uexCompanies;
  private final ManufacturerReviewMerge merge;
  private final EntityManager entityManager;

  @Override
  public boolean supports(@NotNull P4kKind kind) {
    return kind == P4kKind.MANUFACTURER;
  }

  @Override
  public @NotNull P4kReviewOptions options(
      @NotNull P4kReviewCaseDto review, @Nullable String recordPath) {
    if (review.reason() != P4kReviewReason.NAME_CONFLICT
        && review.reason() != P4kReviewReason.GUID_CONFLICT) {
      return new P4kReviewOptions(P4kReviewReadiness.BLOCKED, recordPath, List.of());
    }
    CandidateSet proposals = proposals(review, recordPath, snapshot());
    List<P4kReviewCandidate> candidates = new ArrayList<>();
    for (Match match : proposals.matches()) {
      P4kReviewAction action = proposals.actionFor(review, match);
      candidates.add(
          match.candidate(
              review.guid(),
              action,
              action == P4kReviewAction.MERGE_MANUFACTURER ? proposals.subject() : null));
    }
    candidates.sort(
        Comparator.comparing(
                (P4kReviewCandidate candidate) -> candidate.action() == P4kReviewAction.INSPECT)
            .thenComparing(P4kReviewCandidate::name)
            .thenComparing(P4kReviewCandidate::targetId));
    return new P4kReviewOptions(
        readiness(candidates.stream().map(P4kReviewCandidate::action)),
        recordPath,
        List.copyOf(candidates));
  }

  @Override
  public @NotNull Map<UUID, P4kReviewReadiness> summarize(
      @NotNull List<P4kReviewCaseSource> reviews) {
    List<ManufacturerFacts> facts =
        reviews.stream().anyMatch(P4kManufacturerReviewActions::mayOfferAction)
            ? snapshot()
            : List.of();
    Map<UUID, P4kReviewReadiness> result = new HashMap<>();
    for (P4kReviewCaseSource source : reviews) {
      P4kReviewCaseDto review = source.review();
      P4kReviewReadiness readiness = P4kReviewReadiness.CHECK_REQUIRED;
      if (mayOfferAction(source)) {
        CandidateSet proposals = proposals(review, source.recordPath(), facts);
        readiness =
            readiness(
                proposals.matches().stream().map(match -> proposals.actionFor(review, match)));
      }
      result.put(review.id(), readiness);
    }
    return Map.copyOf(result);
  }

  private static P4kReviewReadiness readiness(Stream<P4kReviewAction> actions) {
    long writable = actions.filter(action -> action != P4kReviewAction.INSPECT).limit(2).count();
    return writable == 1 ? P4kReviewReadiness.ACTIONABLE : P4kReviewReadiness.CHECK_REQUIRED;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void apply(
      @NotNull P4kReviewCaseDto review,
      @Nullable String recordPath,
      @NotNull P4kReviewCandidate candidate,
      @NotNull UUID adminUserId) {
    Manufacturer target = requireVersion(candidate.targetId(), candidate.targetVersion());
    switch (candidate.action()) {
      case ATTACH_MANUFACTURER_ALIAS -> attach(review.guid(), target);
      case KEEP_MANUFACTURER_NAME -> entityManager.lock(target, LockModeType.OPTIMISTIC);
      case MERGE_MANUFACTURER -> {
        Manufacturer subject = requireVersion(review.rowId(), candidate.subjectVersion());
        merge.mergeWithinTransaction(subject, target, review.id());
        if (review.reason() == P4kReviewReason.GUID_CONFLICT
            && !review.guid().equals(subject.getP4kUuid())) {
          attach(review.guid(), subject);
        }
      }
      default -> throw new BadRequestException("This manufacturer proposal is for inspection.");
    }
  }

  private List<ManufacturerFacts> snapshot() {
    Map<UUID, List<ManufacturerScwikiIdentity>> wikiByManufacturer =
        wikiIdentities.findAll().stream()
            .collect(Collectors.groupingBy(identity -> identity.getManufacturer().getId()));
    Map<UUID, List<Integer>> uexByManufacturer =
        uexCompanies.findCompanyRefs().stream()
            .collect(
                Collectors.groupingBy(
                    UexKeyRef::getId,
                    Collectors.mapping(UexKeyRef::getUexId, Collectors.toList())));
    return manufacturers.findAll().stream()
        .map(manufacturer -> {
          UUID id = manufacturer.getId();
          List<ManufacturerScwikiIdentity> identities =
              wikiByManufacturer.getOrDefault(id, List.of());
          TreeSet<Integer> uexIds = new TreeSet<>(uexByManufacturer.getOrDefault(id, List.of()));
          Integer primaryUexId = manufacturer.getUexCompanyId();
          if (primaryUexId != null) {
            uexIds.add(primaryUexId);
          }
          return new ManufacturerFacts(manufacturer, identities, List.copyOf(uexIds));
        })
        .toList();
  }

  private static CandidateSet proposals(
      P4kReviewCaseDto review, @Nullable String recordPath, List<ManufacturerFacts> facts) {
    List<Match> matches =
        facts.stream()
            .map(fact ->
                new Match(
                    fact.manufacturer(),
                    fact.wikiIdentities(),
                    fact.uexIds(),
                    evidence(review, fact.manufacturer(), fact.wikiIdentities())))
            .filter(match ->
                match.evidence().stream()
                    .anyMatch(value -> value != P4kReviewEvidence.OTHER_P4K_GUID))
            .toList();
    Match subject =
        matches.stream()
            .filter(match -> match.manufacturer().getId().equals(review.rowId()))
            .findFirst()
            .orElse(null);
    boolean identityRecord = isIdentityRecord(recordPath);
    Match mergeTarget = identityRecord ? mergeTarget(review, subject, matches) : null;
    return new CandidateSet(matches, subject, mergeTarget, identityRecord);
  }

  private static boolean mayOfferAction(P4kReviewCaseSource source) {
    return source.review().reason() == P4kReviewReason.NAME_CONFLICT
        || (source.review().reason() == P4kReviewReason.GUID_CONFLICT
            && isIdentityRecord(source.recordPath()));
  }

  private static boolean isIdentityRecord(@Nullable String recordPath) {
    return recordPath != null
        && !recordPath.isBlank()
        && !P4kManufacturerRecordPath.isAuxiliary(recordPath);
  }

  private static EnumSet<P4kReviewEvidence> evidence(
      P4kReviewCaseDto review,
      Manufacturer manufacturer,
      List<ManufacturerScwikiIdentity> identities) {
    EnumSet<P4kReviewEvidence> evidence = EnumSet.noneOf(P4kReviewEvidence.class);
    UUID guid = manufacturer.getP4kUuid();
    if (guid != null) {
      evidence.add(
          review.guid().equals(guid)
              ? P4kReviewEvidence.EXACT_P4K_GUID
              : P4kReviewEvidence.OTHER_P4K_GUID);
    }
    if (review.guid().equals(manufacturer.getScwikiUuid())
        || identities.stream()
            .anyMatch(identity -> review.guid().equals(identity.getScwikiUuid()))) {
      evidence.add(P4kReviewEvidence.EXACT_SCWIKI_UUID);
    }
    if (Objects.equals(review.rowId(), manufacturer.getId())
        || Objects.equals(review.otherRowId(), manufacturer.getId())) {
      evidence.add(P4kReviewEvidence.CURRENT_ROW);
    }
    if (sameName(review.nameEn(), manufacturer.getName())
        || identities.stream()
            .anyMatch(identity -> sameName(review.nameEn(), identity.getWikiName()))) {
      evidence.add(P4kReviewEvidence.SAME_NAME);
    }
    if (sameCode(review.className(), manufacturer.getAbbreviation())
        || sameCode(review.className(), manufacturer.getScwikiCode())
        || identities.stream()
            .anyMatch(identity -> sameCode(review.className(), identity.getWikiCode()))) {
      evidence.add(P4kReviewEvidence.SAME_CODE);
    }
    return evidence;
  }

  private static P4kReviewAction action(
      P4kReviewCaseDto review,
      Match match,
      @Nullable Match subject,
      @Nullable Match mergeTarget,
      List<Match> matches,
      boolean identityRecord) {
    if (!match.active()) {
      return P4kReviewAction.INSPECT;
    }
    if (sameManufacturer(match, mergeTarget)) {
      return P4kReviewAction.MERGE_MANUFACTURER;
    }
    if (review.reason() == P4kReviewReason.NAME_CONFLICT
        && sameManufacturer(match, subject)
        && match.exactIdentity()) {
      return P4kReviewAction.KEEP_MANUFACTURER_NAME;
    }
    if (identityRecord
        && review.reason() == P4kReviewReason.GUID_CONFLICT
        && canAttach(match, matches)) {
      return P4kReviewAction.ATTACH_MANUFACTURER_ALIAS;
    }
    return P4kReviewAction.INSPECT;
  }

  private static boolean canAttach(Match match, List<Match> matches) {
    if (matches.stream()
        .anyMatch(candidate -> candidate.evidence().contains(P4kReviewEvidence.EXACT_P4K_GUID))) {
      return false;
    }
    List<Match> exact = matches.stream().filter(Match::exactIdentity).toList();
    return exact.isEmpty()
        ? match.hasNameAndCode() && matches.stream().filter(Match::hasNameAndCode).count() == 1
        : exact.size() == 1 && sameManufacturer(match, exact.getFirst());
  }

  private static @Nullable Match mergeTarget(
      P4kReviewCaseDto review, @Nullable Match subject, List<Match> matches) {
    if (subject == null
        || !subject.active()
        || !subject.exactIdentity()
        || !subject.hasNameAndCode()
        || subject.manufacturer().getP4kUuid() == null
        || matches.stream()
            .anyMatch(match -> !sameManufacturer(match, subject) && match.exactIdentity())) {
      return null;
    }
    if (review.reason() == P4kReviewReason.GUID_CONFLICT && !canAttach(subject, matches)) {
      return null;
    }
    List<Match> duplicates =
        matches.stream()
            .filter(match ->
                !sameManufacturer(match, subject) && match.active() && match.hasNameAndCode())
            .filter(
                match -> match.manufacturer().getP4kUuid() == null && match.hasExternalIdentity())
            .filter(
                match -> sameName(subject.manufacturer().getName(), match.manufacturer().getName()))
            .toList();
    return duplicates.size() == 1 ? duplicates.getFirst() : null;
  }

  private static boolean sameManufacturer(@NotNull Match match, @Nullable Match other) {
    return other != null && match.manufacturer().getId().equals(other.manufacturer().getId());
  }

  private Manufacturer requireVersion(@Nullable UUID id, @Nullable Long version) {
    if (id == null || version == null) {
      throw new BusinessConflictException("The manufacturer proposal has changed.");
    }
    Manufacturer row =
        manufacturers
            .findById(id)
            .orElseThrow(
                () -> new BusinessConflictException("The manufacturer is no longer available."));
    OptimisticLock.checkRequired(row.getVersion(), version, Manufacturer.class, id);
    return row;
  }

  private void attach(UUID guid, Manufacturer manufacturer) {
    if (manufacturers.findByP4kUuid(guid).isPresent()) {
      throw new BusinessConflictException("The P4K GUID already identifies a manufacturer.");
    }
    ManufacturerP4kAlias existing = p4kAliases.findById(guid).orElse(null);
    if (existing != null) {
      if (!existing.getManufacturer().getId().equals(manufacturer.getId())) {
        throw new BusinessConflictException(
            "The P4K alias already belongs to another manufacturer.");
      }
      return;
    }
    entityManager.lock(manufacturer, LockModeType.OPTIMISTIC);
    p4kAliases.save(new ManufacturerP4kAlias(guid, manufacturer));
  }

  private static boolean sameName(@Nullable String left, @Nullable String right) {
    return left != null
        && !left.isBlank()
        && right != null
        && foldName(left).equals(foldName(right));
  }

  private static String foldName(String name) {
    return P4kMatchKeys.fold(name).replace("&", " AND ").replaceAll("\\s+", " ").strip();
  }

  private static boolean sameCode(@Nullable String left, @Nullable String right) {
    return left != null
        && !left.isBlank()
        && right != null
        && P4kMatchKeys.fold(left).equals(P4kMatchKeys.fold(right));
  }

  private record Match(
      Manufacturer manufacturer,
      List<ManufacturerScwikiIdentity> wikiIdentities,
      List<Integer> uexIds,
      EnumSet<P4kReviewEvidence> evidence) {

    boolean active() {
      return !manufacturer.isHidden()
          && manufacturer.getP4kHiddenAt() == null
          && manufacturer.getP4kDeletedAt() == null;
    }

    boolean exactIdentity() {
      return evidence.contains(P4kReviewEvidence.EXACT_P4K_GUID)
          || evidence.contains(P4kReviewEvidence.EXACT_SCWIKI_UUID);
    }

    boolean hasNameAndCode() {
      return evidence.contains(P4kReviewEvidence.SAME_NAME)
          && evidence.contains(P4kReviewEvidence.SAME_CODE);
    }

    boolean hasExternalIdentity() {
      return (!uexIds.isEmpty() && manufacturer.getUexDeletedAt() == null)
          || ((manufacturer.getScwikiUuid() != null || !wikiIdentities.isEmpty())
              && manufacturer.getScwikiDeletedAt() == null);
    }

    P4kReviewCandidate candidate(
        UUID reviewedGuid, P4kReviewAction action, @Nullable Match subject) {
      EnumSet<P4kReviewEvidence> facts = EnumSet.copyOf(evidence);
      if (action == P4kReviewAction.MERGE_MANUFACTURER && !uexIds.isEmpty()) {
        facts.add(P4kReviewEvidence.EXACT_UEX_ID);
      }
      UUID wikiGuid =
          facts.contains(P4kReviewEvidence.EXACT_SCWIKI_UUID)
              ? reviewedGuid
              : manufacturer.getScwikiUuid();
      return new P4kReviewCandidate(
          action,
          manufacturer.getId(),
          manufacturer.getVersion(),
          subject == null ? null : subject.manufacturer().getVersion(),
          manufacturer.getName(),
          manufacturer.getAbbreviation(),
          manufacturer.getP4kUuid(),
          wikiGuid,
          uexIds,
          List.copyOf(facts));
    }
  }

  /** Current source identities of one manufacturer. */
  private record ManufacturerFacts(
      Manufacturer manufacturer,
      List<ManufacturerScwikiIdentity> wikiIdentities,
      List<Integer> uexIds) {}

  /** The matched rows and the subject and peer used to decide a proposal. */
  private record CandidateSet(
      List<Match> matches,
      @Nullable Match subject,
      @Nullable Match mergeTarget,
      boolean identityRecord) {

    P4kReviewAction actionFor(P4kReviewCaseDto review, Match match) {
      return action(review, match, subject, mergeTarget, matches, identityRecord);
    }
  }
}
