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

package de.greluc.krt.profit.basetool.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.backend.architecture.MassAssignmentGuardRules.Component;
import de.greluc.krt.profit.basetool.backend.architecture.MassAssignmentGuardRules.Handler;
import de.greluc.krt.profit.basetool.backend.controller.JobOrderController.ReassignResponsibleOrgUnitRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.AddExternalParticipantRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.BereichDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkOrgUnitChangeRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkRebookRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateClaimDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.FrequencyTypeDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemOrgUnitChangeDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemPersonalRebookDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemProductionCreateDto.BookInDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobTypeDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialCategoryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest.SpecialCommandChange;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitParentUpdateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.OrganisationsleitungDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryOrderStoreItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefiningMethodDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SpecialCommandDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronDto;
import de.greluc.krt.profit.basetool.backend.model.dto.StarSystemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.TerminalDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateParticipantRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankDepositRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankWithdrawalRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateBankAccountRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateMissionRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.PatchMissionCoreRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateMissionOwningOrgUnitRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateMissionRequest;
import de.greluc.krt.profit.basetool.backend.operation.internal.OperationUpdateDto;
import de.greluc.krt.profit.basetool.backend.orgchart.internal.OrgChartPositionCreateRequest;
import de.greluc.krt.profit.basetool.guardfixture.massassignment.FixtureMassAssignmentController;
import de.greluc.krt.profit.basetool.guardfixture.massassignment.FixtureNestedRequest;
import de.greluc.krt.profit.basetool.guardfixture.massassignment.FixtureOrderDto;
import java.beans.Introspector;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

/**
 * G-06 mass-assignment guards (REQ-SEC-077, REQ-API-015): no type is both returned and bound as a
 * request body beyond the reviewed dual-use list, no request type carries a server-managed
 * component beyond the reviewed client inputs, and every request body is {@code @Valid}.
 *
 * <p>Each dual-use type lists which of its server-managed components are proven unwritable by
 * {@link DualUseRequestBodyProofTest} and which are deliberate client input. Each rule is proven
 * able to fail on the planted fixtures in {@code
 * de.greluc.krt.profit.basetool.guardfixture.massassignment}, and each selection carries a floor.
 */
class MassAssignmentGuardTest {

  private static final JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("de.greluc.krt.profit.basetool.backend");

  private static final JavaClasses FIXTURES =
      new ClassFileImporter()
          .importPackages("de.greluc.krt.profit.basetool.guardfixture.massassignment");

  private static final String STAMP =
      "create-time stamp, resolved by OwnerScopeService.resolveOrgUnitForPickerOutput"
          + " (REQ-ORG-004)";

  private static final String MOVE_TARGET =
      "target unit of a move, which InventoryOrgUnitChangeService requires to be a membership of"
          + " the caller";

  private static final String REBOOK_TARGET =
      "stamp of the rebooked stock, resolved by OwnerScopeService.resolveOrgUnitForPickerOutput";

  private static final String JOB_ORDER_UNITS =
      "processing and customer unit, validated by JobOrderOrgUnitResolver (REQ-ORG-003)";

  private static final String AFFILIATION =
      "the participant's declared affiliation, resolved by MissionParticipantService; grants no"
          + " scope";

  private static final String COUNTERPARTY =
      "a counterparty snapshot, resolved against the caller's counterparty options";

  private static final String LIFECYCLE =
      "the lifecycle status the editor sets on the section; the endpoint gates on the scope"
          + " service";

  /**
   * A reviewed dual-use type.
   *
   * @param reason why the type cannot be split now
   * @param proven the server-managed components {@link DualUseRequestBodyProofTest} proves
   *     unwritable
   * @param clientInputs the server-managed-looking components that are deliberate client input,
   *     with the check that validates each
   */
  private record DualUse(
      @NotNull String reason,
      @NotNull Set<String> proven,
      @NotNull Map<String, String> clientInputs) {}

  private static final String FROZEN =
      "a frozen OpenAPI schema; splitting it renames a schema the app consumes";

  /** The types that are both returned and bound as a request body today, each reviewed. */
  private static final Map<Class<?>, DualUse> DUAL_USE =
      Map.ofEntries(
          Map.entry(
              BereichDto.class,
              new DualUse(
                  FROZEN
                      + "; the create passes only name, shorthand, description, parent and"
                      + " department",
                  Set.of("id"),
                  Map.of("parentOrgUnitId", "the parent the ADMIN chooses (REQ-ORG-014)"))),
          Map.entry(
              FrequencyTypeDto.class,
              new DualUse(FROZEN + "; create resets id and version", Set.of("id"), Map.of())),
          Map.entry(
              JobTypeDto.class,
              new DualUse(FROZEN + "; create resets id and version", Set.of("id"), Map.of())),
          Map.entry(
              LocationDto.class,
              new DualUse(
                  FROZEN + "; create strips server-managed fields", Set.of("id"), Map.of())),
          Map.entry(
              MaterialCategoryDto.class,
              new DualUse(FROZEN + "; create resets id and version", Set.of("id"), Map.of())),
          Map.entry(
              MaterialDto.class,
              new DualUse(
                  FROZEN + "; the update loads the row by the path id", Set.of("id"), Map.of())),
          Map.entry(
              OrganisationsleitungDto.class,
              new DualUse(
                  FROZEN + "; the create passes only name, shorthand and description",
                  Set.of("id"),
                  Map.of())),
          Map.entry(
              RefineryOrderDto.class,
              new DualUse(
                  FROZEN + " and the app's refinery contract",
                  Set.of("id", "owningSquadron"),
                  Map.of(
                      "owner",
                      "create on behalf, honoured only when"
                          + " RefineryAccessPolicy.canManageUserRefineryOrders admits the caller"
                          + " (REQ-SEC-005); the proof forges an owner outside the caller's units",
                      "status",
                      "forced to OPEN on create; client-settable on the edit form by owner"
                          + " decision",
                      "owningOrgUnitId",
                      STAMP))),
          Map.entry(
              RefiningMethodDto.class,
              new DualUse(FROZEN + "; create resets id and version", Set.of("id"), Map.of())),
          Map.entry(
              SpecialCommandDto.class,
              new DualUse(FROZEN + "; create resets id and version", Set.of("id"), Map.of())),
          Map.entry(
              SquadronDto.class,
              new DualUse(FROZEN + "; create resets id and version", Set.of("id"), Map.of())),
          Map.entry(
              StarSystemDto.class,
              new DualUse(FROZEN + "; create resets id and version", Set.of("id"), Map.of())),
          Map.entry(
              TerminalDto.class,
              new DualUse(
                  FROZEN + "; the update applies only the hidden flag",
                  Set.of("id", "uexSyncedAt"),
                  Map.of())));

  /** Server-managed-looking components of request types that are deliberate client input. */
  private static final Map<Class<?>, Map<String, String>> REVIEWED_CLIENT_INPUTS =
      Map.ofEntries(
          Map.entry(
              ReassignResponsibleOrgUnitRequest.class,
              Map.of("responsibleOrgUnitId", JOB_ORDER_UNITS)),
          Map.entry(AddExternalParticipantRequest.class, Map.of("orgUnitIds", AFFILIATION)),
          Map.entry(UpdateParticipantRequest.class, Map.of("orgUnitIds", AFFILIATION)),
          Map.entry(BulkOrgUnitChangeRequest.class, Map.of("targetOwningOrgUnitId", MOVE_TARGET)),
          Map.entry(
              InventoryItemOrgUnitChangeDto.class, Map.of("targetOwningOrgUnitId", MOVE_TARGET)),
          Map.entry(BulkRebookRequest.class, Map.of("targetOwningOrgUnitId", REBOOK_TARGET)),
          Map.entry(InventoryItemBookOutDto.class, Map.of("targetOwningOrgUnitId", REBOOK_TARGET)),
          Map.entry(
              InventoryItemPersonalRebookDto.class, Map.of("targetOwningOrgUnitId", REBOOK_TARGET)),
          Map.entry(
              CreateClaimDto.class,
              Map.of(
                  "claimingOrgUnitId",
                  "the claiming unit, authorised by MaterialClaimService.assertCanManage")),
          Map.entry(
              CreateJobOrderDto.class,
              Map.of(
                  "responsibleOrgUnitId", JOB_ORDER_UNITS, "requestingOrgUnitId", JOB_ORDER_UNITS)),
          Map.entry(
              CreateJobOrderItemRequestDto.class,
              Map.of(
                  "responsibleOrgUnitId", JOB_ORDER_UNITS, "requestingOrgUnitId", JOB_ORDER_UNITS)),
          Map.entry(InventoryItemCreateDto.class, Map.of("owningOrgUnitId", STAMP)),
          Map.entry(ShipRequestDto.class, Map.of("owningOrgUnitId", STAMP)),
          Map.entry(RefineryOrderStoreItemDto.class, Map.of("owningOrgUnitId", STAMP)),
          Map.entry(
              BookInDto.class,
              Map.of(
                  "owningOrgUnitId",
                  STAMP,
                  "ownerUserId",
                  "the member the produced item is booked to; the endpoint gates on"
                      + " canEditJobOrder")),
          Map.entry(
              OperationCreateDto.class, Map.of("owningOrgUnitId", STAMP, "status", LIFECYCLE)),
          Map.entry(OperationUpdateDto.class, Map.of("status", LIFECYCLE)),
          Map.entry(
              CreateMissionRequest.class, Map.of("owningOrgUnitId", STAMP, "status", LIFECYCLE)),
          Map.entry(UpdateMissionRequest.class, Map.of("status", LIFECYCLE)),
          Map.entry(PatchMissionCoreRequest.class, Map.of("status", LIFECYCLE)),
          Map.entry(
              UpdateMissionOwningOrgUnitRequest.class,
              Map.of(
                  "owningOrgUnitId",
                  "the reassignment target, validated by MissionService (REQ-ORG-018)")),
          Map.entry(
              OrgChartPositionCreateRequest.class,
              Map.of("orgUnitId", "the unit an ADMIN-edited org-chart position describes")),
          Map.entry(
              OrgUnitParentUpdateRequest.class,
              Map.of("parentOrgUnitId", "the parent an ADMIN chooses (REQ-ORG-014)")),
          Map.entry(
              SpecialCommandChange.class,
              Map.of("orgUnitId", "the unit of an ADMIN membership change")),
          Map.entry(BankDepositRequest.class, Map.of("counterpartyOrgUnitId", COUNTERPARTY)),
          Map.entry(BankWithdrawalRequest.class, Map.of("counterpartyOrgUnitId", COUNTERPARTY)),
          Map.entry(CreateBankBookingRequest.class, Map.of("counterpartyOrgUnitId", COUNTERPARTY)),
          Map.entry(UpdateBankBookingRequest.class, Map.of("counterpartyOrgUnitId", COUNTERPARTY)),
          Map.entry(
              CreateBankAccountRequest.class,
              Map.of("orgUnitId", "the account holder BANK_MANAGEMENT chooses")));

  private static final int HANDLER_FLOOR = 573;

  private static final int REQUEST_BODY_TYPE_FLOOR = 161;

  private static final int REQUEST_BODY_FLOOR = 208;

  @Test
  void noReturnedTypeIsBoundAsARequestBodyBeyondTheReviewedList() {
    List<Handler> handlers = MassAssignmentGuardRules.handlers(PRODUCTION);

    assertThat(MassAssignmentGuardRules.dualUseViolations(handlers, DUAL_USE.keySet())).isEmpty();
    assertThat(handlers).hasSizeGreaterThanOrEqualTo(HANDLER_FLOOR);
    assertThat(MassAssignmentGuardRules.requestBodyTypes(handlers))
        .hasSizeGreaterThanOrEqualTo(REQUEST_BODY_TYPE_FLOOR);
  }

  @Test
  void requestTypesCarryNoServerManagedComponentBeyondTheReviewedInputs() {
    assertThat(
            MassAssignmentGuardRules.serverManagedComponentViolations(
                MassAssignmentGuardRules.handlers(PRODUCTION),
                DUAL_USE.keySet(),
                REVIEWED_CLIENT_INPUTS))
        .isEmpty();
  }

  @Test
  void everyDualUseTypeAccountsForEachServerManagedComponentAndHasAProof() {
    Set<String> proofMethods = new LinkedHashSet<>();
    for (Method method : DualUseRequestBodyProofTest.class.getDeclaredMethods()) {
      if (method.isAnnotationPresent(Test.class)) {
        proofMethods.add(method.getName());
      }
    }
    for (Map.Entry<Class<?>, DualUse> entry : DUAL_USE.entrySet()) {
      Set<String> flagged = new LinkedHashSet<>();
      for (Component component : MassAssignmentGuardRules.components(entry.getKey())) {
        if (MassAssignmentGuardRules.serverManagedReason(component.name(), true, false) != null) {
          flagged.add(component.name());
        }
      }
      Set<String> accounted = new LinkedHashSet<>(entry.getValue().proven());
      accounted.addAll(entry.getValue().clientInputs().keySet());
      assertThat(flagged)
          .as("server-managed components of %s", entry.getKey())
          .isEqualTo(accounted);

      String prefix = Introspector.decapitalize(entry.getKey().getSimpleName());
      assertThat(proofMethods)
          .as("a DualUseRequestBodyProofTest method proving %s", entry.getKey().getSimpleName())
          .anySatisfy(name -> assertThat(name).startsWith(prefix));
    }
  }

  @Test
  void everyRequestBodyIsValidated() {
    MassAssignmentGuardRules.ValidationReport report =
        MassAssignmentGuardRules.unvalidatedBodies(MassAssignmentGuardRules.handlers(PRODUCTION));

    assertThat(report.violations()).isEmpty();
    assertThat(report.bodies()).isGreaterThanOrEqualTo(REQUEST_BODY_FLOOR);
  }

  @Test
  void dualUseRuleFailsOnTheFixtureAndOnAStaleEntry() {
    List<Handler> handlers = MassAssignmentGuardRules.handlers(FIXTURES);

    assertThat(MassAssignmentGuardRules.dualUseViolations(handlers, Set.of()))
        .singleElement()
        .satisfies(v -> assertThat(v).startsWith(FixtureOrderDto.class.getName() + " is returned"));
    assertThat(
            MassAssignmentGuardRules.dualUseViolations(
                handlers, Set.of(FixtureOrderDto.class, FixtureNestedRequest.class)))
        .containsExactly(
            FixtureNestedRequest.class.getName() + " is listed as dual-use but is no longer both");
  }

  @Test
  void serverManagedRuleFailsOnTheFixtureComponents() {
    List<String> violations =
        MassAssignmentGuardRules.serverManagedComponentViolations(
            MassAssignmentGuardRules.handlers(FIXTURES), Set.of(), Map.of());

    assertThat(violations)
        .hasSize(6)
        .anySatisfy(v -> assertThat(v).contains("FixtureOrderDto").contains("`id`"))
        .anySatisfy(v -> assertThat(v).contains("FixtureOrderDto").contains("`ownerId`"))
        .anySatisfy(v -> assertThat(v).contains("FixtureOrderDto").contains("`createdAt`"))
        .anySatisfy(v -> assertThat(v).contains("FixtureOrderDto").contains("`status`"))
        .anySatisfy(v -> assertThat(v).contains("BulkStatusRequest").contains("`status`"))
        .anySatisfy(v -> assertThat(v).contains("Line").contains("`owningOrgUnitId`"))
        .noneSatisfy(v -> assertThat(v).contains("FixtureStatusRequest"));
  }

  @Test
  void serverManagedRuleAcceptsAReviewedInputAndReportsAStaleOne() {
    List<String> violations =
        MassAssignmentGuardRules.serverManagedComponentViolations(
            MassAssignmentGuardRules.handlers(FIXTURES),
            Set.of(FixtureOrderDto.class),
            Map.of(
                FixtureNestedRequest.Line.class,
                Map.of("owningOrgUnitId", "fixture"),
                FixtureMassAssignmentController.BulkStatusRequest.class,
                Map.of("status", "fixture", "ids", "fixture")));

    assertThat(violations)
        .containsExactly(
            FixtureMassAssignmentController.BulkStatusRequest.class.getName()
                + "#ids is listed as a reviewed client input but no request type declares it as a"
                + " server-managed name");
  }

  @Test
  void validationRuleFailsOnTheUnvalidatedFixtureBody() {
    MassAssignmentGuardRules.ValidationReport report =
        MassAssignmentGuardRules.unvalidatedBodies(MassAssignmentGuardRules.handlers(FIXTURES));

    assertThat(report.bodies()).isEqualTo(4);
    assertThat(report.violations())
        .containsExactly(
            "FixtureMassAssignmentController#create binds a @RequestBody without @Valid on"
                + " /api/v1/fixture-orders (REQ-API-015)");
  }
}
