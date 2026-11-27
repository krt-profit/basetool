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

package de.greluc.krt.profit.basetool.backend.platform.api;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * A module's part of the Art. 15 / Art. 20 data export (REQ-SEC-058): the sections that select the
 * rows its module holds for one member.
 *
 * <p>A section is a declaration, a single read-only statement with the named parameter {@code
 * :userId}; the export runs it, scrubs the declared columns and orders the sections by {@link
 * Section#position()}. A statement selects only the member's own columns, never another member's id
 * or handle. Each section names the processing it belongs to, so the export can state purpose,
 * lawful basis, retention and source per section (Art. 15(1)(a), (d) and (g) GDPR).
 */
public interface PersonalDataExportSection {

  /** The data subject's right a section is exported under. */
  enum Right {

    /** The right of access of Art. 15 only. */
    ART_15,

    /** The right of access and data portability of Art. 20: data the member provided. */
    ART_15_20
  }

  /**
   * The processing activity of the record of processing activities ({@code
   * docs/privacy/processing-activities.md}) a section belongs to; it fixes purpose and lawful
   * basis.
   */
  @Getter
  @RequiredArgsConstructor
  enum Activity {

    /** A1: the member account, sign-in and admission. */
    ACCOUNT(true),

    /** A2: missions, operations, warehouse, orders, refinery, hangar, inventory and market. */
    OPERATIONS(true),

    /** A3: the bank's ledger and its records, on Art. 6(1)(f) only. */
    BANKING(false),

    /** A4: the activity audit trails, on Art. 6(1)(f) only. */
    AUDIT(false),

    /** A5: notifications. */
    NOTIFICATIONS(true),

    /** A6: acceptance of the terms of use. */
    TERMS(true),

    /** A9: handling data-subject requests, on Art. 6(1)(c) only. */
    DATA_SUBJECT_REQUESTS(false),

    /** A11: connected applications. */
    CONNECTED_APPLICATIONS(true),

    /** A12: material purchasing from external partners. */
    PURCHASING(true);

    /**
     * Whether the activity's lawful basis includes consent or a contract (Art. 6(1)(a) or (b)), the
     * condition of Art. 20(1)(a) for data portability.
     */
    private final boolean portable;
  }

  /**
   * How long a section's rows are kept, one rule of the retention table of the record of processing
   * activities and of section 18 of the privacy policy each.
   */
  enum Retention {

    /** Kept until the account is deleted. */
    ACCOUNT_LIFETIME,

    /** Kept until the account is deleted, then reassigned to an administrator. */
    REASSIGNED_ON_DELETION,

    /** Kept until the account is deleted, then kept without the link to the member. */
    UNLINKED_ON_DELETION,

    /** The rows stay; names and free text go three years after the event (REQ-DATA-047). */
    NAMES_REMOVED_AFTER_THREE_YEARS,

    /** A received donation stays without free text; the link ends with the account. */
    DONATION,

    /** Read notifications 90 days after reading, unread ones 180 days after creation. */
    NOTIFICATION_WINDOWS,

    /** 24 months after the recorded activity (REQ-AUDIT-006). */
    AUDIT_TWENTY_FOUR_MONTHS,

    /**
     * Kept without limit with the handle of the time; the name is removed on request, and after the
     * account is deleted three years after the booking (REQ-DATA-047).
     */
    BANK_RECORDS_PERMANENT,

    /** Kept without limit; the link to the member ends with the account (REQ-PUR-022). */
    PURCHASING_PERMANENT,

    /** 90 days (REQ-XCH-013, REQ-XCH-022, REQ-XCH-034). */
    EXCHANGE_NINETY_DAYS,

    /** While connected, then 90 days after the disconnection (REQ-XCH-035). */
    CONNECTION,

    /** Until the ship is deleted, at the latest with the account. */
    SHIP_LINK,

    /** While the refinery order exists, then until no change entry refers to it (REQ-XCH-056). */
    CLIENT_ORDER_KEY,

    /** Until the job order leaves the data connected applications receive (REQ-OXCH-010). */
    CLIENT_ORDER_WINDOW,

    /** Until the erasure is carried out, then removed with the account (REQ-SEC-061). */
    DELETION_REQUEST,

    /**
     * Kept without limit as evidence of an act on the organisation's data; after the account is
     * deleted with the internal id only, which goes three years after the act (REQ-DATA-047).
     */
    ADMIN_ACT_PERMANENT,

    /** Seven days after the catalogue import job was started (REQ-P4K-015). */
    CATALOGUE_IMPORT_JOB
  }

  /** Where a section's data comes from, for the data the member did not enter themselves. */
  enum Source {

    /** Entered by the member. */
    MEMBER,

    /** Entered by other members, leads or administrators in their role. */
    OTHER_MEMBERS,

    /** Recorded by the tool while it is used. */
    SYSTEM,

    /** Taken over from Discord at sign-in. */
    DISCORD,

    /** Sent by a connected application on the member's behalf. */
    CONNECTED_APPLICATION
  }

  /**
   * The processing a section belongs to.
   *
   * @param activity the processing activity, which fixes purpose and lawful basis
   * @param retention how long the rows are kept
   * @param sources where the rows come from, never empty
   */
  record Processing(
      @NotNull Activity activity,
      @NotNull Retention retention,
      @NotNull @Unmodifiable Set<Source> sources) {

    /**
     * Copies the sources so the processing stays immutable.
     *
     * @throws IllegalArgumentException when no source is named
     */
    public Processing {
      if (sources.isEmpty()) {
        throw new IllegalArgumentException("A section names at least one source");
      }
      sources = Set.copyOf(sources);
    }

    /**
     * Tells whether rows of this processing can be portable under Art. 20: the activity rests on
     * consent or a contract, and the member provided the rows, by themselves or through their
     * connected application.
     *
     * @return {@code true} when a section of this processing may be marked {@link Right#ART_15_20}
     */
    public boolean allowsPortability() {
      return activity.isPortable()
          && (sources.contains(Source.MEMBER) || sources.contains(Source.CONNECTED_APPLICATION));
    }
  }

  /**
   * One export section.
   *
   * @param position where the section sits in the export, ascending and unique across modules
   * @param key the stable machine key, also the JSON property name and the suffix of the {@code
   *     pdf.export.section.*} label key
   * @param right the right the member receives it under
   * @param rationale one sentence on why the section carries this right; not serialised
   * @param sql the statement, selecting the member's rows through {@code :userId}
   * @param scrubbedColumns the selected columns passed through the handle scrubber
   * @param unscrubbedPersonColumns the selected person-name columns left as they are, each with the
   *     reason
   * @param processing the processing the section belongs to, or {@code null} until declared; the
   *     registry refuses a section without one
   */
  record Section(
      int position,
      @NotNull String key,
      @NotNull Right right,
      @NotNull String rationale,
      @NotNull String sql,
      @NotNull @Unmodifiable Set<String> scrubbedColumns,
      @NotNull @Unmodifiable Map<String, String> unscrubbedPersonColumns,
      @Nullable Processing processing) {

    /** Copies the column declarations so a section stays immutable. */
    public Section {
      scrubbedColumns = Set.copyOf(scrubbedColumns);
      unscrubbedPersonColumns = Map.copyOf(unscrubbedPersonColumns);
    }

    /**
     * Creates a section that scrubs nothing, exempts nothing and has no processing yet.
     *
     * @param position where the section sits in the export
     * @param key the stable machine key
     * @param right the right the member receives it under
     * @param rationale why the section carries this right
     * @param sql the statement
     */
    public Section(
        int position,
        @NotNull String key,
        @NotNull Right right,
        @NotNull String rationale,
        @NotNull String sql) {
      this(position, key, right, rationale, sql, Set.of(), Map.of(), null);
    }

    /**
     * Returns this section with further columns scrubbed.
     *
     * @param columns the selected column aliases to pass through the handle scrubber
     * @return the extended section
     */
    public @NotNull Section scrubbing(@NotNull String... columns) {
      Set<String> all = new HashSet<>(scrubbedColumns);
      all.addAll(List.of(columns));
      return new Section(
          position, key, right, rationale, sql, all, unscrubbedPersonColumns, processing);
    }

    /**
     * Returns this section with one person-name column left unscrubbed on purpose.
     *
     * @param column the selected column alias
     * @param reason why the value must stay as it is
     * @return the extended section
     */
    public @NotNull Section keeping(@NotNull String column, @NotNull String reason) {
      Map<String, String> all = new HashMap<>(unscrubbedPersonColumns);
      all.put(column, reason);
      return new Section(position, key, right, rationale, sql, scrubbedColumns, all, processing);
    }

    /**
     * Returns this section with the processing it belongs to.
     *
     * @param activity the processing activity
     * @param retention how long the rows are kept
     * @param first where the rows come from
     * @param more further sources
     * @return the extended section
     */
    public @NotNull Section processedAs(
        @NotNull Activity activity,
        @NotNull Retention retention,
        @NotNull Source first,
        @NotNull Source... more) {
      return new Section(
          position,
          key,
          right,
          rationale,
          sql,
          scrubbedColumns,
          unscrubbedPersonColumns,
          new Processing(activity, retention, EnumSet.of(first, more)));
    }

    /**
     * Tells whether a selected column is passed through the handle scrubber.
     *
     * @param column the column alias as the statement yields it
     * @return {@code true} when the value is scrubbed
     */
    public boolean scrubs(@NotNull String column) {
      return scrubbedColumns.contains(column);
    }
  }

  /**
   * Returns the sections this module contributes.
   *
   * @return the sections, in any order
   */
  @NotNull
  List<Section> sections();
}
