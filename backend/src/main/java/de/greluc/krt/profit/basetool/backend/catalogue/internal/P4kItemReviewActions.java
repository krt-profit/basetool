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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Proposes review actions based on current UEX and SC Wiki item identities. */
@Component
@RequiredArgsConstructor
class P4kItemReviewActions implements P4kReviewActionProvider {

  private final GameItemRepository items;
  private final ItemReconciliationService reconciliation;

  @Override
  public boolean supports(@NotNull P4kKind kind) {
    return kind == P4kKind.ITEM;
  }

  @Override
  public @NotNull P4kReviewOptions options(
      @NotNull P4kReviewCaseDto review, @Nullable String recordPath) {
    Map<UUID, GameItem> known = new LinkedHashMap<>();
    if (review.rowId() != null) {
      items.findById(review.rowId()).ifPresent(item -> known.put(item.getId(), item));
    }
    if (review.otherRowId() != null) {
      items.findById(review.otherRowId()).ifPresent(item -> known.put(item.getId(), item));
    }
    if (review.reason() != P4kReviewReason.GUID_CONFLICT) {
      return new P4kReviewOptions(
          P4kReviewReadiness.CHECK_REQUIRED,
          null,
          known.values().stream()
              .map(item -> candidate(review, item, P4kReviewAction.INSPECT, null, List.of()))
              .toList());
    }

    GameItem wiki =
        items.findByExternalUuid(review.guid()).filter(P4kItemReviewActions::liveWiki).orElse(null);
    if (wiki != null) {
      known.put(wiki.getId(), wiki);
    }
    if (review.className() != null) {
      for (GameItem item : items.findByClassNameIgnoreCase(review.className())) {
        if (!item.isP4kRetired()) {
          known.put(item.getId(), item);
        }
      }
    }
    List<P4kReviewCandidate> options = new ArrayList<>();
    if (wiki != null && sameClass(wiki, review.className())) {
      if (wiki.getP4kUuid() == null && items.findByP4kUuid(review.guid()).isEmpty()) {
        options.add(candidate(review, wiki, P4kReviewAction.ATTACH_P4K, null, List.of()));
      }
      offerUexPeer(review, wiki, items.findByNameIgnoreCase(wikiName(wiki)), options);
    }
    for (GameItem item : known.values()) {
      if (options.stream().noneMatch(option -> option.targetId().equals(item.getId()))) {
        options.add(candidate(review, item, P4kReviewAction.INSPECT, null, List.of()));
      }
    }
    P4kReviewReadiness readiness =
        options.stream().anyMatch(option -> option.action() != P4kReviewAction.INSPECT)
            ? P4kReviewReadiness.ACTIONABLE
            : P4kReviewReadiness.CHECK_REQUIRED;
    return new P4kReviewOptions(readiness, null, List.copyOf(options));
  }

  @Override
  public @NotNull Map<UUID, P4kReviewReadiness> summarize(
      @NotNull List<P4kReviewCaseSource> reviews) {
    Set<UUID> incoming =
        reviews.stream()
            .map(P4kReviewCaseSource::review)
            .filter(review -> review.reason() == P4kReviewReason.GUID_CONFLICT)
            .map(P4kReviewCaseDto::guid)
            .collect(Collectors.toUnmodifiableSet());
    if (incoming.isEmpty()) {
      return Map.of();
    }

    Map<UUID, GameItem> wikiRows = new HashMap<>();
    for (GameItem item : items.findAllByExternalUuidIn(incoming)) {
      UUID external = item.getExternalUuid();
      if (external != null && liveWiki(item)) {
        wikiRows.put(external, item);
      }
    }
    if (wikiRows.isEmpty()) {
      return Map.of();
    }
    Set<UUID> claimed = Set.copyOf(items.findClaimedP4kGuids(wikiRows.keySet()));
    Set<String> names =
        wikiRows.values().stream()
            .filter(item ->
                Objects.equals(item.getExternalUuid(), item.getP4kUuid())
                    && item.getUexItemId() == null)
            .map(item -> wikiName(item).toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    Map<String, List<GameItem>> named =
        names.isEmpty()
            ? Map.of()
            : items.findAllByFoldedNames(names).stream()
                .collect(Collectors.groupingBy(item -> item.getName().toLowerCase(Locale.ROOT)));

    Map<UUID, P4kReviewReadiness> result = new HashMap<>();
    for (P4kReviewCaseSource entry : reviews) {
      P4kReviewCaseDto review = entry.review();
      GameItem wiki = wikiRows.get(review.guid());
      if (review.reason() != P4kReviewReason.GUID_CONFLICT
          || wiki == null
          || !sameClass(wiki, review.className())) {
        continue;
      }
      List<P4kReviewCandidate> options = new ArrayList<>();
      offerUexPeer(
          review,
          wiki,
          named.getOrDefault(wikiName(wiki).toLowerCase(Locale.ROOT), List.of()),
          options);
      if ((wiki.getP4kUuid() == null && !claimed.contains(review.guid()))
          || options.stream().anyMatch(option -> option.action() == P4kReviewAction.MERGE_ITEM)) {
        result.put(review.id(), P4kReviewReadiness.ACTIONABLE);
      }
    }
    return Map.copyOf(result);
  }

  @Override
  public void apply(
      @NotNull P4kReviewCaseDto review,
      @NotNull P4kReviewCandidate candidate,
      @NotNull UUID adminUserId) {
    GameItem wiki =
        items
            .findByExternalUuid(review.guid())
            .filter(P4kItemReviewActions::liveWiki)
            .filter(item -> sameClass(item, review.className()))
            .orElseThrow(P4kItemReviewActions::changed);
    if (candidate.action() == P4kReviewAction.ATTACH_P4K
        && candidate.targetId().equals(wiki.getId())
        && wiki.getP4kUuid() == null
        && items.findByP4kUuid(review.guid()).isEmpty()) {
      wiki.setP4kUuid(review.guid());
      return;
    }
    if (candidate.action() != P4kReviewAction.MERGE_ITEM
        || !review.guid().equals(wiki.getP4kUuid())
        || reconciliation.mergeReviewed(wiki, candidate.targetId(), wikiIdentity(wiki)) == null) {
      throw changed();
    }
  }

  private void offerUexPeer(
      P4kReviewCaseDto review,
      GameItem wiki,
      List<GameItem> sameName,
      List<P4kReviewCandidate> options) {
    List<GameItem> uexPeers =
        sameName.stream()
            .filter(item ->
                item.getExternalUuid() == null
                    && item.getUexItemId() != null
                    && item.getSourceSystems() == GameItemSourceSystem.UEX_ONLY
                    && item.getUexDeletedAt() == null
                    && !item.isP4kRetired())
            .toList();
    for (GameItem peer : uexPeers) {
      List<P4kReviewEvidence> conflicts = new ArrayList<>();
      if (wiki.getManufacturer() != null
          && peer.getManufacturer() != null
          && !wiki.getManufacturer().getId().equals(peer.getManufacturer().getId())) {
        conflicts.add(P4kReviewEvidence.DIFFERENT_MANUFACTURER);
      }
      if (peer.getClassName() != null && !sameClass(peer, wiki.getClassName())) {
        conflicts.add(P4kReviewEvidence.DIFFERENT_CLASS);
      }
      boolean verified =
          conflicts.isEmpty()
              && uexPeers.size() == 1
              && review.guid().equals(wiki.getP4kUuid())
              && ItemReconciliationService.compatible(peer, wiki, wikiIdentity(wiki))
              && sameName.stream()
                  .filter(P4kItemReviewActions::liveWiki)
                  .filter(item ->
                      ItemIdentityCompatibility.matches(
                          peer, item.getKind(), item.getManufacturer(), item.getClassName()))
                  .allMatch(item -> item.getId().equals(wiki.getId()))
              && (peer.getP4kUuid() == null || peer.getP4kUuid().equals(wiki.getP4kUuid()));
      P4kReviewAction action = verified ? P4kReviewAction.MERGE_ITEM : P4kReviewAction.INSPECT;
      options.add(candidate(review, peer, action, wiki.getVersion(), conflicts));
    }
  }

  private static boolean liveWiki(GameItem item) {
    return item.getExternalUuid() != null
        && item.getScwikiSlug() != null
        && item.getScwikiDeletedAt() == null
        && !item.isP4kRetired();
  }

  private static boolean sameClass(GameItem item, @Nullable String className) {
    return item.getClassName() != null && item.getClassName().equalsIgnoreCase(className);
  }

  private static String wikiName(GameItem wiki) {
    return Objects.requireNonNullElse(wiki.getScwikiName(), wiki.getName());
  }

  private static ItemReconciliationService.WikiIdentity wikiIdentity(GameItem wiki) {
    return new ItemReconciliationService.WikiIdentity(
        wikiName(wiki), wiki.getKind(), wiki.getManufacturer(), wiki.getClassName());
  }

  private static P4kReviewCandidate candidate(
      P4kReviewCaseDto review,
      GameItem item,
      P4kReviewAction action,
      @Nullable Long subjectVersion,
      List<P4kReviewEvidence> conflicts) {
    List<P4kReviewEvidence> evidence = new ArrayList<>(conflicts);
    if (item.getId().equals(review.rowId())) {
      evidence.add(P4kReviewEvidence.CURRENT_ROW);
    }
    if (review.guid().equals(item.getExternalUuid()) && item.getScwikiSlug() != null) {
      evidence.add(P4kReviewEvidence.EXACT_SCWIKI_UUID);
    }
    if (sameClass(item, review.className())) {
      evidence.add(P4kReviewEvidence.EXACT_CLASS);
    }
    if (item.getName().equalsIgnoreCase(review.nameEn())
        || (item.getUexName() != null && item.getUexName().equalsIgnoreCase(review.nameEn()))) {
      evidence.add(P4kReviewEvidence.SAME_NAME);
    }
    if (item.getP4kUuid() != null) {
      evidence.add(
          review.guid().equals(item.getP4kUuid())
              ? P4kReviewEvidence.EXACT_P4K_GUID
              : P4kReviewEvidence.OTHER_P4K_GUID);
    }
    Integer uexId = item.getUexItemId();
    return new P4kReviewCandidate(
        action,
        item.getId(),
        item.getVersion(),
        subjectVersion,
        item.getName(),
        item.getClassName(),
        item.getP4kUuid(),
        item.getScwikiSlug() == null ? null : item.getExternalUuid(),
        uexId == null ? List.of() : List.of(uexId),
        List.copyOf(evidence));
  }

  private static BusinessConflictException changed() {
    return new BusinessConflictException("The item identity has changed.");
  }
}
