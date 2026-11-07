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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Matches catalogue records to Basetool rows the same way for every kind (REQ-P4K-007): the P4K
 * GUID first, then the SC Wiki UUID, then a class name and a name that each identify exactly one
 * row and one record. The import never merges: a key that several rows or records share, a row that
 * already holds another GUID and a row two records claim each open a {@code GUID_CONFLICT} review
 * case and leave the rows as they are.
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
 */
public record P4kRowMatcher<T extends P4kKeyedRow>(
    @NotNull P4kKind kind,
    @NotNull Function<UUID, Optional<T>> byP4kUuid,
    @NotNull Function<UUID, Optional<T>> byWikiUuid,
    @NotNull Function<T, UUID> wikiUuid,
    @NotNull String wikiColumn,
    @NotNull Function<String, List<T>> byClassName,
    @NotNull Function<String, List<T>> byName,
    @NotNull Predicate<T> seeded) {

  /**
   * What matching one record found.
   *
   * @param <T> the row type
   */
  public sealed interface Match<T> permits Matched, Unmatched, Held {}

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
    Optional<T> found = byP4kUuid.apply(guid).or(() -> byWikiUuid.apply(guid));
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
   * Tries one fallback key: it matches only when it names exactly one row and one record.
   *
   * @param record the record
   * @param guid the record's GUID
   * @param key the key, or {@code null} to skip
   * @param finder finds the rows of the key, ignoring case
   * @param repeated whether several records of the kind share the key
   * @param creatable whether the current seeding rule of the kind would create the record
   * @param context the run
   * @return the match, or {@code null} when the key finds no row
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
    List<T> candidates = finder.apply(key.strip());
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
   * Accepts a found row unless another GUID holds or claimed it or it is held for review, and
   * reports a SC Wiki UUID that differs from the GUID.
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
    if ((held != null && !held.equals(guid)) || !context.claim(kind, row.getId(), guid)) {
      report.review(kind, record, guid, P4kReviewReason.GUID_CONFLICT, row.getId());
      return new Held<>();
    }
    if (seeded.test(row) && !creatable) {
      report.review(kind, record, guid, P4kReviewReason.SEEDED_NOT_CREATABLE, row.getId());
      return new Held<>();
    }
    UUID wiki = wikiUuid.apply(row);
    if (wiki != null && !wiki.equals(guid)) {
      report.identityMismatch(kind, record, wikiColumn, wiki);
    }
    return new Matched<>(row, guid);
  }
}
