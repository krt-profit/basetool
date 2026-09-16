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

import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.DataExportSections;
import de.greluc.krt.profit.basetool.backend.support.DataExportSections.Section;
import de.greluc.krt.profit.basetool.backend.support.HandleScrubber;
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
import java.util.Set;
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

  @PersistenceContext private EntityManager entityManager;

  /**
   * One section of an assembled export.
   *
   * @param key the stable machine key, also the label key
   * @param legalBasis {@code ART_15} or {@code ART_15_20}
   * @param portableHint why the section is or is not portable
   * @param rows the rows, each a column-name to value map in the statement's own column order
   * @param truncated whether {@link #MAX_ROWS_PER_SECTION} was reached
   */
  public record ExportSection(
      String key,
      String legalBasis,
      String portableHint,
      List<Map<String, Object>> rows,
      boolean truncated) {}

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
    Set<String> freeText = Set.copyOf(DataExportSections.FREE_TEXT_SECTIONS);

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
      boolean scrub = freeText.contains(section.key()) && scrubber.isActive();
      for (Object item : raw.subList(0, Math.min(raw.size(), MAX_ROWS_PER_SECTION))) {
        Tuple tuple = (Tuple) item;
        Map<String, Object> row = new LinkedHashMap<>();
        for (TupleElement<?> element : tuple.getElements()) {
          String name = element.getAlias();
          Object value = tuple.get(name);
          if (scrub && value instanceof String text) {
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
      sections.add(
          new ExportSection(
              section.key(), section.legalBasis(), section.portableHint(), rows, truncated));
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
   * A scrubber loaded with every member's handle <em>except</em> the subject's.
   *
   * <p>Excluding the subject is not an optimisation: scrubbing their own name would remove the one
   * name the export is supposed to be about, and would do it from the entries they wrote
   * themselves.
   *
   * @param subjectId the member the export is about
   * @return the scrubber
   */
  private @NotNull HandleScrubber scrubberForOthers(@NotNull UUID subjectId) {
    List<String> others =
        userRepository.findAll().stream()
            .filter(u -> !u.getId().equals(subjectId))
            .map(User::getEffectiveName)
            .filter(h -> h != null && !h.isBlank())
            .toList();
    return new HandleScrubber(others);
  }
}
