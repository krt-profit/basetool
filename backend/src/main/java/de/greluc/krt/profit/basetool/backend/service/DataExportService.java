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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.DataExportSections;
import de.greluc.krt.profit.basetool.backend.support.DataExportSections.Section;
import de.greluc.krt.profit.basetool.backend.support.HandleScrubber;
import de.greluc.krt.profit.basetool.backend.support.HandleSpellings;
import de.greluc.krt.profit.basetool.backend.support.PersonSearchTargets;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.persistence.TupleElement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles a member's Art. 15 / Art. 20 data export (REQ-SEC-058).
 *
 * <p>Before this existed, an access request meant a person reading ~25 tables by hand, which is
 * both slow and the kind of task that quietly produces an incomplete answer.
 *
 * <p><b>Third-party data is excluded by construction.</b> The statements in {@link
 * DataExportSections} select the requester's own columns and never another member's id or handle —
 * so the structured half of the export cannot leak a counterparty even if somebody later adds a
 * section carelessly, because the leak would have to be written into a visible {@code SELECT} list.
 * Free text the requester wrote goes through {@link HandleScrubber}, which is the only part that
 * scrubs rather than omits, for the only data where omission is not possible.
 *
 * <p><b>Each section is marked with its legal basis</b>, so the Art. 20 portable subset is
 * identifiable without re-deriving which sections qualify.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataExportService {

  /** Rows returned per section, so one very active member cannot produce an unbounded document. */
  public static final int MAX_ROWS_PER_SECTION = 5000;

  private final UserRepository userRepository;
  private final AuditService auditService;

  @PersistenceContext private EntityManager entityManager;

  /**
   * One section of an assembled export.
   *
   * @param key the stable machine key, also the label key
   * @param legalBasis {@code ART_15} or {@code ART_15_20}
   * @param rows the rows, each a column-name to value map in the statement's own column order
   * @param truncated whether {@link #MAX_ROWS_PER_SECTION} was reached
   */
  public record ExportSection(
      String key, String legalBasis, List<Map<String, Object>> rows, boolean truncated) {}

  /**
   * A complete export.
   *
   * @param subjectId the member the export is about
   * @param subjectHandle their effective name, so the document names its own subject
   * @param generatedAt when it was assembled
   * @param thirdPartyHandlesRemoved whether any third-party handle was scrubbed from free text,
   *     which tells the admin whether the Art. 15(4) read-through has anything to look for
   * @param outOfScope surfaces deliberately not covered, stated in the document itself
   * @param sections the sections, in reading order
   */
  public record DataExport(
      UUID subjectId,
      String subjectHandle,
      Instant generatedAt,
      boolean thirdPartyHandlesRemoved,
      List<String> outOfScope,
      List<ExportSection> sections) {

    /**
     * The total number of rows across every section.
     *
     * @return the row count
     */
    public int totalRows() {
      return sections.stream().mapToInt(s -> s.rows().size()).sum();
    }
  }

  /**
   * Surfaces the export deliberately does not cover. Stated in the document rather than left
   * implicit, and kept in step with {@code docs/privacy/data-subject-requests.md}.
   */
  private static final List<String> OUT_OF_SCOPE =
      List.of("platformLogsMetricsTraces", "backups", "keycloakAccountRecord");

  /**
   * Assembles the export for one member.
   *
   * @param userId the member
   * @return the assembled export
   * @throws EntityNotFoundException when no such member exists
   */
  @Transactional(readOnly = true)
  public @NotNull DataExport export(@NotNull UUID userId) {
    User subject =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));

    HandleScrubber scrubber = scrubberForOthers(userId);

    List<ExportSection> sections = new ArrayList<>(DataExportSections.SECTIONS.size());
    boolean scrubbed = false;
    for (Section section : DataExportSections.SECTIONS) {
      Query query =
          entityManager
              .createNativeQuery(section.sql(), Tuple.class)
              .setParameter("userId", userId);
      query.setMaxResults(MAX_ROWS_PER_SECTION + 1);
      List<?> raw = query.getResultList();
      boolean truncated = raw.size() > MAX_ROWS_PER_SECTION;

      List<Map<String, Object>> rows = new ArrayList<>(Math.min(raw.size(), MAX_ROWS_PER_SECTION));
      boolean sectionHasProse = scrubber.isActive();
      for (Object item : raw.subList(0, Math.min(raw.size(), MAX_ROWS_PER_SECTION))) {
        Tuple tuple = (Tuple) item;
        Map<String, Object> row = new LinkedHashMap<>();
        for (TupleElement<?> element : tuple.getElements()) {
          String name = element.getAlias();
          Object value = tuple.get(name);
          // Per column, not per section. Scrubbing every String of a section corrupted structured
          // values -- an e-mail address, a status, a username -- whenever another member's handle
          // occurred inside one (REQ-SEC-058, DataExportSections#UNSCRUBBED_PERSON_COLUMNS).
          if (sectionHasProse
              && DataExportSections.isScrubbed(section.key(), name)
              && value instanceof String text) {
            String cleaned = scrubber.scrub(text);
            if (cleaned != null && !cleaned.equals(text)) {
              scrubbed = true;
            }
            value = cleaned;
          }
          row.put(name, value);
        }
        rows.add(row);
      }
      sections.add(new ExportSection(section.key(), section.legalBasis(), rows, truncated));
    }

    DataExport export =
        new DataExport(
            userId, subject.getEffectiveName(), Instant.now(), scrubbed, OUT_OF_SCOPE, sections);
    // The subject's id, never their handle: a log line about an access request must not be a second
    // place the name is written (REQ-OBS-004).
    log.info(
        "Assembled data export for {}: {} section(s), {} row(s){}",
        userId,
        sections.size(),
        export.totalRows(),
        scrubbed ? ", third-party handles removed from free text" : "");
    return export;
  }

  /**
   * Appends the audit row for one served export (REQ-SEC-058, REQ-AUDIT-001).
   *
   * <p>Separate from {@link #export(UUID)}, and deliberately so. The assembly is {@code readOnly =
   * true} — the right setting for ~29 statements across the whole schema, and one that cannot hold
   * an {@code INSERT}: Spring marks the JDBC connection read-only and Postgres refuses the write.
   * The audit row therefore gets its own short writable transaction, which also satisfies the
   * {@code MANDATORY} propagation on {@link AuditService#record} that a controller calling it
   * directly cannot satisfy at all.
   *
   * <p>Being a second transaction rather than the export's own is acceptable <em>here</em> and
   * nowhere near a mutation: the export is a read, so there is no business write for the audit row
   * to be atomic with. What the guarantee costs is the case where the row is written and the
   * response never reaches the caller — an export recorded that nobody received, which errs towards
   * over-recording and is the safe direction for an access-request trail.
   *
   * <p>The payload names the format and the row count only. Nothing about the export's contents
   * goes in, and neither does the subject's handle.
   *
   * @param userId the member the export was about, recorded as both subject and target
   * @param format {@code json} or {@code pdf}
   * @param rows the row count, or {@code -1} when the format does not report one
   * @param bySelf whether the subject exported their own data, as opposed to an admin doing it for
   *     them — the distinction the trail exists to make answerable
   */
  @Transactional
  public void recordExport(@NotNull UUID userId, @NotNull String format, int rows, boolean bySelf) {
    auditService.record(
        AuditEventType.PERSONAL_DATA_EXPORTED,
        userId,
        null,
        userId,
        AuditDetails.of("format", format).with("rows", rows).with("bySelf", bySelf));
  }

  /**
   * A scrubber loaded with every spelling of every member's name <em>except</em> the subject's.
   *
   * <p>Not scrubbing the subject is not an optimisation: replacing their own name would remove the
   * one name the export is supposed to be about, and would do it from the entries they wrote
   * themselves.
   *
   * <p><b>But they are still passed to the scrubber, as protected terms.</b> Leaving them out of
   * the matcher entirely is what shredded them — longest-match ranks only the terms it knows, so a
   * three-character third-party handle that is a prefix of the subject's own longer name won, and
   * the name came back with a placeholder spliced into the middle of it.
   *
   * <p><b>Every spelling, not just the effective one.</b> {@code getEffectiveName()} is {@code
   * displayName ?: username}, so loading only that left a member's {@code username} unscrubbed for
   * as long as they had a display name set, and left every member's {@code discord_guild_nickname}
   * unscrubbed always — while the export still told the reader that other members' names had been
   * removed. Whoever typed the name into a note was typing what they call the person, which is as
   * likely to be the Discord nickname as the display name. {@link PersonSearchTargets} registers
   * all three columns as places a person is named, and a scrubber that knew fewer of them than the
   * search did was the two registries disagreeing about the same question.
   *
   * @param subjectId the member the export is about
   * @return the scrubber
   */
  private @NotNull HandleScrubber scrubberForOthers(@NotNull UUID subjectId) {
    List<User> roster = userRepository.findAll();
    List<String> others =
        roster.stream()
            .filter(u -> !u.getId().equals(subjectId))
            .flatMap(HandleSpellings::of)
            .filter(h -> h != null && !h.isBlank())
            .toList();
    // The subject's own names go in as PROTECTED terms rather than being left out. Excluding them
    // entirely is what shredded them: longest-match can only rank terms the matcher knows, so a
    // third party called "Val" beat the subject's own "Valkyrie" and turned it into
    // "#OTHER_MEMBER#kyrie" -- in the subject's own export, reported to the reviewing admin as a
    // third-party redaction.
    List<String> own =
        roster.stream()
            .filter(u -> u.getId().equals(subjectId))
            .flatMap(HandleSpellings::of)
            .filter(h -> h != null && !h.isBlank())
            .toList();
    return new HandleScrubber(others, own);
  }
}
