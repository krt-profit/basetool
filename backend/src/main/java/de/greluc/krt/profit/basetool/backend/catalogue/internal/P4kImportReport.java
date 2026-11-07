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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The change report and the counts of one P4K run while it is computed (REQ-P4K-008, REQ-P4K-014):
 * report rows and review cases in memory, persisted by {@link P4kImportRecorder} after an APPLY and
 * stored on the job after a preview. Report rows hold identifiers, short names and fact values,
 * never a game text.
 */
public final class P4kImportReport {

  /** The longest short name or class name a report row stores. */
  private static final int MAX_SHORT_TEXT = 255;

  /** The {@code field_name} of a {@link P4kChangeType#LOADOUT_ISSUES} row. */
  private static final String LOADOUT_FIELD = "loadout";

  /** The ships whose port tree the run wrote. */
  private int loadoutShips;

  /** The ports of those trees. */
  private int loadoutPorts;

  /** The ship links of the listed paints. */
  private int paintLinks;

  /** The report rows, in the order the run found them. */
  private final List<P4kImportChange> changes = new ArrayList<>();

  /** The review cases, one per kind, GUID and reason. */
  private final Map<ReviewKey, ReviewFinding> reviews = new LinkedHashMap<>();

  /** The mutable counts per kind. */
  private final Map<P4kKind, Tally> tallies = new EnumMap<>(P4kKind.class);

  /** The GUIDs of the records the run created a row from, or in a preview would, per kind. */
  private final Map<P4kKind, Set<UUID>> addedGuids = new EnumMap<>(P4kKind.class);

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
    add(row(kind, record, P4kChangeType.UNMATCHED));
  }

  /**
   * Records a row the run created.
   *
   * @param kind the kind
   * @param record the record the row was created from
   */
  public void added(@NotNull P4kKind kind, @NotNull P4kRecord record) {
    tally(kind).created++;
    UUID guid = record.parsedGuid();
    if (guid != null) {
      addedGuids.computeIfAbsent(kind, _ -> new HashSet<>()).add(guid);
    }
    add(row(kind, record, P4kChangeType.ADDED));
  }

  /**
   * Whether the run created a row from a record, or in a preview would.
   *
   * @param kind the kind
   * @param guid the record's GUID
   * @return {@code true} when a row of the GUID was added in this run
   */
  public boolean isAdded(@NotNull P4kKind kind, @NotNull UUID guid) {
    return addedGuids.getOrDefault(kind, Set.of()).contains(guid);
  }

  /**
   * Records a field of a matched row the run writes (REQ-P4K-014).
   *
   * @param kind the kind
   * @param record the record
   * @param field the column written
   * @param oldValue the value before the run, {@code null} for none or for a game text
   * @param newValue the value written, {@code null} for a game text
   */
  public void changed(
      @NotNull P4kKind kind,
      @NotNull P4kRecord record,
      @NotNull String field,
      @Nullable String oldValue,
      @Nullable String newValue) {
    P4kImportChange change = row(kind, record, P4kChangeType.CHANGED);
    change.setFieldName(field);
    change.setOldValue(shorten(oldValue));
    change.setNewValue(shorten(newValue));
    add(change);
  }

  /**
   * Records a stamped row the run matched again (REQ-P4K-011).
   *
   * @param kind the kind
   * @param record the record
   */
  public void reappeared(@NotNull P4kKind kind, @NotNull P4kRecord record) {
    tally(kind).reappeared++;
    add(row(kind, record, P4kChangeType.REAPPEARED));
  }

  /**
   * Records a row the sweep stamps (REQ-P4K-011).
   *
   * @param kind the kind
   * @param row the P4K-known row the catalogue no longer lists
   */
  public void vanished(@NotNull P4kKind kind, @NotNull P4kKnownRow row) {
    tally(kind).vanished++;
    P4kImportChange change = new P4kImportChange();
    change.setKind(kind);
    change.setChangeType(P4kChangeType.VANISHED);
    change.setGuid(row.guid());
    change.setClassName(shorten(row.className()));
    change.setNameEn(shorten(row.name()));
    change.setStillLiveElsewhere(row.stillLiveElsewhere());
    add(change);
  }

  /**
   * Records what the sweep did with a kind, and a report row when it was skipped.
   *
   * @param kind the kind
   * @param outcome the sweep's outcome
   * @param known the kind's P4K-known rows
   * @param toStamp the rows the sweep stamps, or would have stamped
   */
  public void sweep(
      @NotNull P4kKind kind, @NotNull P4kSweepOutcome outcome, int known, int toStamp) {
    Tally tally = tally(kind);
    tally.sweep = outcome;
    tally.known = known;
    tally.toStamp = toStamp;
    if (outcome != P4kSweepOutcome.SWEPT) {
      P4kImportChange change = new P4kImportChange();
      change.setKind(kind);
      change.setChangeType(P4kChangeType.SWEEP_SKIPPED);
      change.setFieldName(outcome.name());
      change.setOldValue(Integer.toString(known));
      change.setNewValue(Integer.toString(toStamp));
      add(change);
    }
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
    add(change);
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
    add(change);
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
    add(change);
  }

  /**
   * Records a ship loadout that was not read in full (REQ-P4K-040).
   *
   * @param ship the ship the loadout belongs to
   * @param issues the loadout's issue codes, possibly empty
   * @param unresolvedPorts the ports whose attachment could not be resolved
   */
  public void loadoutIssues(
      @NotNull P4kRecord ship, @NotNull List<String> issues, int unresolvedPorts) {
    P4kImportChange change = row(P4kKind.SHIP, ship, P4kChangeType.LOADOUT_ISSUES);
    change.setFieldName(LOADOUT_FIELD);
    change.setOldValue(String.join(",", issues));
    change.setNewValue(Integer.toString(unresolvedPorts));
    add(change);
  }

  /**
   * Counts a ship whose port tree the run wrote, or would write.
   *
   * @param ports the ports of the tree, nested ones included
   */
  public void loadoutWritten(int ports) {
    loadoutShips++;
    loadoutPorts += ports;
  }

  /**
   * Counts the ship links of a listed paint.
   *
   * @param links the ship types the paint is linked to
   */
  public void paintLinked(int links) {
    paintLinks += links;
  }

  /**
   * Returns the ships whose port tree the run wrote.
   *
   * @return the ship count
   */
  public int loadoutShips() {
    return loadoutShips;
  }

  /**
   * Returns the ports of the trees the run wrote.
   *
   * @return the port count
   */
  public int loadoutPorts() {
    return loadoutPorts;
  }

  /**
   * Returns the ship links of the paints the run listed.
   *
   * @return the link count
   */
  public int paintLinks() {
    return paintLinks;
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
   * Returns the sweep result of one kind.
   *
   * @param kind the kind
   * @return the sweep result; its outcome is {@code null} when no sweep was decided for the kind
   */
  @NotNull
  public P4kImportResultDto.Sweep sweepResult(@NotNull P4kKind kind) {
    Tally t = tally(kind);
    return new P4kImportResultDto.Sweep(
        kind, t.sweep, t.known, t.toStamp, t.vanished, t.reappeared);
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
   * Appends a report row at the next position.
   *
   * @param change the row
   */
  private void add(@NotNull P4kImportChange change) {
    change.setPosition(changes.size());
    changes.add(change);
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
    change.setNameEn(shorten(record.reportName()));
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
    private int reappeared;
    private int vanished;
    private int known;
    private int toStamp;
    @Nullable private P4kSweepOutcome sweep;
  }
}
