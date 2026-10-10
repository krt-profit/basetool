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

package de.greluc.krt.profit.basetool.backend.config;

import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The business domain each REST controller belongs to, as the OpenAPI document tags it
 * (REQ-API-018).
 *
 * <p>The table is the controller-to-domain map of the REST API cut ({@code
 * docs/modularisation/rest-api-cut.md}, "Today's surface"): 22 domains, every controller in exactly
 * one. A controller missing here is tagged {@link #UNASSIGNED}, which the document assertions
 * refuse.
 */
public final class ApiDomains {

  /** The tag of an operation whose controller the table does not name. */
  public static final String UNASSIGNED = "unassigned";

  /** Controller simple name to its domain. */
  private static final Map<String, String> BY_CONTROLLER =
      Map.ofEntries(
          Map.entry("AdminController", "identity"),
          Map.entry("AdminDataExportController", "identity"),
          Map.entry("AdminDefaultBlueprintController", "blueprint"),
          Map.entry("AdminDeletionRequestController", "identity"),
          Map.entry("AdminExchangeBulkUndoController", "exchange"),
          Map.entry("AdminExchangeRegistryController", "exchange"),
          Map.entry("AdminP4kImportController", "catalogue"),
          Map.entry("AdminPersonSearchController", "identity"),
          Map.entry("AdminPersonalBlueprintController", "blueprint"),
          Map.entry("AdminPersonalInventoryController", "personalinventory"),
          Map.entry("AdminQualityTierController", "catalogue"),
          Map.entry("AdminTermsController", "identity"),
          Map.entry("AnnouncementController", "dashboard"),
          Map.entry("AppVersionPolicyController", "admin-system"),
          Map.entry("AuditAdminController", "audit"),
          Map.entry("BankAccountController", "bank"),
          Map.entry("BankAdminController", "bank"),
          Map.entry("BankBookingController", "bank"),
          Map.entry("BankDashboardController", "bank"),
          Map.entry("BankExportController", "bank"),
          Map.entry("BankGrantController", "bank"),
          Map.entry("BankHolderController", "bank"),
          Map.entry("BankRequestController", "bank"),
          Map.entry("BasetoolErrorController", "admin-system"),
          Map.entry("BlueprintController", "blueprint"),
          Map.entry("BlueprintProductController", "blueprint"),
          Map.entry("CityController", "catalogue"),
          Map.entry("ConnectedAppsController", "exchange"),
          Map.entry("DataExportController", "identity"),
          Map.entry("DeletionRequestController", "identity"),
          Map.entry("DiscordAccountExistenceController", "identity"),
          Map.entry("DiscordRegistrationAdminController", "identity"),
          Map.entry("ExchangeAccountCheckController", "exchange"),
          Map.entry("ExchangeBlueprintController", "exchange"),
          Map.entry("ExchangeCatalogController", "exchange"),
          Map.entry("ExchangeDemandController", "exchange"),
          Map.entry("ExchangeDraftController", "exchange"),
          Map.entry("ExchangeInstallationController", "exchange"),
          Map.entry("ExchangeShipController", "exchange"),
          Map.entry("ExchangeStockController", "exchange"),
          Map.entry("FrequencyTypeController", "catalogue"),
          Map.entry("HangarController", "hangar"),
          Map.entry("InventoryItemController", "inventory"),
          Map.entry("JobOrderController", "joborder"),
          Map.entry("JobOrderItemStockController", "joborder"),
          Map.entry("JobTypeController", "catalogue"),
          Map.entry("KommandoGroupController", "orgunit"),
          Map.entry("LeitungController", "leadership"),
          Map.entry("LiveSyncController", "livesync"),
          Map.entry("LocationController", "catalogue"),
          Map.entry("ManufacturerController", "catalogue"),
          Map.entry("MaterialCategoryController", "catalogue"),
          Map.entry("MaterialClaimController", "joborder"),
          Map.entry("MaterialCollectionController", "joborder"),
          Map.entry("MaterialController", "catalogue"),
          Map.entry("MaterialExchangeController", "materialexchange"),
          Map.entry("MaterialExternalAliasController", "catalogue"),
          Map.entry("MaterialRequestController", "materialexchange"),
          Map.entry("MeController", "identity"),
          Map.entry("MemberEvaluationController", "promotion"),
          Map.entry("MissionController", "mission"),
          Map.entry("MissionFinanceEntryController", "mission"),
          Map.entry("MyRegistrationStatusController", "identity"),
          Map.entry("NotificationController", "notification"),
          Map.entry("NotificationRuleController", "notification"),
          Map.entry("OperationController", "operation"),
          Map.entry("OrgChartController", "orgchart"),
          Map.entry("OrgHierarchyController", "orgunit"),
          Map.entry("OrgUnitBankController", "bank"),
          Map.entry("OrgUnitController", "orgunit"),
          Map.entry("OutpostController", "catalogue"),
          Map.entry("PersonalBlueprintController", "blueprint"),
          Map.entry("PersonalBlueprintOverviewController", "blueprint"),
          Map.entry("PersonalInventoryController", "personalinventory"),
          Map.entry("PoiController", "catalogue"),
          Map.entry("ProfitCalculationController", "catalogue"),
          Map.entry("PromotionCategoryController", "promotion"),
          Map.entry("PromotionEligibilityController", "promotion"),
          Map.entry("PromotionLevelContentController", "promotion"),
          Map.entry("PromotionTopicController", "promotion"),
          Map.entry("QualityTierController", "catalogue"),
          Map.entry("RankRequirementController", "promotion"),
          Map.entry("RefineryImportController", "refinery"),
          Map.entry("RefineryOrderController", "refinery"),
          Map.entry("RefiningMethodController", "catalogue"),
          Map.entry("ShipTypeController", "catalogue"),
          Map.entry("SpaceStationController", "catalogue"),
          Map.entry("SpecialCommandController", "orgunit"),
          Map.entry("SpecialCommandMembershipController", "orgunit"),
          Map.entry("SquadronController", "orgunit"),
          Map.entry("SquadronMembershipController", "orgunit"),
          Map.entry("SquadronRoleController", "orgunit"),
          Map.entry("StarSystemController", "catalogue"),
          Map.entry("SyncReportController", "catalogue"),
          Map.entry("SystemSettingController", "admin-system"),
          Map.entry("TerminalController", "catalogue"),
          Map.entry("TermsController", "identity"),
          Map.entry("TermsDocumentController", "identity"),
          Map.entry("UexLocationController", "catalogue"),
          Map.entry("UserController", "identity"));

  /** Not instantiable. */
  private ApiDomains() {}

  /**
   * Names the domain a controller belongs to.
   *
   * @param controller the controller class, unproxied
   * @return the domain, or {@link #UNASSIGNED} when the table does not name the controller
   */
  @NotNull
  public static String of(@NotNull Class<?> controller) {
    return BY_CONTROLLER.getOrDefault(controller.getSimpleName(), UNASSIGNED);
  }

  /**
   * Lists every domain the table assigns.
   *
   * @return the distinct domain names
   */
  @NotNull
  @Unmodifiable
  public static Set<String> all() {
    return Set.copyOf(BY_CONTROLLER.values());
  }

  /**
   * Lists every controller the table names.
   *
   * @return the controller simple names
   */
  @NotNull
  @Unmodifiable
  public static Set<String> controllers() {
    return BY_CONTROLLER.keySet();
  }
}
