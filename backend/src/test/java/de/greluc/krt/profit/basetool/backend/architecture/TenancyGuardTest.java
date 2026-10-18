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

import static de.greluc.krt.profit.basetool.backend.architecture.TenancyGuardRules.OrgUnitReference.key;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.backend.architecture.TenancyGuardRules.GateReport;
import de.greluc.krt.profit.basetool.backend.architecture.TenancyGuardRules.HandlerKey;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccount;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankBookingRequest;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankTransaction;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.internal.HangarAccessPolicy;
import de.greluc.krt.profit.basetool.backend.hangar.web.HangarController;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryAccessPolicy;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItem;
import de.greluc.krt.profit.basetool.backend.inventory.web.InventoryItemController;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderHandover;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemHandover;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderAccessPolicy;
import de.greluc.krt.profit.basetool.backend.materialexchange.internal.MaterialExchangeOffer;
import de.greluc.krt.profit.basetool.backend.materialexchange.internal.MaterialExchangeRequest;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionAccessPolicy;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionSecurityService;
import de.greluc.krt.profit.basetool.backend.mission.web.MissionController;
import de.greluc.krt.profit.basetool.backend.mission.web.MissionFinanceEntryController;
import de.greluc.krt.profit.basetool.backend.operation.internal.Operation;
import de.greluc.krt.profit.basetool.backend.operation.internal.OperationAccessPolicy;
import de.greluc.krt.profit.basetool.backend.operation.web.OperationController;
import de.greluc.krt.profit.basetool.backend.orgchart.internal.OrgChartPosition;
import de.greluc.krt.profit.basetool.backend.orgunit.api.KommandoGroup;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgRoleManagementSecurityService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommandSecurityService;
import de.greluc.krt.profit.basetool.backend.orgunit.web.SpecialCommandController;
import de.greluc.krt.profit.basetool.backend.orgunit.web.SpecialCommandMembershipController;
import de.greluc.krt.profit.basetool.backend.platform.api.TenantScoped;
import de.greluc.krt.profit.basetool.backend.promotion.internal.PromotionTopic;
import de.greluc.krt.profit.basetool.backend.promotion.internal.RankRequirement;
import de.greluc.krt.profit.basetool.backend.promotion.web.MemberEvaluationController;
import de.greluc.krt.profit.basetool.backend.promotion.web.PromotionCategoryController;
import de.greluc.krt.profit.basetool.backend.promotion.web.PromotionLevelContentController;
import de.greluc.krt.profit.basetool.backend.promotion.web.PromotionTopicController;
import de.greluc.krt.profit.basetool.backend.promotion.web.RankRequirementController;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PurchasingAccessPolicy;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PurchasingDeal;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PurchasingUnitPause;
import de.greluc.krt.profit.basetool.backend.purchasing.web.PurchasingDealController;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryAccessPolicy;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.refinery.web.RefineryOrderController;
import de.greluc.krt.profit.basetool.backend.scope.api.AccessGateService;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplyAccessPolicy;
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplyDonation;
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplyNeed;
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplyUnitSetting;
import de.greluc.krt.profit.basetool.backend.supply.web.SupplyDonationController;
import de.greluc.krt.profit.basetool.guardfixture.tenancy.FixtureAggregate;
import de.greluc.krt.profit.basetool.guardfixture.tenancy.FixtureAggregateController;
import de.greluc.krt.profit.basetool.guardfixture.tenancy.UnclassifiedOwnedEntity;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * G-05 tenancy guards (REQ-ORG-028): every org-unit reference of an entity is classified by the
 * {@link TenantScoped} marker or a reasoned list, and every write endpoint of a controller that
 * writes tenant data gates on the scope service.
 *
 * <p>Each rule is proven able to fail on the planted fixtures in {@code
 * de.greluc.krt.profit.basetool.guardfixture.tenancy}, and each selection carries a floor.
 */
class TenancyGuardTest {

  private static final JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("de.greluc.krt.profit.basetool.backend");

  private static final JavaClasses FIXTURES =
      new ClassFileImporter().importPackages("de.greluc.krt.profit.basetool.guardfixture.tenancy");

  /** The scope services whose SpEL bean names and {@code can*} methods count as a scope gate. */
  private static final Set<Class<?>> SCOPE_GATE_TYPES =
      Set.of(
          OwnerScopeService.class,
          AccessGateService.class,
          OperationAccessPolicy.class,
          RefineryAccessPolicy.class,
          JobOrderAccessPolicy.class,
          MissionAccessPolicy.class,
          InventoryAccessPolicy.class,
          HangarAccessPolicy.class,
          MissionSecurityService.class,
          SpecialCommandSecurityService.class,
          OrgRoleManagementSecurityService.class,
          SupplyAccessPolicy.class,
          PurchasingAccessPolicy.class);

  /** Org-unit references that are no tenancy boundary, each with the reason. */
  private static final Map<String, String> NOT_TENANT_SCOPE =
      Map.ofEntries(
          Map.entry(
              key(JobOrder.class, "requestingOrgUnit"),
              "the customer unit; it grants no queue visibility, the responsible unit scopes the"
                  + " order (REQ-ORG-003)"),
          Map.entry(
              key(JobOrderHandover.class, "executingSquadron"),
              "a part of its JobOrder, scoped through the order; records the executing unit"),
          Map.entry(
              key(JobOrderItemHandover.class, "executingSquadron"),
              "a part of its JobOrder, scoped through the order; records the executing unit"),
          Map.entry(
              key(MaterialClaim.class, "claimingOrgUnit"),
              "a part of its JobOrder, read inside the order; MaterialClaimService authorises the"
                  + " claiming unit on every write"),
          Map.entry(
              key(MissionParticipant.class, "orgUnits"),
              "the participant's declared affiliation; the mission scopes the row"),
          Map.entry(
              key(MaterialExchangeOffer.class, "owningOrgUnit"),
              "attribution of the offering member's unit; the Materialbörse is organisation-wide"
                  + " and its writes are owner-only"),
          Map.entry(
              key(MaterialExchangeRequest.class, "owningOrgUnit"),
              "attribution of the requesting member's unit; the Materialbörse is"
                  + " organisation-wide and its writes are owner-only"),
          Map.entry(
              key(BankAccount.class, "orgUnit"),
              "the account holder; bank access runs through bank grants and the one"
                  + " OrgUnitBankAccessService seam (ADR-0020, ADR-0028)"),
          Map.entry(
              key(BankTransaction.class, "counterpartyOrgUnitId"),
              "a counterparty snapshot on a ledger line, no ownership"),
          Map.entry(
              key(BankBookingRequest.class, "counterpartyOrgUnitId"),
              "a counterparty snapshot on a booking request, no ownership"),
          Map.entry(
              key(KommandoGroup.class, "squadron"),
              "org structure under a Staffel, authorised by the delegated appointment ladder,"
                  + " which never consults owner scope (ADR-0042)"),
          Map.entry(
              key(OrgChartPosition.class, "orgUnit"),
              "the unit a descriptive, ADMIN-edited org-chart position describes"),
          Map.entry(
              key(SupplyNeed.class, "orgUnitId"),
              "the raising unit of a need that every member reads; it is a role scope"
                  + " (SupplyAccessPolicy.canManageUnit), not a tenancy boundary"),
          Map.entry(
              key(SupplyDonation.class, "receivingOrgUnitId"),
              "the receiving unit of a donation; the unit's managers and the donor read it through"
                  + " SupplyAccessPolicy, which is a role scope and not a tenancy boundary"),
          Map.entry(
              key(PurchasingDeal.class, "raisingOrgUnitId"),
              "the raising unit of the need a deal serves; deals are read through"
                  + " PurchasingAccessPolicy (the delegate, the governing leadership, the unit's"
                  + " managers), a role scope and not a tenancy boundary"),
          Map.entry(
              key(PurchasingUnitPause.class, "orgUnitId"),
              "the unit a pause of purchasing applies to, set by the unit's lead or the governing"
                  + " leadership"),
          Map.entry(
              key(SupplyUnitSetting.class, "id"),
              "the key of the unit's own supply setting, an admin-edited flag row"),
          Map.entry(key(OrgUnit.class, "parent"), "the org-unit hierarchy itself (REQ-ORG-014)"),
          Map.entry(
              key(OrgUnitMembership.class, "id.orgUnitId"),
              "the membership that defines scope, not data within it"));

  /** Write handlers whose scope check lives in the service layer, each with the reason. */
  private static final Map<HandlerKey, String> SERVICE_GATED =
      Map.ofEntries(
          Map.entry(
              new HandlerKey(PurchasingDealController.class, "update"),
              "PurchasingDealService.update checks the deal's own delegate"),
          Map.entry(
              new HandlerKey(PurchasingDealController.class, "cancel"),
              "PurchasingDealService.cancel checks the deal's delegate or the governing"
                  + " leadership"),
          Map.entry(
              new HandlerKey(PurchasingDealController.class, "requestTranche"),
              "PurchasingTrancheService.request checks the deal's own active delegate"),
          Map.entry(
              new HandlerKey(PurchasingDealController.class, "recordDelivery"),
              "PurchasingDeliveryService.record checks the deal's own delegate"),
          Map.entry(
              new HandlerKey(PurchasingDealController.class, "acceptDelivery"),
              "PurchasingDeliveryService.accept checks canAcceptDeliveries on the raising unit"),
          Map.entry(
              new HandlerKey(PurchasingDealController.class, "closeDelivery"),
              "PurchasingDeliveryService.close checks the deal's own delegate"),
          Map.entry(
              new HandlerKey(PurchasingDealController.class, "settle"),
              "PurchasingSettlementService.settle checks the deal's own delegate"),
          Map.entry(
              new HandlerKey(SupplyDonationController.class, "receive"),
              "SupplyDonationService.receive checks canManageUnit on the receiving unit"),
          Map.entry(
              new HandlerKey(SupplyDonationController.class, "decline"),
              "SupplyDonationService.decline checks canManageUnit on the receiving unit"),
          Map.entry(
              new HandlerKey(SupplyDonationController.class, "withdraw"),
              "SupplyDonationService.withdraw checks canWithdrawPledge: the donor or an admin"),
          Map.entry(
              new HandlerKey(PromotionLevelContentController.class, "update"),
              "PromotionLevelContentService checks canEditSquadron on the topic's owning Staffel"),
          Map.entry(
              new HandlerKey(PromotionLevelContentController.class, "delete"),
              "PromotionLevelContentService checks canEditSquadron on the topic's owning Staffel"),
          Map.entry(
              new HandlerKey(MemberEvaluationController.class, "upsert"),
              "MemberEvaluationService checks canEditSquadron on the topic's owning Staffel"),
          Map.entry(
              new HandlerKey(MemberEvaluationController.class, "delete"),
              "MemberEvaluationService checks canEditSquadron on the evaluated member's Staffel"),
          Map.entry(
              new HandlerKey(RankRequirementController.class, "update"),
              "RankRequirementService checks canEditSquadron on the owning Staffel"),
          Map.entry(
              new HandlerKey(RankRequirementController.class, "delete"),
              "RankRequirementService checks canEditSquadron on the owning Staffel"),
          Map.entry(
              new HandlerKey(PromotionTopicController.class, "update"),
              "PromotionTopicService checks canEditSquadron on the owning Staffel"),
          Map.entry(
              new HandlerKey(PromotionTopicController.class, "delete"),
              "PromotionTopicService checks canEditSquadron on the owning Staffel"),
          Map.entry(
              new HandlerKey(PromotionCategoryController.class, "update"),
              "PromotionCategoryService checks canEditSquadron on the topic's owning Staffel"),
          Map.entry(
              new HandlerKey(PromotionCategoryController.class, "delete"),
              "PromotionCategoryService checks canEditSquadron on the topic's owning Staffel"),
          Map.entry(
              new HandlerKey(MissionFinanceEntryController.class, "updateFinanceEntry"),
              "the service method carries @missionSecurityService.canEditFinanceEntry"),
          Map.entry(
              new HandlerKey(MissionFinanceEntryController.class, "deleteFinanceEntry"),
              "the service method carries @missionSecurityService.canEditFinanceEntry"));

  /** Selected write handlers that write no tenant data, each with the reason. */
  private static final Map<HandlerKey, String> NOT_TENANT_WRITES = Map.of();

  /** The controllers the replaced simple-name whitelist covered; the marker rule must keep them. */
  private static final Set<Class<?>> LEGACY_WHITELIST =
      Set.of(
          MissionController.class,
          OperationController.class,
          HangarController.class,
          InventoryItemController.class,
          RefineryOrderController.class,
          SpecialCommandController.class,
          SpecialCommandMembershipController.class);

  /** The aggregates marked when the guard was introduced; the marker may not silently go. */
  private static final Set<Class<?>> MARKED_FLOOR =
      Set.of(
          Mission.class,
          Operation.class,
          Ship.class,
          InventoryItem.class,
          RefineryOrder.class,
          JobOrder.class,
          PromotionTopic.class,
          RankRequirement.class);

  private static final int REFERENCE_FLOOR = 22;

  private static final int CONTROLLER_FLOOR = 23;

  private static final int HANDLER_FLOOR = 124;

  @Test
  void everyOrgUnitReferenceOfAnEntityIsClassified() {
    assertThat(TenancyGuardRules.markerViolations(PRODUCTION, NOT_TENANT_SCOPE)).isEmpty();

    long references =
        TenancyGuardRules.entities(PRODUCTION).stream()
            .mapToLong(entity -> TenancyGuardRules.orgUnitReferences(entity).size())
            .sum();
    assertThat(references).isGreaterThanOrEqualTo(REFERENCE_FLOOR);
  }

  @Test
  void theScopedAggregatesCarryTheMarker() {
    assertThat(TenancyGuardRules.tenantData(PRODUCTION)).containsAll(MARKED_FLOOR);
    for (Class<?> aggregate : MARKED_FLOOR) {
      assertThat(aggregate.isAnnotationPresent(TenantScoped.class))
          .as("%s carries @TenantScoped", aggregate.getSimpleName())
          .isTrue();
    }
  }

  @Test
  void writeEndpointsOfControllersWritingTenantDataGateOnTheScopeService() {
    GateReport report =
        TenancyGuardRules.writeGateReport(
            PRODUCTION,
            TenancyGuardRules.tenantData(PRODUCTION),
            SCOPE_GATE_TYPES,
            SERVICE_GATED,
            NOT_TENANT_WRITES);

    assertThat(report.violations()).isEmpty();
    assertThat(report.selectedControllers()).containsAll(LEGACY_WHITELIST);
    assertThat(report.selectedControllers()).hasSizeGreaterThanOrEqualTo(CONTROLLER_FLOOR);
    assertThat(report.selectedHandlers()).hasSizeGreaterThanOrEqualTo(HANDLER_FLOOR);
  }

  @Test
  void markerRuleFailsOnUnclassifiedAndMislabelledFixtures() {
    assertThat(TenancyGuardRules.markerViolations(FIXTURES, Map.of()))
        .containsExactlyInAnyOrder(
            key(UnclassifiedOwnedEntity.class, "owningOrgUnit")
                + " refers to an org unit but is neither named by @TenantScoped on its entity nor"
                + " listed with a reason as no tenancy boundary (REQ-ORG-028)",
            key(UnclassifiedOwnedEntity.class, "handoverUnit")
                + " refers to an org unit but is neither named by @TenantScoped on its entity nor"
                + " listed with a reason as no tenancy boundary (REQ-ORG-028)",
            "de.greluc.krt.profit.basetool.guardfixture.tenancy.MislabelledAggregate"
                + " @TenantScoped names `name`, which is no org-unit association of the entity");
  }

  @Test
  void markerRuleFailsOnAStaleExceptionEntry() {
    assertThat(
            TenancyGuardRules.markerViolations(
                FIXTURES,
                Map.of(
                    key(UnclassifiedOwnedEntity.class, "owningOrgUnit"), "fixture",
                    key(UnclassifiedOwnedEntity.class, "handoverUnit"), "fixture",
                    key(FixtureAggregate.class, "owningOrgUnit"), "fixture",
                    key(FixtureAggregate.class, "gone"), "fixture")))
        .contains(
            key(FixtureAggregate.class, "owningOrgUnit")
                + " is named by @TenantScoped and also listed as no tenancy boundary",
            key(FixtureAggregate.class, "gone")
                + " is listed as no tenancy boundary but is no org-unit reference");
  }

  @Test
  void writeGateRuleFailsOnUngatedFixtureHandlers() {
    GateReport report =
        TenancyGuardRules.writeGateReport(
            FIXTURES,
            TenancyGuardRules.tenantData(FIXTURES),
            SCOPE_GATE_TYPES,
            Map.of(
                new HandlerKey(FixtureAggregateController.class, "serviceChecked"), "fixture",
                new HandlerKey(FixtureAggregateController.class, "serviceUnchecked"), "fixture"),
            Map.of(new HandlerKey(FixtureAggregateController.class, "read"), "fixture"));

    assertThat(report.selectedControllers()).containsExactly(FixtureAggregateController.class);
    assertThat(report.selectedHandlers()).hasSize(6);
    assertThat(report.violations())
        .hasSize(4)
        .anySatisfy(v -> assertThat(v).startsWith("FixtureAggregateController#openBranch writes"))
        .anySatisfy(
            v -> assertThat(v).startsWith("FixtureAggregateController#classGateOnly writes"))
        .anySatisfy(
            v ->
                assertThat(v)
                    .startsWith(
                        "FixtureAggregateController#serviceUnchecked is listed as service-gated"))
        .anySatisfy(
            v ->
                assertThat(v)
                    .isEqualTo(
                        "FixtureAggregateController#read is listed as an exception but is no"
                            + " selected write handler"));
  }

  @Test
  void spelGateAcceptsOnlyScopeOrAdminOnEveryBranch() {
    SpelScopeGate gate = new SpelScopeGate(SCOPE_GATE_TYPES);

    assertThat(gate.isScopeGated("hasRole('ADMIN')")).isTrue();
    assertThat(gate.isScopeGated("hasRole('OFFICER') and @ownerScopeService.canEditShip(#id)"))
        .isTrue();
    assertThat(
            gate.isScopeGated(
                "hasRole('ADMIN') or (@orgRoleManagementSecurityService.canX(#id, authentication)"
                    + " and @orgRoleManagementSecurityService.targetsAnotherUser(#u,"
                    + " authentication))"))
        .isTrue();
    assertThat(gate.isScopeGated("isAuthenticated()")).isFalse();
    assertThat(gate.isScopeGated("hasRole('ADMIN') or isAuthenticated()")).isFalse();
    assertThat(gate.isScopeGated("isAuthenticated() or @ownerScopeService.canEditShip(#id)"))
        .isFalse();
    assertThat(gate.isScopeGated("@bankSecurityService.canSee(#id, authentication)")).isFalse();
    assertThat(gate.isScopeGated("hasAnyRole('ADMIN', 'OFFICER')")).isFalse();
    assertThat(gate.isScopeGated("")).isFalse();
  }
}
