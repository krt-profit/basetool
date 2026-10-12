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

package de.greluc.krt.profit.basetool.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserApprovalEventRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialClaimRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionOwnershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Section;
import de.greluc.krt.profit.basetool.backend.platform.api.UserErasureParticipant;
import de.greluc.krt.profit.basetool.backend.privacy.api.UserAccountMergeService;
import de.greluc.krt.profit.basetool.backend.privacy.api.UserDeletionService;
import de.greluc.krt.profit.basetool.backend.privacy.internal.ExportSectionDeclarations;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderRepository;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds the three GDPR registries against every column that references a member (REQ-DATA-021): the
 * erasure of {@link UserDeletionService} and its {@link UserErasureParticipant}s, the Art. 15
 * export of the {@link PersonalDataExportSection}s and the account merge of {@link
 * UserAccountMergeService}.
 *
 * <p>A column references a member when it carries a foreign key to {@code app_user}, or when it is
 * a {@code uuid} named {@code user_id}, {@code owner_id}, {@code *_user_id}, {@code *_by}, {@code
 * *_by_id} or {@code *_sub}. Every such column needs a disposition in each registry, or an entry
 * with its reason in the exception lists below; a new module's table without one fails the build.
 */
@SpringBootTest
class GdprParticipantCoverageTest {

  /** Today's count of member-referencing columns; the sweep must see at least this many. */
  private static final int REFERENCE_FLOOR = 56;

  /** Today's count of export sections keyed by a member column; the union must hold this many. */
  private static final int EXPORT_SECTION_FLOOR = 44;

  /** The {@code ON DELETE} actions that let the database itself erase or unlink the reference. */
  private static final Set<Character> ERASED_BY_THE_DATABASE = Set.of('c', 'n', 'd');

  private static final Pattern MEMBER_COLUMN_NAME =
      Pattern.compile("^(user_id|owner_id)$|_user_id$|_by$|_by_id$|_sub$");

  private static final Pattern EXPORT_KEY =
      Pattern.compile(
          "\\bwhere\\s+(?:([a-z_][a-z0-9_]*)\\.)?([a-z_][a-z0-9_]*)\\s*=\\s*:userid\\b");

  private static final JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("de.greluc.krt.profit.basetool.backend");

  private static final Pattern FIRST_FROM = Pattern.compile("\\bfrom\\s+([a-z_][a-z0-9_]*)");

  /**
   * One step of the deletion that resolves a reference the database refuses to drop on its own.
   *
   * @param type the class whose member the deletion calls
   * @param member the method {@link UserDeletionService} or one of the {@link
   *     UserErasureParticipant}s calls
   * @param effect what the step does to the column
   */
  record DeletionStep(@NotNull Class<?> type, @NotNull String member, @NotNull String effect) {}

  /**
   * The member references whose foreign key neither cascades nor nulls, each with the step of
   * {@link UserDeletionService} or of an {@link UserErasureParticipant} that resolves it before the
   * account row goes.
   */
  static final Map<String, DeletionStep> ERASED_BY_THE_DELETION_SERVICE =
      Map.ofEntries(
          Map.entry(
              "app_user.approved_by_id",
              new DeletionStep(UserRepository.class, "clearApprovedBy", "nulled")),
          Map.entry(
              "inventory_item.user_id",
              new DeletionStep(InventoryItemRepository.class, "deleteByUserId", "rows purged")),
          Map.entry(
              "material_claim.claimed_by_user_id",
              new DeletionStep(MaterialClaimRepository.class, "unlinkClaimedByUser", "nulled")),
          Map.entry(
              "mission.owner_id",
              new DeletionStep(
                  MissionRepository.class, "updateOwner", "reassigned to a fallback admin")),
          Map.entry(
              "mission_ownership.owner_id",
              new DeletionStep(
                  MissionOwnershipRepository.class,
                  "updateOwner",
                  "reassigned to a fallback admin")),
          Map.entry(
              "mission_participant.user_id",
              new DeletionStep(MissionParticipantRepository.class, "unlinkUser", "nulled")),
          Map.entry(
              "refinery_order.owner_id",
              new DeletionStep(
                  RefineryOrderRepository.class, "updateOwner", "reassigned to a fallback admin")),
          Map.entry(
              "ship.owner_id",
              new DeletionStep(ShipRepository.class, "deleteByOwnerId", "rows purged")),
          Map.entry(
              "user_approval_event.decided_by_id",
              new DeletionStep(UserApprovalEventRepository.class, "clearDecidedBy", "nulled")),
          Map.entry(
              "user_approval_event.user_id",
              new DeletionStep(UserApprovalEventRepository.class, "deleteByUserId", "rows purged")),
          Map.entry(
              "user_roles.user_id",
              new DeletionStep(
                  UserRepository.class,
                  "delete",
                  "join rows removed with the User entity, which owns the roles association")));

  /** Member references without a foreign key that deliberately outlive the account. */
  static final Map<String, String> RETAINED_AFTER_ERASURE =
      Map.of(
          "audit_event.target_user_id",
          "An audit target outlives the account (REQ-AUDIT-001); a granted erasure anonymises"
              + " the handle snapshots instead (REQ-SEC-062).",
          "bank_audit_event.target_user_id",
          "A bank audit target outlives the account (REQ-AUDIT-001, ADR-0020).",
          "p4k_import_job.created_by",
          "The id of the admin who enqueued a P4K import, a pseudonymous operational record;"
              + " P4kImportJobService.pruneOldJobs drops jobs older than seven days after every"
              + " run.");

  /** Member references no export section selects, each with the reason. */
  static final Map<String, String> NOT_EXPORTED =
      Map.ofEntries(
          Map.entry(
              "app_user.approved_by_id",
              "Names the admin who approved another member's account; the row is the other"
                  + " member's."),
          Map.entry(
              "bank_account_grant.granted_by",
              "Names the bank employee who granted another holder access; the grant is the"
                  + " grantee's."),
          Map.entry(
              "bank_booking_request.decided_by",
              "Names the bank employee who decided another member's request (ADR-0185)."),
          Map.entry(
              "bank_booking_request.owner_approval_granted_by",
              "Names the account owner who approved another member's request (ADR-0185)."),
          Map.entry(
              "bank_transaction.initiated_by",
              "Names the bank employee who booked; deliberately not selected (REQ-SEC-058)."),
          Map.entry(
              "deletion_request.decided_by_id",
              "Names the admin who decided another member's erasure request."),
          Map.entry(
              "user_approval_event.decided_by_id",
              "Names the admin who decided another member's registration (REQ-SEC-058)."),
          Map.entry(
              "exchange_bulk_undo_run.requested_by",
              "Names the admin who started a bulk undo over every member's data."),
          Map.entry(
              "operation_payout_status.paid_out_by_user_id",
              "Names the officer who marked a payout; the payout is the recipient's."),
          Map.entry(
              "job_order_handover.executing_user_id",
              "Names the member who executed a job-order handover, an act on the organisation's"
                  + " order; not selected by any section."),
          Map.entry(
              "job_order_item_handover.executing_user_id",
              "See job_order_handover.executing_user_id."),
          Map.entry(
              "mission_ownership.owner_id",
              "The concurrency companion of mission.owner_id, which missionsOwned exports."),
          Map.entry(
              "p4k_import_job.created_by",
              "The admin who enqueued a catalogue import; operational, pruned after seven days."));

  /** Member references the account merge does not classify, each with the reason. */
  static final Map<String, String> NOT_MERGED =
      Map.of(
          "p4k_import_job.created_by",
          "The admin who enqueued a catalogue import; operational, pruned after seven days, and"
              + " never the subject of a merge.");

  @MockitoBean private RoleRepository roleRepository;

  @MockitoBean private SquadronRepository squadronRepository;

  @Autowired private DataSource dataSource;

  @Autowired private UserAccountMergeService mergeService;

  @Test
  @DisplayName("every member reference is erased by the database, the deletion, or retained")
  void everyMemberReferenceHasAnErasureDisposition() {
    Map<String, Character> references = memberReferences(new JdbcTemplate(dataSource));
    assertThat(references)
        .as("member-referencing columns (a sweep matching none would pass vacuously)")
        .hasSizeGreaterThanOrEqualTo(REFERENCE_FLOOR);

    assertThat(erasureGaps(references))
        .as(
            """
            Member references the account deletion cannot resolve. A foreign key without ON \
            DELETE CASCADE or SET NULL makes UserDeletionService fail; a column without a foreign \
            key outlives the account unseen. Give the key an ON DELETE clause, resolve it in \
            UserDeletionService or an UserErasureParticipant of the owning module and list the \
            step in ERASED_BY_THE_DELETION_SERVICE, or list a column that must outlive the account \
            in RETAINED_AFTER_ERASURE with its reason.\
            """)
        .isEmpty();
    assertThat(staleKeys(ERASED_BY_THE_DELETION_SERVICE.keySet(), references.keySet()))
        .as("deletion steps for columns that no longer exist")
        .isEmpty();
    assertThat(staleKeys(RETAINED_AFTER_ERASURE.keySet(), references.keySet()))
        .as("retained columns that no longer exist")
        .isEmpty();
  }

  @Test
  @DisplayName("every listed deletion step is a call the deletion or a participant really makes")
  void everyDeletionStepIsCalledByTheDeletionService() {
    assertThat(missingDeletionCalls(ERASED_BY_THE_DELETION_SERVICE))
        .as("deletion steps neither UserDeletionService nor an erasure participant calls")
        .isEmpty();
  }

  @Test
  @DisplayName("every member reference is exported or listed as not exported")
  void everyMemberReferenceHasAnExportDisposition() {
    Map<String, Character> references = memberReferences(new JdbcTemplate(dataSource));
    Set<String> exported =
        exportedKeys(new JdbcTemplate(dataSource), ExportSectionDeclarations.inReadingOrder());
    assertThat(exported)
        .as("export section keys (an emptied registry would pass vacuously)")
        .hasSizeGreaterThanOrEqualTo(EXPORT_SECTION_FLOOR);

    assertThat(exportGaps(references.keySet(), exported))
        .as(
            """
            Member references the Art. 15 export (REQ-SEC-058) does not answer for. Add a section \
            to the owning module's PersonalDataExportSection, or list the column in NOT_EXPORTED with \
            its reason.\
            """)
        .isEmpty();
    assertThat(staleKeys(NOT_EXPORTED.keySet(), references.keySet()))
        .as("not-exported columns that no longer exist")
        .isEmpty();
    Set<String> contradicted = new TreeSet<>(NOT_EXPORTED.keySet());
    contradicted.retainAll(exported);
    assertThat(contradicted).as("columns listed as not exported that a section exports").isEmpty();
  }

  @Test
  @Transactional
  @DisplayName("every member reference is classified by the account merge or listed")
  void everyMemberReferenceHasAMergeDisposition() {
    Map<String, Character> references = memberReferences(new JdbcTemplate(dataSource));
    assertThat(mergeGaps(references.keySet()))
        .as(
            """
            Member references the account merge (REQ-SEC-046) has no opinion about. Declare the \
            column in the owning module's UserReassignmentParticipant, or list it in NOT_MERGED \
            with its reason.\
            """)
        .isEmpty();
    assertThat(staleKeys(NOT_MERGED.keySet(), references.keySet()))
        .as("not-merged columns that no longer exist")
        .isEmpty();
  }

  @Test
  @Transactional
  @DisplayName("proof: a planted table referencing a member is reported by all three registries")
  void aPlantedMemberReferenceIsReported() throws SQLException {
    Map<String, Character> references =
        RolledBackTransaction.run(
            dataSource,
            jdbc -> {
              jdbc.execute(
                  """
                  CREATE TABLE g11_planted (
                      id UUID PRIMARY KEY,
                      member_id UUID REFERENCES app_user (id),
                      reviewed_by UUID
                  )
                  """);
              return memberReferences(jdbc);
            });
    Set<String> planted = Set.of("g11_planted.member_id", "g11_planted.reviewed_by");
    assertThat(references).containsKeys("g11_planted.member_id", "g11_planted.reviewed_by");
    assertThat(erasureGaps(references)).containsAll(planted);
    assertThat(exportGaps(references.keySet(), Set.of())).containsAll(planted);
    assertThat(mergeGaps(references.keySet())).containsAll(planted);
  }

  @Test
  @DisplayName("proof: a section a participant declares for a planted column closes its gap")
  void aSectionAParticipantDeclaresClosesTheGapOfItsColumn() throws SQLException {
    Section planted =
        new Section(
            9000,
            "g11Planted",
            LegalBasis.ART_15,
            "A planted section that exports the planted member column.",
            "SELECT member_id FROM g11_planted WHERE member_id = :userId");
    PersonalDataExportSection participant = () -> List.of(planted);

    Set<String> gaps =
        RolledBackTransaction.run(
            dataSource,
            jdbc -> {
              jdbc.execute(
                  """
                  CREATE TABLE g11_planted (
                      id UUID PRIMARY KEY,
                      member_id UUID REFERENCES app_user (id)
                  )
                  """);
              Map<String, Character> references = memberReferences(jdbc);
              Set<String> declared =
                  exportedKeys(
                      jdbc,
                      ExportSectionDeclarations.inReadingOrder(
                          java.util.stream.Stream.concat(
                                  ExportSectionDeclarations.participants().stream(),
                                  java.util.stream.Stream.of(participant))
                              .toList()));
              return exportGaps(references.keySet(), declared);
            });

    assertThat(gaps).doesNotContain("g11_planted.member_id");
  }

  @Test
  @DisplayName("proof: a deletion step the service never calls is reported")
  void aDeletionStepTheServiceNeverCallsIsReported() {
    Map<String, DeletionStep> planted =
        Map.of(
            "g11_planted.member_id",
            new DeletionStep(UserRepository.class, "deleteAllInBatch", "rows purged"));
    assertThat(missingDeletionCalls(planted)).containsExactly("g11_planted.member_id");
  }

  @Test
  @DisplayName("proof: a step only a participant performs is found")
  void aStepOnlyAParticipantPerformsIsFound() {
    Map<String, DeletionStep> steps =
        Map.of(
            "ship.owner_id",
            new DeletionStep(ShipRepository.class, "deleteByOwnerId", "rows purged"));
    assertThat(missingDeletionCalls(steps)).isEmpty();
  }

  /**
   * Returns every member-referencing column with its foreign key's {@code ON DELETE} action, or
   * {@code null} when it has no foreign key.
   *
   * @param jdbc the template to query with
   * @return {@code table.column} mapped to the action code of {@code pg_constraint.confdeltype}
   */
  static @NotNull Map<String, Character> memberReferences(@NotNull JdbcTemplate jdbc) {
    Map<String, Character> references = new TreeMap<>();
    jdbc.query(
        """
        SELECT src.relname || '.' || att.attname, con.confdeltype::text
        FROM pg_constraint con
        JOIN pg_class src ON src.oid = con.conrelid
        JOIN pg_class tgt ON tgt.oid = con.confrelid
        JOIN unnest(con.conkey) AS k(attnum) ON TRUE
        JOIN pg_attribute att ON att.attrelid = src.oid AND att.attnum = k.attnum
        WHERE con.contype = 'f' AND tgt.relname = 'app_user'
        """,
        rs -> {
          references.put(rs.getString(1), rs.getString(2).charAt(0));
        });
    jdbc.query(
        """
        SELECT c.table_name, c.column_name
        FROM information_schema.columns c
        JOIN information_schema.tables t
          ON t.table_schema = c.table_schema
         AND t.table_name = c.table_name
         AND t.table_type = 'BASE TABLE'
        WHERE c.table_schema = current_schema() AND c.data_type = 'uuid'
        """,
        rs -> {
          String column = rs.getString(2);
          if (MEMBER_COLUMN_NAME.matcher(column).find()) {
            references.putIfAbsent(rs.getString(1) + "." + column, null);
          }
        });
    return references;
  }

  private static Set<String> erasureGaps(Map<String, Character> references) {
    Set<String> gaps = new TreeSet<>();
    references.forEach(
        (column, action) -> {
          boolean handled =
              action == null
                  ? RETAINED_AFTER_ERASURE.containsKey(column)
                  : ERASED_BY_THE_DATABASE.contains(action)
                      || ERASED_BY_THE_DELETION_SERVICE.containsKey(column);
          if (!handled) {
            gaps.add(column);
          }
        });
    return gaps;
  }

  private static Set<String> missingDeletionCalls(Map<String, DeletionStep> steps) {
    Set<String> calls = new TreeSet<>();
    for (JavaClass type : PRODUCTION) {
      if (type.isEquivalentTo(UserDeletionService.class)
          || (!type.isInterface() && type.isAssignableTo(UserErasureParticipant.class))) {
        for (JavaMethodCall call : type.getMethodCallsFromSelf()) {
          calls.add(call.getTargetOwner().getName() + "#" + call.getName());
        }
      }
    }
    Set<String> missing = new TreeSet<>();
    steps.forEach(
        (column, step) -> {
          if (!calls.contains(step.type().getName() + "#" + step.member())) {
            missing.add(column);
          }
        });
    return missing;
  }

  private static Set<String> exportedKeys(JdbcTemplate jdbc, List<Section> declared) {
    Set<String> tables =
        new TreeSet<>(
            jdbc.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
                """,
                String.class));
    Set<String> keys = new TreeSet<>();
    for (Section section : declared) {
      String key = exportKey(section.sql(), tables);
      if (key != null) {
        keys.add(key);
      }
    }
    return keys;
  }

  static @Nullable String exportKey(String sql, Set<String> tables) {
    String text = SqlReferences.scannable(sql);
    Matcher where = EXPORT_KEY.matcher(text);
    Matcher from = FIRST_FROM.matcher(text);
    if (!where.find() || !from.find()) {
      return null;
    }
    String table =
        where.group(1) == null
            ? from.group(1)
            : SqlReferences.aliases(sql, tables).getOrDefault(where.group(1), where.group(1));
    return table + "." + where.group(2);
  }

  private static Set<String> exportGaps(Set<String> references, Set<String> exported) {
    Set<String> gaps = new TreeSet<>(references);
    gaps.removeAll(exported);
    gaps.removeAll(NOT_EXPORTED.keySet());
    return gaps;
  }

  private Set<String> mergeGaps(Set<String> references) {
    Set<String> gaps = new TreeSet<>(references);
    gaps.removeAll(mergeService.followedColumns());
    gaps.removeAll(mergeService.staysWithTheAct());
    gaps.removeAll(NOT_MERGED.keySet());
    return gaps;
  }

  private static Set<String> staleKeys(Set<String> listed, Set<String> present) {
    Set<String> stale = new TreeSet<>(listed);
    stale.removeAll(present);
    return stale;
  }
}
