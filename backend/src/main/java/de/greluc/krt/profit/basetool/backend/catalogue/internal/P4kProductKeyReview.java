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

import de.greluc.krt.profit.basetool.backend.catalogue.api.ProducedItemGuidFollower;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ProducedItemGuidFollower.UnresolvedProductKey;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kRecord;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Hands an APPLY's produced-item GUIDs to the modules keyed by them and keeps the review list of
 * the personal-blueprint name keys in step (REQ-P4K-029, REQ-P4K-016): each name key that still
 * resolves to no GUID or to several is a {@code BLUEPRINT} case, and an open case whose key a run
 * resolved is closed as {@code RESOLVED}.
 */
@Component
@RequiredArgsConstructor
public class P4kProductKeyReview {

  /** The reasons this review owns. */
  private static final List<P4kReviewReason> REASONS =
      List.of(P4kReviewReason.PRODUCT_KEY_UNRESOLVED, P4kReviewReason.PRODUCT_KEY_AMBIGUOUS);

  /** Prefixes the name key a case GUID is derived from. */
  private static final String CASE_GUID_PREFIX = "personal-blueprint-product-key:";

  private final List<ProducedItemGuidFollower> followers;
  private final P4kReviewCaseRepository caseRepository;

  /**
   * Lets every follower move its rows and records the name keys left over; an APPLY also reopens
   * and closes the cases, a preview only reports them, read against the GUIDs already stored.
   *
   * @param context the run
   * @param now the run time, stamped on the closed cases
   */
  public void follow(@NotNull P4kImportContext context, @NotNull Instant now) {
    boolean apply = context.isApply();
    Set<CaseKey> found = new HashSet<>();
    for (ProducedItemGuidFollower follower : followers) {
      for (UnresolvedProductKey key : follower.followProducedItemGuids(apply)) {
        UUID guid = caseGuid(key.nameKey());
        P4kReviewReason reason =
            key.ambiguous()
                ? P4kReviewReason.PRODUCT_KEY_AMBIGUOUS
                : P4kReviewReason.PRODUCT_KEY_UNRESOLVED;
        found.add(new CaseKey(guid, reason));
        context.getReport().review(P4kKind.BLUEPRINT, new NameKeyRecord(key), guid, reason, null);
        if (!apply) {
          continue;
        }
        caseRepository
            .findByKindAndGuidAndReason(P4kKind.BLUEPRINT, guid, reason)
            .filter(c -> c.getResolution() == P4kReviewResolution.RESOLVED)
            .ifPresent(P4kProductKeyReview::reopen);
      }
    }
    if (!apply) {
      return;
    }
    for (P4kReviewCase open :
        caseRepository.findByStatusAndKindAndReasonIn(
            P4kReviewStatus.OPEN, P4kKind.BLUEPRINT, REASONS)) {
      if (!found.contains(new CaseKey(open.getGuid(), open.getReason()))) {
        open.setStatus(P4kReviewStatus.REVIEWED);
        open.setResolution(P4kReviewResolution.RESOLVED);
        open.setResolvedAt(now);
      }
    }
  }

  /**
   * Opens a case a run closed again, because a later run found its name key unresolved once more;
   * an admin's decision is never reopened.
   *
   * @param reviewCase the case closed as {@code RESOLVED}
   */
  private static void reopen(@NotNull P4kReviewCase reviewCase) {
    reviewCase.setStatus(P4kReviewStatus.OPEN);
    reviewCase.setResolution(null);
    reviewCase.setResolvedAt(null);
  }

  /**
   * Derives the stable GUID of a name key's review case.
   *
   * @param nameKey the name key
   * @return a name-based UUID of the key
   */
  @NotNull
  static UUID caseGuid(@NotNull String nameKey) {
    return UUID.nameUUIDFromBytes((CASE_GUID_PREFIX + nameKey).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The identity of a review case of this review.
   *
   * @param guid the case GUID
   * @param reason the reason
   */
  private record CaseKey(@NotNull UUID guid, @NotNull P4kReviewReason reason) {}

  /**
   * A name key as the report row names it: its name key as class name, its product name as English
   * name.
   *
   * @param key the unresolved key
   */
  private record NameKeyRecord(@NotNull UnresolvedProductKey key) implements P4kRecord {

    @Override
    public @Nullable String guid() {
      return caseGuid(key.nameKey()).toString();
    }

    @Override
    public @NotNull String className() {
      return key.nameKey();
    }

    @Override
    public @Nullable String recordPath() {
      return null;
    }

    @Override
    public @NotNull P4kLocalisedText name() {
      return new P4kLocalisedText(null, key.productName(), null);
    }
  }
}
