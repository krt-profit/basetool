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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kRecord;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The change report and the counts of one P4K run while it is computed (REQ-P4K-008, REQ-P4K-014):
 * report rows and review cases in memory, persisted by {@link P4kImportRecorder} after an APPLY. A
 * preview computes the same report and keeps only its counts.
 */
public final class P4kImportReport {

  /** The longest short name or class name a report row stores. */
  private static final int MAX_SHORT_TEXT = 255;

  /** The report rows, in the order the run found them. */
  private final List<P4kImportChange> changes = new ArrayList<>();

  /** The review cases, one per kind, GUID and reason. */
  private final Map<ReviewKey, ReviewFinding> reviews = new LinkedHashMap<>();

  /** The mutable counts per kind. */
  private final Map<P4kKind, Tally> tallies = new EnumMap<>(P4kKind.class);

  /**
   * A review case the run found.
   *
   * @param kind the kind
   * @param guid the P4K GUID
   * @param reason why the record is on the review list
   * @param rowId the Basetool row, or {@code null} for a record without one
   */
  public record ReviewFinding(
      @NotNull P4kKind kind,
      @NotNull UUID guid,
      @NotNull P4kReviewReason reason,
      @Nullable UUID rowId) {}

  /**
   * Counts a matched and stamped row.
   *
   * @param kind the kind
   * @param enriched whether the run wrote at least one field of the row
   */
  public void matched(@NotNull P4kKind kind, boolean enriched) {
    Tally tally = tally(kind);
    tally.matched++;
    if (enriched) {
      tally.enriched++;
    }
  }

  /**
   * Records a catalogue record without a Basetool row.
   *
   * @param kind the kind
   * @param record the record
   */
  public void unmatched(@NotNull P4kKind kind, @NotNull P4kRecord record) {
    tally(kind).unmatched++;
    changes.add(row(kind, record, P4kChangeType.UNMATCHED));
  }

  /**
   * Records a row the run created.
   *
   * @param kind the kind
   * @param record the record the row was created from
   */
  public void added(@NotNull P4kKind kind, @NotNull P4kRecord record) {
    tally(kind).created++;
    changes.add(row(kind, record, P4kChangeType.ADDED));
  }

  /**
   * Records a matched row whose SC Wiki UUID differs from the P4K GUID (REQ-P4K-007).
   *
   * @param kind the kind
   * @param record the record
   * @param wikiColumn the column holding the Wiki UUID
   * @param wikiUuid the Wiki UUID the row keeps
   */
  public void identityMismatch(
      @NotNull P4kKind kind,
      @NotNull P4kRecord record,
      @NotNull String wikiColumn,
      @NotNull UUID wikiUuid) {
    tally(kind).identityMismatches++;
    P4kImportChange change = row(kind, record, P4kChangeType.IDENTITY_MISMATCH);
    change.setFieldName(wikiColumn);
    change.setOldValue(wikiUuid.toString());
    change.setNewValue(record.guid());
    changes.add(change);
  }

  /**
   * Records a closed-set value the import does not know (REQ-P4K-003).
   *
   * @param kind the kind
   * @param record the record
   * @param field the catalogue field, e.g. {@code playability}
   * @param value the unknown value
   */
  public void unknownValue(
      @NotNull P4kKind kind,
      @NotNull P4kRecord record,
      @NotNull String field,
      @NotNull String value) {
    tally(kind).unknownValues++;
    P4kImportChange change = row(kind, record, P4kChangeType.UNKNOWN_VALUE);
    change.setFieldName(field);
    change.setNewValue(shorten(value));
    changes.add(change);
  }

  /**
   * Records a review case once per kind, GUID and reason, with its {@code REVIEW} report row.
   *
   * @param kind the kind
   * @param record the record
   * @param guid the record's P4K GUID
   * @param reason why the record is on the review list
   * @param rowId the Basetool row, or {@code null}
   */
  public void review(
      @NotNull P4kKind kind,
      @NotNull P4kRecord record,
      @NotNull UUID guid,
      @NotNull P4kReviewReason reason,
      @Nullable UUID rowId) {
    if (reviews.putIfAbsent(
            new ReviewKey(kind, guid, reason), new ReviewFinding(kind, guid, reason, rowId))
        != null) {
      return;
    }
    tally(kind).review++;
    P4kImportChange change = row(kind, record, P4kChangeType.REVIEW);
    change.setFieldName(reason.name());
    changes.add(change);
  }

  /**
   * Returns the counts of one kind.
   *
   * @param kind the kind
   * @param completeness the kind's completeness in the catalogue
   * @return the counts
   */
  @NotNull
  public P4kImportResultDto.Counts counts(
      @NotNull P4kKind kind, @NotNull P4kCompleteness completeness) {
    Tally t = tally(kind);
    return new P4kImportResultDto.Counts(
        completeness,
        t.matched,
        t.enriched,
        t.identityMismatches,
        t.created,
        t.unmatched,
        t.review,
        t.unknownValues);
  }

  /**
   * Returns the report rows, without their run id.
   *
   * @return the rows in the order found
   */
  @NotNull
  @Unmodifiable
  public List<P4kImportChange> changes() {
    return List.copyOf(changes);
  }

  /**
   * Returns the review cases.
   *
   * @return the cases in the order found
   */
  @NotNull
  @Unmodifiable
  public List<ReviewFinding> reviews() {
    return List.copyOf(reviews.values());
  }

  /**
   * Returns the tally of a kind, creating it on first use.
   *
   * @param kind the kind
   * @return the tally
   */
  @NotNull
  private Tally tally(@NotNull P4kKind kind) {
    return tallies.computeIfAbsent(kind, _ -> new Tally());
  }

  /**
   * Builds a report row with the record's identifiers.
   *
   * @param kind the kind
   * @param record the record
   * @param type the change type
   * @return the unsaved row
   */
  @NotNull
  private static P4kImportChange row(
      @NotNull P4kKind kind, @NotNull P4kRecord record, @NotNull P4kChangeType type) {
    P4kImportChange change = new P4kImportChange();
    change.setKind(kind);
    change.setChangeType(type);
    change.setGuid(record.parsedGuid());
    change.setClassName(shorten(record.className()));
    change.setNameEn(shorten(record.nameEn()));
    change.setRecordPath(record.recordPath());
    return change;
  }

  /**
   * Cuts a short text to the column width.
   *
   * @param text the text, or {@code null}
   * @return the text cut to {@value #MAX_SHORT_TEXT} characters, or {@code null}
   */
  @Nullable
  private static String shorten(@Nullable String text) {
    return text == null || text.length() <= MAX_SHORT_TEXT
        ? text
        : text.substring(0, MAX_SHORT_TEXT);
  }

  /**
   * The identity of a review case.
   *
   * @param kind the kind
   * @param guid the P4K GUID
   * @param reason the reason
   */
  private record ReviewKey(
      @NotNull P4kKind kind, @NotNull UUID guid, @NotNull P4kReviewReason reason) {}

  /** The mutable counts of one kind. */
  private static final class Tally {
    private int matched;
    private int enriched;
    private int identityMismatches;
    private int created;
    private int unmatched;
    private int review;
    private int unknownValues;
  }
}
