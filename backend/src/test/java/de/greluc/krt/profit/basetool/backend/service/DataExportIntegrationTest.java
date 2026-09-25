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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.AuditEvent;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.PersonalInventoryItem;
import de.greluc.krt.profit.basetool.backend.model.PersonalInventoryLocationType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.AuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalInventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.DataExportSections;
import de.greluc.krt.profit.basetool.backend.support.HandleScrubber;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration coverage for the Art. 15 / Art. 20 data export against the real Postgres test
 * container (REQ-SEC-058).
 *
 * <p>The property this class exists for is the one the handover plan called the hardest thing to
 * get subtly wrong, and asked to be treated as a test rather than a review comment: <b>no other
 * member's handle appears in an export.</b> It is asserted here across the whole document, not per
 * section, because a leak introduced by a future section would otherwise only be caught by somebody
 * re-reading that section's SQL.
 *
 * <p>It also runs all ~29 statements against the real schema. A registry of hand-written SQL is
 * exactly the kind of thing that compiles and type-checks while naming a column that was renamed
 * two migrations ago, and the first person to find out must not be somebody answering a legal
 * request.
 */
@SpringBootTest
@ActiveProfiles("test")
class DataExportIntegrationTest {

  /**
   * Distinctive enough that a match cannot be a coincidence, and long enough to clear the
   * scrubber's minimum length.
   */
  private static final String OTHER_HANDLE = "ZzzOtherMemberHandleZzz";

  /**
   * A person who has <em>no account</em> — a job order's external contact, which is the common case
   * for {@code job_order.handle}.
   *
   * <p>The distinction is the point of {@link #anAuditSubjectLabelNeverReachesTheExport()}: {@link
   * HandleScrubber} is built from the roster, so it can never match this name. Only the projection
   * can keep it out, which is why the column is dropped rather than scrubbed.
   */
  private static final String EXTERNAL_CONTACT = "ZzzExternalContactZzz";

  @Autowired private DataExportService dataExportService;
  @Autowired private DataExportReportService dataExportReportService;
  @Autowired private UserRepository userRepository;
  @Autowired private PersonalInventoryItemRepository personalInventoryItemRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  private final Set<UUID> seededUsers = new HashSet<>();
  private final Set<UUID> seededItems = new HashSet<>();
  private final Set<UUID> seededAuditEvents = new HashSet<>();

  /**
   * Removes what this class committed into the container the whole suite shares.
   *
   * <p>The items go first: deleting the user cascades them, but doing it explicitly keeps the
   * cleanup true if that cascade ever changes.
   */
  @AfterEach
  void cleanUp() {
    transactionTemplate.executeWithoutResult(
        status -> {
          personalInventoryItemRepository.deleteAllById(seededItems);
          auditEventRepository.deleteAllById(seededAuditEvents);
          userRepository.deleteAllById(seededUsers);
          seededItems.clear();
          seededAuditEvents.clear();
          seededUsers.clear();
        });
  }

  /**
   * Creates a committed user.
   *
   * @param username the username, which is also the effective name
   * @return the new user's id
   */
  private UUID user(String username) {
    UUID id = UUID.randomUUID();
    transactionTemplate.executeWithoutResult(
        status -> {
          User u = new User();
          u.setId(id);
          u.setUsername(username);
          userRepository.save(u);
        });
    seededUsers.add(id);
    return id;
  }

  /**
   * Creates a committed personal-inventory item with a free-text note.
   *
   * @param owner the owning member
   * @param note the note, which the export scrubs
   */
  private void personalItem(UUID owner, String note) {
    transactionTemplate.executeWithoutResult(
        status -> {
          PersonalInventoryItem item = new PersonalInventoryItem();
          item.setOwnerUserId(owner);
          item.setName("Probe");
          item.setQuantity(1);
          item.setNote(note);
          item.setLocationUexId(1);
          item.setLocationType(PersonalInventoryLocationType.values()[0]);
          item.setLocationNameSnapshot("Testort");
          seededItems.add(personalInventoryItemRepository.save(item).getId());
        });
  }

  /**
   * Creates a committed audit row carrying a subject label, in the shape the job-order trails
   * write: {@code #<displayId> '<handle>'}.
   *
   * @param actor the acting member, or {@code null} when the member is only the target
   * @param target the member the action was performed on, or {@code null}
   * @param subjectLabel the label to snapshot, which is what this test is about
   */
  private void auditEvent(UUID actor, UUID target, String subjectLabel) {
    transactionTemplate.executeWithoutResult(
        status -> {
          AuditEvent event =
              AuditEvent.builder()
                  .occurredAt(Instant.now())
                  .domain(AuditEventType.JOB_ORDER_CREATED.domain())
                  .eventType(AuditEventType.JOB_ORDER_CREATED)
                  .actorUserId(actor)
                  .actorHandle("ZzzActorZzz")
                  .subjectLabel(subjectLabel)
                  .targetUserId(target)
                  .build();
          seededAuditEvents.add(auditEventRepository.save(event).getId());
        });
  }

  /**
   * The rows of one section, by key.
   *
   * @param export the assembled export
   * @param key the section key
   * @return that section's rows
   * @throws java.util.NoSuchElementException when no section carries the key
   */
  private static List<Map<String, Object>> sectionRows(
      DataExportService.DataExport export, String key) {
    return export.sections().stream()
        .filter(s -> s.key().equals(key))
        .findFirst()
        .orElseThrow()
        .rows();
  }

  /**
   * Every string value anywhere in the export, flattened.
   *
   * @param export the assembled export
   * @return the values
   */
  private static List<String> allStrings(DataExportService.DataExport export) {
    return export.sections().stream()
        .flatMap(s -> s.rows().stream())
        .flatMap(r -> r.values().stream())
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .toList();
  }

  @Test
  void everySectionRunsAndIsReported() {
    UUID subject = user("ZzzSubjectZzz");

    DataExportService.DataExport export = dataExportService.export(subject);

    assertThat(export.sections()).hasSameSizeAs(DataExportSections.SECTIONS);
    assertThat(export.subjectId()).isEqualTo(subject);
    assertThat(export.subjectHandle()).isEqualTo("ZzzSubjectZzz");
    assertThat(export.sections().stream().filter(s -> s.key().equals("account")).findFirst())
        .get()
        .satisfies(s -> assertThat(s.rows()).hasSize(1));
  }

  @Test
  void everySectionCarriesItsLegalBasis() {
    UUID subject = user("ZzzBasisZzz");

    DataExportService.DataExport export = dataExportService.export(subject);

    assertThat(export.sections())
        .allSatisfy(
            s ->
                assertThat(s.legalBasis())
                    .isIn(DataExportSections.ART_15, DataExportSections.ART_15_20));
    assertThat(export.sections().stream().map(DataExportService.ExportSection::key).toList())
        .containsExactlyElementsOf(
            DataExportSections.SECTIONS.stream().map(DataExportSections.Section::key).toList());
  }

  @Test
  void noOtherMembersHandleAppearsInTheExport() {
    UUID subject = user("ZzzSubjectTwoZzz");
    user(OTHER_HANDLE);
    personalItem(subject, "Uebergabe an " + OTHER_HANDLE + " erledigt");

    DataExportService.DataExport export = dataExportService.export(subject);

    assertThat(allStrings(export))
        .withFailMessage(
            "An export leaked another member's handle. The projections in DataExportSections must "
                + "not select another member's handle column, and free text must go through "
                + "HandleScrubber.")
        .noneMatch(v -> v.contains(OTHER_HANDLE));
    assertThat(export.thirdPartyHandlesRemoved()).isTrue();
  }

  @Test
  void theMembersOwnFreeTextSurvivesWithTheOtherNameReplaced() {
    UUID subject = user("ZzzSubjectThreeZzz");
    user(OTHER_HANDLE);
    personalItem(subject, "Uebergabe an " + OTHER_HANDLE + " erledigt");

    DataExportService.DataExport export = dataExportService.export(subject);

    List<Map<String, Object>> rows = sectionRows(export, "personalInventory");
    assertThat(rows).hasSize(1);
    String note = String.valueOf(rows.get(0).get("note"));
    assertThat(note).contains("Uebergabe an").contains("erledigt");
    assertThat(note).contains(HandleScrubber.REPLACEMENT);
  }

  @Test
  void theSubjectsOwnHandleIsNotScrubbed() {
    UUID subject = user("ZzzSelfNamedZzz");
    user("ZzzSelf");
    personalItem(subject, "Notiz von ZzzSelfNamedZzz");

    DataExportService.DataExport export = dataExportService.export(subject);

    assertThat(allStrings(export)).anyMatch(v -> v.contains("ZzzSelfNamedZzz"));
    assertThat(allStrings(export))
        .as("and no placeholder was spliced into it")
        .noneMatch(v -> v.contains(HandleScrubber.REPLACEMENT));
    assertThat(export.thirdPartyHandlesRemoved())
        .as("nothing was replaced, so the Art. 15(4) reviewer is not told there was")
        .isFalse();
  }

  @Test
  void anAuditSubjectLabelNeverReachesTheExport() {
    UUID subject = user("ZzzSubjectFourZzz");
    user(OTHER_HANDLE);
    auditEvent(subject, null, "#4711 '" + EXTERNAL_CONTACT + "'");
    auditEvent(null, subject, OTHER_HANDLE);

    DataExportService.DataExport export = dataExportService.export(subject);

    assertThat(allStrings(export))
        .withFailMessage(
            "An audit subject label reached the export. audit_event.subject_label is a person for "
                + "the job-order and account-deletion trails, and HandleScrubber cannot rescue it: "
                + "it knows registered members only, so an external contact and an already-deleted "
                + "member are both invisible to it. The column must stay unselected.")
        .noneMatch(v -> v.contains(EXTERNAL_CONTACT) || v.contains(OTHER_HANDLE));

    assertThat(sectionRows(export, "auditActionsByMember")).hasSize(1);
    assertThat(sectionRows(export, "auditActionsOnMember")).hasSize(1);
    assertThat(sectionRows(export, "auditActionsByMember").get(0))
        .containsKeys("occurred_at", "domain", "event_type")
        .doesNotContainKey("subject_label");
  }

  @Test
  void noSectionSelectsTheAuditSubjectLabel() {
    assertThat(DataExportSections.SECTIONS)
        .withFailMessage(
            "A section selects subject_label. It is a person for the job-order and "
                + "account-deletion trails, and adding the section to FREE_TEXT_SECTIONS does not "
                + "fix it -- the scrubber is built from the roster and cannot see a non-member.")
        .noneMatch(section -> section.sql().contains("subject_label"));
  }

  @Test
  void thePdfRenders() {
    UUID subject = user("ZzzPdfZzz");

    DataExportService.DataExport export = dataExportService.export(subject);
    byte[] pdf = dataExportReportService.renderPdf(export);

    assertThat(export.totalRows()).isNotNegative();
    assertThat(pdf).isNotEmpty();
    assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1))
        .isEqualTo("%PDF-");
  }
}
