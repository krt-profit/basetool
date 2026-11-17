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

import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kKeyedRow;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kRecord;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Matches catalogue records to Basetool rows the same way for every kind (REQ-P4K-007): the P4K
 * GUID first, then the record's SC Wiki key (REQ-P4K-070), then a class name and a name that each
 * identify exactly one row and one record. The import never merges: a key that several rows or
 * records share, a row that already holds another GUID and a row two records claim each open a
 * {@code GUID_CONFLICT} review case and leave the rows as they are, unless the kind's {@link
 * P4kMatchRules} say a row the key found already stands for the record (REQ-P4K-050), which then
 * needs neither a row of its own nor a case, or leave the row out of the class-name and name steps
 * (REQ-P4K-054).
 *
 * @param <T> the row type
 * @param kind the kind
 * @param byP4kUuid finds the row holding a P4K GUID
 * @param byWikiUuid finds the row holding a SC Wiki UUID
 * @param wikiUuid reads the row's SC Wiki UUID
 * @param wikiColumn the column holding the SC Wiki UUID, for the report
 * @param byClassName finds the rows of a class name, ignoring case
 * @param byName finds the rows of a name, ignoring case
 * @param seeded whether a row was created by an earlier seeding (REQ-P4K-010)
 * @param rules the kind's rules for a found row of another GUID
 */
public record P4kRowMatcher<T extends P4kKeyedRow>(
    @NotNull P4kKind kind,
    @NotNull Function<UUID, Optional<T>> byP4kUuid,
    @NotNull Function<UUID, Optional<T>> byWikiUuid,
    @NotNull Function<T, UUID> wikiUuid,
    @NotNull String wikiColumn,
    @NotNull Function<String, List<T>> byClassName,
    @NotNull Function<String, List<T>> byName,
    @NotNull Predicate<T> seeded,
    @NotNull P4kMatchRules<T> rules) {

  /**
   * Creates the matcher of a kind with the default rules: no stored row covers a record of another
   * GUID, and the class-name and name steps consider every row they find.
   *
   * @param kind the kind
   * @param byP4kUuid finds the row holding a P4K GUID
   * @param byWikiUuid finds the row holding a SC Wiki UUID
   * @param wikiUuid reads the row's SC Wiki UUID
   * @param wikiColumn the column holding the SC Wiki UUID, for the report
   * @param byClassName finds the rows of a class name, ignoring case
   * @param byName finds the rows of a name, ignoring case
   * @param seeded whether a row was created by an earlier seeding (REQ-P4K-010)
   */
  public P4kRowMatcher(
      @NotNull P4kKind kind,
      @NotNull Function<UUID, Optional<T>> byP4kUuid,
      @NotNull Function<UUID, Optional<T>> byWikiUuid,
      @NotNull Function<T, UUID> wikiUuid,
      @NotNull String wikiColumn,
      @NotNull Function<String, List<T>> byClassName,
      @NotNull Function<String, List<T>> byName,
      @NotNull Predicate<T> seeded) {
    this(
        kind,
        byP4kUuid,
        byWikiUuid,
        wikiUuid,
        wikiColumn,
        byClassName,
        byName,
        seeded,
        P4kMatchRules.none());
  }

  /**
   * What matching one record found.
   *
   * @param <T> the row type
   */
  public sealed interface Match<T> permits Matched, Unmatched, Held, Covered {}

  /**
   * One row, accepted for enrichment and stamping.
   *
   * @param row the row
   * @param guid the record's GUID
   * @param <T> the row type
   */
  public record Matched<T>(@NotNull T row, @NotNull UUID guid) implements Match<T> {}

  /**
   * No row; the caller may create one or reports the record unmatched.
   *
   * @param guid the record's GUID, {@code null} when the record has no usable one
   * @param <T> the row type
   */
  public record Unmatched<T>(@Nullable UUID guid) implements Match<T> {}

  /**
   * A conflict or a held row: the review case is recorded and nothing is written.
   *
   * @param <T> the row type
   */
  public record Held<T>() implements Match<T> {}

  /**
   * A stored row that already stands for the record: nothing is stamped, created or reviewed.
   *
   * @param row the covering row
   * @param <T> the row type
   */
  public record Covered<T>(@NotNull T row) implements Match<T> {}

  /**
   * Writes the values a reconciler's kind leads onto a matched row.
   *
   * @param <T> the row type
   */
  @FunctionalInterface
  public interface Enricher<T> {

    /**
     * Writes onto a matched row, before the run stamps it.
     *
     * @param row the matched row
     * @param guid the record's GUID
     * @return whether a field changed
     */
    boolean enrich(@NotNull T row, @NotNull UUID guid);
  }

  /**
   * Matches one record and handles the outcome the way every reconciler does: a matched row is
   * enriched, stamped, indexed under the keys it now holds and counted; an unmatched record goes to
   * the reconciler's own rule; a held one is already on the review list.
   *
   * @param record the record
   * @param className the record's class-name key, or {@code null} to skip that step
   * @param name the record's name key, or {@code null} to skip that step
   * @param creatable whether the current seeding rule of the kind would create the record
   * @param context the run
   * @param rows the kind's row index
   * @param enricher writes the led values onto a matched row
   * @param unmatched handles a record without a row, given its GUID or {@code null}
   */
  public void reconcile(
      @NotNull P4kRecord record,
      @Nullable String className,
      @Nullable String name,
      boolean creatable,
      @NotNull P4kImportContext context,
      @NotNull P4kRowIndex<T> rows,
      @NotNull Enricher<T> enricher,
      @NotNull Consumer<UUID> unmatched) {
    reconcile(record, className, name, creatable, context, rows, enricher, unmatched, (_, _) -> {});
  }

  /**
   * Matches one record as {@link #reconcile(P4kRecord, String, String, boolean, P4kImportContext,
   * P4kRowIndex, Enricher, Consumer)} does, and hands a covered record's GUID and covering row to
   * the caller.
   *
   * @param record the record
   * @param className the record's class-name key, or {@code null} to skip that step
   * @param name the record's name key, or {@code null} to skip that step
   * @param creatable whether the current seeding rule of the kind would create the record
   * @param context the run
   * @param rows the kind's row index
   * @param enricher writes the led values onto a matched row
   * @param unmatched handles a record without a row, given its GUID or {@code null}
   * @param covered receives the covering row and the covered record's GUID
   */
  public void reconcile(
      @NotNull P4kRecord record,
      @Nullable String className,
      @Nullable String name,
      boolean creatable,
      @NotNull P4kImportContext context,
      @NotNull P4kRowIndex<T> rows,
      @NotNull Enricher<T> enricher,
      @NotNull Consumer<UUID> unmatched,
      @NotNull BiConsumer<T, UUID> covered) {
    switch (match(record, className, name, creatable, context)) {
      case Matched<T>(T row, UUID guid) -> {
        boolean changed = enricher.enrich(row, guid);
        context.stamp(kind, record, row, guid);
        rows.add(row);
        context.getReport().matched(kind, changed);
      }
      case Unmatched<T>(UUID guid) -> unmatched.accept(guid);
      case Held<T> _ -> {}
      case Covered<T>(T row) -> {
        context.getReport().covered(kind, record);
        covered.accept(row, Objects.requireNonNull(record.parsedGuid()));
      }
    }
  }

  /**
   * Matches one record.
   *
   * @param record the record
   * @param className the record's class-name key, or {@code null} to skip that step
   * @param name the record's name key, or {@code null} to skip that step
   * @param creatable whether the current seeding rule of the kind would create the record
   * @param context the run
   * @return the match; a record without a usable GUID is {@link Unmatched}
   */
  @NotNull
  public Match<T> match(
      @NotNull P4kRecord record,
      @Nullable String className,
      @Nullable String name,
      boolean creatable,
      @NotNull P4kImportContext context) {
    UUID guid = record.parsedGuid();
    if (guid == null) {
      return new Unmatched<>(null);
    }
    Optional<T> holder = byP4kUuid.apply(guid);
    if (holder.isPresent() && followsAnother(holder.get(), guid)) {
      if (rules.covers(guid, holder.get())) {
        return new Covered<>(holder.get());
      }
      holder = Optional.empty();
    }
    Optional<T> found =
        holder.or(() -> rules.follower(guid)).or(() -> byWikiUuid.apply(rules.wikiKey(guid)));
    if (found.isPresent()) {
      return accept(record, guid, found.get(), creatable, context);
    }
    Match<T> byClass =
        fallback(
            record,
            guid,
            className,
            byClassName,
            context.isRepeatedClassName(kind, className),
            creatable,
            context);
    if (byClass != null) {
      return byClass;
    }
    Match<T> byNameMatch =
        fallback(
            record, guid, name, byName, context.isRepeatedName(kind, name), creatable, context);
    return byNameMatch != null ? byNameMatch : new Unmatched<>(guid);
  }

  /**
   * Tries one fallback key: it matches only when it names exactly one candidate row and one record;
   * when every row it finds is refused as a candidate, the next step runs.
   *
   * @param record the record
   * @param guid the record's GUID
   * @param key the key, or {@code null} to skip
   * @param finder finds the rows of the key, ignoring case
   * @param repeated whether several records of the kind share the key
   * @param creatable whether the current seeding rule of the kind would create the record
   * @param context the run
   * @return the match, or {@code null} when the key finds no candidate row
   */
  @Nullable
  private Match<T> fallback(
      @NotNull P4kRecord record,
      @NotNull UUID guid,
      @Nullable String key,
      @NotNull Function<String, List<T>> finder,
      boolean repeated,
      boolean creatable,
      @NotNull P4kImportContext context) {
    if (key == null || key.isBlank()) {
      return null;
    }
    List<T> found = finder.apply(key.strip());
    Optional<T> covering = found.stream().filter(row -> rules.covers(guid, row)).findFirst();
    if (covering.isPresent()) {
      return new Covered<>(covering.get());
    }
    List<T> candidates = found.stream().filter(row -> rules.isCandidate(guid, row)).toList();
    if (candidates.isEmpty()) {
      return null;
    }
    if (candidates.size() > 1 || repeated) {
      context.getReport().review(kind, record, guid, P4kReviewReason.GUID_CONFLICT, null);
      return new Held<>();
    }
    return accept(record, guid, candidates.getFirst(), creatable, context);
  }

  /**
   * Tells whether a row's own facts move it to the record of another GUID than the given one, so
   * the given GUID no longer finds it (REQ-P4K-055).
   *
   * @param row the row holding the GUID
   * @param guid the GUID that found it
   * @return {@code true} when the row follows another record
   */
  private boolean followsAnother(@NotNull T row, @NotNull UUID guid) {
    UUID followed = rules.followedGuid(row);
    return followed != null && !followed.equals(guid);
  }

  /**
   * Accepts a found row unless another GUID holds or claimed it or it is held for review, and
   * reports a SC Wiki UUID that is neither the GUID nor the record's Wiki key; a row whose own
   * facts name the record takes its GUID instead of the one it holds and reports the change
   * (REQ-P4K-055).
   *
   * @param record the record
   * @param guid the record's GUID
   * @param row the found row
   * @param creatable whether the current seeding rule of the kind would create the record
   * @param context the run
   * @return the match
   */
  @NotNull
  private Match<T> accept(
      @NotNull P4kRecord record,
      @NotNull UUID guid,
      @NotNull T row,
      boolean creatable,
      @NotNull P4kImportContext context) {
    P4kImportReport report = context.getReport();
    UUID held = row.getP4kUuid();
    boolean foreign = held != null && !held.equals(guid);
    if (foreign && rules.covers(guid, row)) {
      return new Covered<>(row);
    }
    boolean follows = foreign && guid.equals(rules.followedGuid(row));
    if ((foreign && !follows) || !context.claim(kind, row.getId(), guid)) {
      report.review(kind, record, guid, P4kReviewReason.GUID_CONFLICT, row.getId());
      return new Held<>();
    }
    if (follows) {
      report.changed(kind, record, "p4k_uuid", String.valueOf(held), guid.toString());
    }
    if (seeded.test(row) && !creatable) {
      if (row.getP4kHiddenAt() == null && !context.isRetired(kind, row.getId())) {
        report.review(kind, record, guid, P4kReviewReason.SEEDED_NOT_CREATABLE, row.getId());
      }
      return new Held<>();
    }
    UUID wiki = wikiUuid.apply(row);
    if (wiki != null && !wiki.equals(guid) && !wiki.equals(rules.wikiKey(guid))) {
      report.identityMismatch(kind, record, wikiColumn, wiki);
    }
    return new Matched<>(row, guid);
  }
}
