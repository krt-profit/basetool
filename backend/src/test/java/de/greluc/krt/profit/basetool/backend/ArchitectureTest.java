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

package de.greluc.krt.profit.basetool.backend;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.audit.internal.AuditRetentionProperties;
import de.greluc.krt.profit.basetool.backend.bank.api.BankConflictException;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestCancelledEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestConfirmedEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestCreatedEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestRejectedEvent;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityTypeRounding;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.CachedEntityGraphs;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.StalePriceSweep;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.UexValues;
import de.greluc.krt.profit.basetool.backend.config.ActingMemberFilter;
import de.greluc.krt.profit.basetool.backend.controller.AppVersionPolicyController;
import de.greluc.krt.profit.basetool.backend.controller.BankAccountController;
import de.greluc.krt.profit.basetool.backend.controller.BankAdminController;
import de.greluc.krt.profit.basetool.backend.controller.BankBookingController;
import de.greluc.krt.profit.basetool.backend.controller.BankDashboardController;
import de.greluc.krt.profit.basetool.backend.controller.BankExportController;
import de.greluc.krt.profit.basetool.backend.controller.BankGrantController;
import de.greluc.krt.profit.basetool.backend.controller.BankHolderController;
import de.greluc.krt.profit.basetool.backend.controller.BankRequestController;
import de.greluc.krt.profit.basetool.backend.controller.BasetoolErrorController;
import de.greluc.krt.profit.basetool.backend.controller.DiscordAccountExistenceController;
import de.greluc.krt.profit.basetool.backend.controller.OrgUnitBankController;
import de.greluc.krt.profit.basetool.backend.controller.TermsDocumentController;
import de.greluc.krt.profit.basetool.backend.exchange.api.ActingMemberAuthorities;
import de.greluc.krt.profit.basetool.backend.exchange.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.exchange.api.IngestGatewayProperties;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ChangeSource;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ChangeSourceProperties;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ConnectedAppsProperties;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeRetentionProperties;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientDirectory;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeConnectionRetentionProperties;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeMirrorProperties;
import de.greluc.krt.profit.basetool.backend.exchange.internal.KnownExchangeClients;
import de.greluc.krt.profit.basetool.backend.identity.api.TermsConsentCheck;
import de.greluc.krt.profit.basetool.backend.identity.api.UserDtoRedaction;
import de.greluc.krt.profit.basetool.backend.identity.internal.RejectedRegistrationRetentionProperties;
import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.integration.scwiki.ScWikiClient;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryAuditLabels;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryProperties;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockViewerAccess;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAuditLabel;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderInventoryOwnerRedactor;
import de.greluc.krt.profit.basetool.backend.kernel.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.kernel.LikePatterns;
import de.greluc.krt.profit.basetool.backend.kernel.Permissions;
import de.greluc.krt.profit.basetool.backend.kernel.ProblemResponseFactory;
import de.greluc.krt.profit.basetool.backend.kernel.Quality;
import de.greluc.krt.profit.basetool.backend.kernel.RequestMemo;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncAuthorization;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.livesync.internal.LiveSyncFanoutProperties;
import de.greluc.krt.profit.basetool.backend.mapper.BankAccountMapper;
import de.greluc.krt.profit.basetool.backend.mapper.BankAuditEventMapper;
import de.greluc.krt.profit.basetool.backend.mapper.BankGrantMapper;
import de.greluc.krt.profit.basetool.backend.mapper.BankHolderMapper;
import de.greluc.krt.profit.basetool.backend.mapper.CentralMapperConfig;
import de.greluc.krt.profit.basetool.backend.mapper.OrgUnitMembershipMapper;
import de.greluc.krt.profit.basetool.backend.materialexchange.internal.MaterialExchangeQueryParams;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionPeerRedactor;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionSectionVersions;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionViewerAccess;
import de.greluc.krt.profit.basetool.backend.model.AbstractEntity;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountApprovalLimit;
import de.greluc.krt.profit.basetool.backend.model.BankAccountGrant;
import de.greluc.krt.profit.basetool.backend.model.BankAccountGrantId;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankAccountViewGrant;
import de.greluc.krt.profit.basetool.backend.model.BankAccountViewGranteeKind;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEvent;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.model.BankHolder;
import de.greluc.krt.profit.basetool.backend.model.BankHolderPosting;
import de.greluc.krt.profit.basetool.backend.model.BankPosting;
import de.greluc.krt.profit.basetool.backend.model.BankRequestApprover;
import de.greluc.krt.profit.basetool.backend.model.BankTransaction;
import de.greluc.krt.profit.basetool.backend.model.BankTransactionType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.PromotionTopic;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BankAccountDetailDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankAccountRefDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankApprovalLimitUserDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankApprovalLimitsDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankAuditEventDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBalancePointDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBalanceSeriesDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingOutcomeDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankCapabilitiesDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankDashboardAccountDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankDashboardDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankDashboardTotalsDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankGrantDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankHolderBookingDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankHolderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankTransactionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankTransferFeeRateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankWipeResetResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionParticipantDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitBankAccountDetailDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitBankAccountSettingsDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitBankBalanceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitBankViewUserDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankAccountLifecycleRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankDepositRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankHolderTransferRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankTransferRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankWithdrawalRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CancelBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.ConfirmBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateBankAccountRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateBankGrantRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateMissionRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.RegisterBankHolderRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.RejectBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.RenameBankAccountRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.ReverseBankTransactionRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.SetBankApprovalLimitRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.SetBankBalanceTargetRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateBankGrantRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateBankHolderRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateMissionRequest;
import de.greluc.krt.profit.basetool.backend.model.projection.BankAccountBalance;
import de.greluc.krt.profit.basetool.backend.model.projection.BankBookingRow;
import de.greluc.krt.profit.basetool.backend.model.projection.BankCounterLeg;
import de.greluc.krt.profit.basetool.backend.model.projection.BankHolderBalance;
import de.greluc.krt.profit.basetool.backend.model.projection.BankHolderBookingRow;
import de.greluc.krt.profit.basetool.backend.model.projection.BankHolderLeg;
import de.greluc.krt.profit.basetool.backend.model.projection.BankPostingSlice;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import de.greluc.krt.profit.basetool.backend.notification.internal.NotificationFanoutProperties;
import de.greluc.krt.profit.basetool.backend.notification.internal.NotificationParamsCodec;
import de.greluc.krt.profit.basetool.backend.notification.internal.NotificationRetentionProperties;
import de.greluc.krt.profit.basetool.backend.orgunit.api.StaffelMembershipResolver;
import de.greluc.krt.profit.basetool.backend.orgunit.internal.OrgUnitLabels;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthenticatedSubject;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthoritiesCacheProperties;
import de.greluc.krt.profit.basetool.backend.platform.api.ClientAttribution;
import de.greluc.krt.profit.basetool.backend.platform.api.ClientDirectory;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import de.greluc.krt.profit.basetool.backend.platform.api.PartialRoleScopeProperties;
import de.greluc.krt.profit.basetool.backend.platform.api.RateLimitProperties;
import de.greluc.krt.profit.basetool.backend.platform.api.RefusedSubjectWindow;
import de.greluc.krt.profit.basetool.backend.platform.api.ResilientRedisMessageListenerContainer;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import de.greluc.krt.profit.basetool.backend.platform.internal.ApiClientMetricsProperties;
import de.greluc.krt.profit.basetool.backend.platform.internal.RequestBodyLimitProperties;
import de.greluc.krt.profit.basetool.backend.privacy.internal.DataExportSections;
import de.greluc.krt.profit.basetool.backend.privacy.internal.HandleErasureCoverage;
import de.greluc.krt.profit.basetool.backend.privacy.internal.HandleSpellings;
import de.greluc.krt.profit.basetool.backend.privacy.internal.PersonSearchTargets;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountApprovalLimitRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountGrantRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountViewGrantRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankBookingRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankTransactionRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.ScopeSpecifications;
import de.greluc.krt.profit.basetool.backend.service.AuditService;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.BankAccountService;
import de.greluc.krt.profit.basetool.backend.service.BankApprovalLimitService;
import de.greluc.krt.profit.basetool.backend.service.BankAuditReportService;
import de.greluc.krt.profit.basetool.backend.service.BankAuditService;
import de.greluc.krt.profit.basetool.backend.service.BankBalanceSeriesCalculator;
import de.greluc.krt.profit.basetool.backend.service.BankBookingGuards;
import de.greluc.krt.profit.basetool.backend.service.BankBookingRequestService;
import de.greluc.krt.profit.basetool.backend.service.BankDashboardService;
import de.greluc.krt.profit.basetool.backend.service.BankGrantService;
import de.greluc.krt.profit.basetool.backend.service.BankHolderReconciliationService;
import de.greluc.krt.profit.basetool.backend.service.BankHolderService;
import de.greluc.krt.profit.basetool.backend.service.BankLedgerIntegrityService;
import de.greluc.krt.profit.basetool.backend.service.BankLedgerService;
import de.greluc.krt.profit.basetool.backend.service.BankManagementReportService;
import de.greluc.krt.profit.basetool.backend.service.BankPostingWriter;
import de.greluc.krt.profit.basetool.backend.service.BankSecurityService;
import de.greluc.krt.profit.basetool.backend.service.BankStatementReportService;
import de.greluc.krt.profit.basetool.backend.service.BankTransferFeeService;
import de.greluc.krt.profit.basetool.backend.service.BankTrendCalculator;
import de.greluc.krt.profit.basetool.backend.service.HangarService;
import de.greluc.krt.profit.basetool.backend.service.InventoryAggregationService;
import de.greluc.krt.profit.basetool.backend.service.InventoryCheckoutService;
import de.greluc.krt.profit.basetool.backend.service.InventoryItemService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderQueryService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderService;
import de.greluc.krt.profit.basetool.backend.service.MaterialClaimService;
import de.greluc.krt.profit.basetool.backend.service.MissionParticipantService;
import de.greluc.krt.profit.basetool.backend.service.MissionService;
import de.greluc.krt.profit.basetool.backend.service.OperationService;
import de.greluc.krt.profit.basetool.backend.service.OrgRoleManagementSecurityService;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitBankAccessService;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitBankApprovalLimitService;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitBankLiveSyncTopicAuthorizer;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitBankRecipientDirectory;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitBankResponsibilityService;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitBankVisibilityService;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitCascadeService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.service.PersonalBlueprintOverviewService;
import de.greluc.krt.profit.basetool.backend.service.RefineryOrderService;
import de.greluc.krt.profit.basetool.backend.service.exchange.ExchangeGate;
import de.greluc.krt.profit.basetool.backend.service.pdf.BankBalanceChart;
import de.greluc.krt.profit.basetool.backend.service.pdf.BankPdfFormat;
import de.greluc.krt.profit.basetool.backend.task.BankLedgerIntegrityTask;
import de.greluc.krt.profit.basetool.backend.util.BankAmounts;
import de.greluc.krt.profit.basetool.backend.validation.DtoConstraints;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OneToOne;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hibernate.annotations.OptimisticLock;
import org.junit.jupiter.api.Test;
import org.mapstruct.Mapper;
import org.mapstruct.MapperConfig;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.Repository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ArchUnit tests enforcing the backend's architectural invariants on production classes
 * (REQ-SEC-003, REQ-SEC-073).
 *
 * <p>Every rule selects by role ({@code @RestController}/{@code @Controller}, {@code @Service},
 * Spring Data {@code Repository}, MapStruct {@code @Mapper}, {@code @Entity}) or by class literal,
 * and asserts a selection floor before it runs, so a moved, renamed or deleted class fails the
 * build instead of silently leaving the rule. {@link ArchitectureRuleFailureTest} proves that every
 * rule family reports a planted violation.
 */
class ArchitectureTest {

  /** The backend's production classes; test sources, the fixtures included, are not imported. */
  static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(BackendApplication.class.getPackageName());

  /** The backend's root package, derived from the application class. */
  static final String ROOT_PACKAGE = BackendApplication.class.getPackageName();

  /** The selection floor of the rules that select every top-level production class. */
  static final long WHOLE_BACKEND_FLOOR = 1439;

  /**
   * Web controllers: {@code @RestController}, or anything meta-annotated with {@code @Controller}.
   */
  static final DescribedPredicate<JavaClass> WEB_CONTROLLERS =
      DescribedPredicate.describe(
          "web controllers (@RestController / @Controller)",
          c ->
              c.isAnnotatedWith(RestController.class)
                  || c.isAnnotatedWith(Controller.class)
                  || c.isMetaAnnotatedWith(Controller.class));

  /** Spring {@code @Service} beans. */
  static final DescribedPredicate<JavaClass> SERVICES =
      DescribedPredicate.describe("@Service beans", c -> c.isAnnotatedWith(Service.class));

  /**
   * MapStruct mappers: {@code @Mapper} and {@code @MapperConfig} types and the implementations
   * MapStruct generates for them.
   */
  static final DescribedPredicate<JavaClass> MAPPERS =
      DescribedPredicate.describe(
          "MapStruct mappers and their generated implementations",
          c ->
              c.isAnnotatedWith(Mapper.class)
                  || c.isAnnotatedWith(MapperConfig.class)
                  || c.getAllRawInterfaces().stream().anyMatch(i -> i.isAnnotatedWith(Mapper.class))
                  || c.getAllRawSuperclasses().stream()
                      .anyMatch(s -> s.isAnnotatedWith(Mapper.class)));

  /** Spring Data repositories: every subtype of {@link Repository}. */
  static final DescribedPredicate<JavaClass> SPRING_DATA_REPOSITORIES =
      DescribedPredicate.describe(
          "Spring Data repositories", c -> c.isAssignableTo(Repository.class));

  /** JPA-managed types: {@code @Entity}, {@code @Embeddable} and {@code @MappedSuperclass}. */
  static final DescribedPredicate<JavaClass> PERSISTENT_TYPES =
      DescribedPredicate.describe(
          "JPA entities, embeddables and mapped superclasses",
          c ->
              c.isAnnotatedWith(Entity.class)
                  || c.isAnnotatedWith(Embeddable.class)
                  || c.isAnnotatedWith(MappedSuperclass.class));

  /** Bean Validation constraints and their validators. */
  static final DescribedPredicate<JavaClass> VALIDATION_TYPES =
      DescribedPredicate.describe(
          "Bean Validation constraints and validators",
          c ->
              (c.isAnnotation() && c.isAnnotatedWith(Constraint.class))
                  || c.isAssignableTo(ConstraintValidator.class));

  /** The controller layer: web controllers, their nested classes and the legacy package. */
  static final DescribedPredicate<JavaClass> CONTROLLER_CODE =
      layer("controller code", WEB_CONTROLLERS, BasetoolErrorController.class);

  /** The service layer: services, their nested classes and the legacy package. */
  static final DescribedPredicate<JavaClass> SERVICE_CODE =
      layer("service code", SERVICES, AuthHelperService.class);

  /** The mapper layer: MapStruct mappers, their nested classes and the legacy package. */
  static final DescribedPredicate<JavaClass> MAPPER_CODE =
      layer("mapper code", MAPPERS, CentralMapperConfig.class);

  /** The repository layer: Spring Data repositories, their nested classes and the package. */
  static final DescribedPredicate<JavaClass> REPOSITORY_CODE =
      layer("repository code", SPRING_DATA_REPOSITORIES, ScopeSpecifications.class);

  /** The model: JPA-managed types, their nested classes and the legacy model package tree. */
  static final DescribedPredicate<JavaClass> MODEL_CODE =
      layer("model code", PERSISTENT_TYPES, AbstractEntity.class);

  /** The validation leaf: constraints, validators and the legacy validation package. */
  static final DescribedPredicate<JavaClass> VALIDATION_CODE =
      layer("validation code", VALIDATION_TYPES, DtoConstraints.class);

  /**
   * The dependency-leaf helpers: the kernel's and platform's primitives and the small per-module
   * helpers the service, mapper and controller layers share, named by class literal (plan §7.3,
   * P1-9).
   */
  static final Set<Class<?>> LEAF_HELPER_CLASSES =
      Set.of(
          ActingMemberAuthorities.class,
          ActingMemberHeader.class,
          ApiClientMetricsProperties.class,
          AppProblemProperties.class,
          AuditRetentionProperties.class,
          AuthenticatedSubject.class,
          AuthoritiesCacheProperties.class,
          CachedEntityGraphs.class,
          ChangeSource.class,
          ChangeSourceProperties.class,
          ClientAttribution.class,
          ClientDirectory.class,
          ConnectedAppsProperties.class,
          DataExportSections.class,
          ExchangeChangeRetentionProperties.class,
          ExchangeClientDirectory.class,
          ExchangeConnectionRetentionProperties.class,
          ExchangeMirrorProperties.class,
          HandleErasureCoverage.class,
          HandleSpellings.class,
          IngestGatewayProperties.class,
          InventoryAllocations.class,
          InventoryAuditLabels.class,
          InventoryProperties.class,
          JobOrderAuditLabel.class,
          JobOrderInventoryOwnerRedactor.class,
          KnownExchangeClients.class,
          LikePatterns.class,
          LiveSyncAuthorization.class,
          LiveSyncFanoutProperties.class,
          LiveSyncTopic.class,
          LiveSyncTopicClass.class,
          MaterialExchangeQueryParams.class,
          MissionPeerRedactor.class,
          MissionSectionVersions.class,
          MissionViewerAccess.class,
          NotificationFanoutProperties.class,
          NotificationParamsCodec.class,
          NotificationRetentionProperties.class,
          de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock.class,
          OrgUnitContextualAuthority.class,
          OrgUnitLabels.class,
          PartialRoleScopeProperties.class,
          Permissions.class,
          PersonSearchTargets.class,
          ProblemResponseFactory.class,
          Quality.class,
          QuantityTypeRounding.class,
          RateLimitProperties.class,
          RefusedSubjectWindow.class,
          RejectedRegistrationRetentionProperties.class,
          RequestBodyLimitProperties.class,
          RequestMemo.class,
          ResilientRedisMessageListenerContainer.class,
          Roles.class,
          StaffelMembershipResolver.class,
          StalePriceSweep.class,
          StockViewerAccess.class,
          StringNormalization.class,
          SubjectAuthentication.class,
          TermsConsentCheck.class,
          UexValues.class,
          UserDtoRedaction.class);

  /** The dependency-leaf helpers, their nested classes and arrays of either. */
  static final DescribedPredicate<JavaClass> LEAF_HELPERS =
      DescribedPredicate.describe(
          "the dependency-leaf helpers, their nested classes and arrays of them",
          c ->
              LEAF_HELPER_CLASSES.stream()
                  .map(Class::getName)
                  .anyMatch(
                      outermost(c.isArray() ? c.getBaseComponentType() : c).getName()::equals));

  /** The outbound integration clients, anchored on {@link UexClient}. */
  static final DescribedPredicate<JavaClass> INTEGRATION_CODE =
      packageTreeOf("integration code", UexClient.class);

  /**
   * The event payloads: the {@code api.events} package tree of every module, which holds {@link
   * NotificationEvent} and every record a module publishes (plan §5.2).
   */
  static final DescribedPredicate<JavaClass> EVENT_CODE =
      moduleApiEventsCode("event code", ROOT_PACKAGE);

  /** Classes of the exchange domain: a package segment named {@code exchange}. */
  static final DescribedPredicate<JavaClass> EXCHANGE_DOMAIN = inDomain("exchange");

  /**
   * The bank's own classes (REQ-BANK-008, REQ-BANK-019), named by class literal until the bank has
   * a module package; {@link #BANK_DOMAIN} adds that package.
   */
  static final Set<Class<?>> BANK_CLASSES =
      Set.of(
          BankAccountController.class,
          BankAdminController.class,
          BankBookingController.class,
          BankDashboardController.class,
          BankExportController.class,
          BankGrantController.class,
          BankHolderController.class,
          BankRequestController.class,
          BankBookingRequestCancelledEvent.class,
          BankBookingRequestConfirmedEvent.class,
          BankBookingRequestCreatedEvent.class,
          BankBookingRequestEvent.class,
          BankBookingRequestRejectedEvent.class,
          BankConflictException.class,
          BankAccountMapper.class,
          BankAuditEventMapper.class,
          BankGrantMapper.class,
          BankHolderMapper.class,
          BankAccount.class,
          BankAccountApprovalLimit.class,
          BankAccountGrant.class,
          BankAccountGrantId.class,
          BankAccountStatus.class,
          BankAccountType.class,
          BankAccountViewGrant.class,
          BankAccountViewGranteeKind.class,
          BankAuditEvent.class,
          BankAuditEventType.class,
          BankBookingRequest.class,
          BankBookingRequestStatus.class,
          BankBookingRequestType.class,
          BankHolder.class,
          BankHolderPosting.class,
          BankPosting.class,
          BankRequestApprover.class,
          BankTransaction.class,
          BankTransactionType.class,
          BankAccountDetailDto.class,
          BankAccountDto.class,
          BankAccountRefDto.class,
          BankApprovalLimitUserDto.class,
          BankApprovalLimitsDto.class,
          BankAuditEventDto.class,
          BankBalancePointDto.class,
          BankBalanceSeriesDto.class,
          BankBookingDto.class,
          BankBookingOutcomeDto.class,
          BankBookingRequestDto.class,
          BankCapabilitiesDto.class,
          BankDashboardAccountDto.class,
          BankDashboardDto.class,
          BankDashboardTotalsDto.class,
          BankGrantDto.class,
          BankHolderBookingDto.class,
          BankHolderDto.class,
          BankTransactionDto.class,
          BankTransferFeeRateDto.class,
          BankWipeResetResultDto.class,
          BankAccountLifecycleRequest.class,
          BankDepositRequest.class,
          BankHolderTransferRequest.class,
          BankTransferRequest.class,
          BankWithdrawalRequest.class,
          CancelBankBookingRequest.class,
          ConfirmBankBookingRequest.class,
          CreateBankAccountRequest.class,
          CreateBankBookingRequest.class,
          CreateBankGrantRequest.class,
          RegisterBankHolderRequest.class,
          RejectBankBookingRequest.class,
          RenameBankAccountRequest.class,
          ReverseBankTransactionRequest.class,
          SetBankApprovalLimitRequest.class,
          SetBankBalanceTargetRequest.class,
          UpdateBankBookingRequest.class,
          UpdateBankGrantRequest.class,
          UpdateBankHolderRequest.class,
          BankAccountBalance.class,
          BankBookingRow.class,
          BankCounterLeg.class,
          BankHolderBalance.class,
          BankHolderBookingRow.class,
          BankHolderLeg.class,
          BankPostingSlice.class,
          BankAccountApprovalLimitRepository.class,
          BankAccountGrantRepository.class,
          BankAccountRepository.class,
          BankAccountViewGrantRepository.class,
          BankAuditEventRepository.class,
          BankBookingRequestRepository.class,
          BankHolderPostingRepository.class,
          BankHolderRepository.class,
          BankPostingRepository.class,
          BankTransactionRepository.class,
          BankAccountService.class,
          BankApprovalLimitService.class,
          BankAuditReportService.class,
          BankAuditService.class,
          BankBalanceSeriesCalculator.class,
          BankBookingGuards.class,
          BankBookingRequestService.class,
          BankDashboardService.class,
          BankGrantService.class,
          BankHolderReconciliationService.class,
          BankHolderService.class,
          BankLedgerIntegrityService.class,
          BankLedgerService.class,
          BankManagementReportService.class,
          BankPostingWriter.class,
          BankSecurityService.class,
          BankStatementReportService.class,
          BankTransferFeeService.class,
          BankTrendCalculator.class,
          BankBalanceChart.class,
          BankPdfFormat.class,
          BankLedgerIntegrityTask.class,
          BankAmounts.class);

  /**
   * The org-unit side of the bank: classes that carry "Bank" in their name but belong to org-unit
   * oversight, bridged into the bank only through {@link OrgUnitBankAccessService} (ADR-0020).
   */
  static final Set<Class<?>> ORG_UNIT_BANK_SIDE =
      Set.of(
          OrgUnitBankController.class,
          OrgUnitBankAccountDetailDto.class,
          OrgUnitBankAccountSettingsDto.class,
          OrgUnitBankBalanceDto.class,
          OrgUnitBankViewUserDto.class,
          OrgUnitBankAccessService.class,
          OrgUnitBankApprovalLimitService.class,
          OrgUnitBankLiveSyncTopicAuthorizer.class,
          OrgUnitBankRecipientDirectory.class,
          OrgUnitBankResponsibilityService.class,
          OrgUnitBankVisibilityService.class);

  /**
   * The bank domain: the {@code bank} module package, the classes of {@link #BANK_CLASSES}, the
   * classes nested in them and the implementations MapStruct generates for the bank mappers.
   */
  static final DescribedPredicate<JavaClass> BANK_DOMAIN =
      inDomain("bank")
          .or(nestedInAnyOf("the registered bank classes", BANK_CLASSES))
          .or(generatedImplementationsOf(BANK_CLASSES));

  /**
   * The names of today's top-level backend packages: in {@link #layerModuleOf} a top-level package
   * with one of these names is a layer of the root module, any other one is a domain module.
   */
  static final Set<String> LAYER_PACKAGE_NAMES =
      Set.of(
          "annotation",
          "config",
          "controller",
          "dto",
          "exception",
          "filter",
          "health",
          "integration",
          "interceptor",
          "logging",
          "mapper",
          "metrics",
          "model",
          "repository",
          "service",
          "task",
          "util",
          "validation",
          "web");

  /** Generic wrappers whose type arguments are scanned for JPA entities and PII-carrying DTOs. */
  static final Set<Class<?>> ENTITY_GENERIC_WRAPPERS =
      Set.of(
          ResponseEntity.class,
          Page.class,
          Slice.class,
          List.class,
          Set.class,
          Collection.class,
          Optional.class,
          Iterable.class);

  /** Mission DTOs whose participant data carries PII (REQ-SEC-007). */
  static final Set<Class<?>> MISSION_PII_CARRYING_DTOS =
      Set.of(MissionDto.class, MissionParticipantDto.class, MissionFinanceEntryDto.class);

  /** Staffel-scoped aggregate services that must inject a scope guard. */
  static final Set<Class<?>> STAFFEL_SCOPED_SERVICES =
      Set.of(
          MissionService.class,
          InventoryItemService.class,
          InventoryAggregationService.class,
          InventoryCheckoutService.class,
          RefineryOrderService.class,
          HangarService.class,
          OperationService.class,
          JobOrderService.class,
          JobOrderQueryService.class,
          MaterialClaimService.class,
          PersonalBlueprintOverviewService.class,
          OrgUnitBankAccessService.class);

  /** The scope guards a staffel-scoped service injects. */
  static final Set<Class<?>> SCOPE_GUARDS =
      Set.of(AuthHelperService.class, OwnerScopeService.class);

  /** The handler methods that may declare {@code @PreAuthorize("permitAll()")} (REQ-SEC-052). */
  static final Set<Method> PERMIT_ALL_ALLOWED_METHODS =
      Set.of(
          method(AppVersionPolicyController.class, "versionPolicy"),
          method(TermsDocumentController.class, "document", Locale.class),
          method(BasetoolErrorController.class, "handleError", HttpServletRequest.class));

  /** The controllers every handler of which may declare {@code permitAll()} (REQ-SEC-052). */
  static final Set<Class<?>> PERMIT_ALL_ALLOWED_CONTROLLERS =
      Set.of(DiscordAccountExistenceController.class);

  /** Entities still allowed to map the {@code squadron_id} column; only {@code User.squadron}. */
  static final Set<Class<?>> SQUADRON_ID_COLUMN_GRANDFATHERED = Set.of(User.class);

  /** Mission write requests that must not carry server-managed components. */
  static final Set<Class<?>> MISSION_WRITE_REQUESTS =
      Set.of(CreateMissionRequest.class, UpdateMissionRequest.class);

  /** The bank ledger repositories, insert-only (REQ-BANK-004). */
  static final Set<Class<?>> LEDGER_REPOSITORIES =
      Set.of(
          BankTransactionRepository.class,
          BankPostingRepository.class,
          BankHolderPostingRepository.class);

  /** The one approved ledger mutation, the Art. 17 counterparty anonymisation. */
  static final Set<String> APPROVED_LEDGER_MUTATIONS =
      Set.of(
          BankTransactionRepository.class.getName()
              + "."
              + methodName(BankTransactionRepository.class, "anonymiseCounterpartyHandle"));

  /** Classes in {@code ScWikiClient}'s package exempt from injecting it; empty. */
  static final Set<Class<?>> SCWIKI_CLIENT_INJECTION_EXEMPT = Set.of();

  /**
   * Method-name prefixes identifying state-mutating service methods for {@link
   * #mutatingServiceMethodsInReadOnlyClassesNeedExplicitTransactional()}; any other name counts as
   * a read.
   */
  static final Set<String> MUTATING_METHOD_PREFIXES =
      Set.of(
          "create",
          "update",
          "delete",
          "add",
          "remove",
          "save",
          "store",
          "book",
          "handover",
          "link",
          "unlink",
          "move",
          "reset",
          "patch",
          "toggle",
          "complete",
          "approve",
          "reject",
          "publish",
          "cancel",
          "join",
          "leave",
          "register",
          "unregister",
          "set",
          "insert",
          "merge",
          "assign",
          "unassign",
          "increment",
          "decrement",
          "clear",
          "purge",
          "import",
          "sync");

  /**
   * Record component names forbidden on Mission write DTOs (audit finding C-4); {@code version}
   * stays allowed on the update request as its optimistic-lock token.
   */
  static final Set<String> FORBIDDEN_MISSION_REQUEST_COMPONENTS =
      Set.of(
          "id",
          "version",
          "coreVersion",
          "scheduleVersion",
          "flagsVersion",
          "owner",
          "ownerId",
          "managers",
          "owningSquadron",
          "owningSquadronId",
          "squadronId",
          "squadron",
          "owningOrgUnit",
          "creatingOrgUnit",
          "requestingOrgUnit",
          "parent",
          "parentId",
          "participants",
          "assignedUnits",
          "frequencies",
          "subMissions",
          "inventoryEntries",
          "refineryOrders",
          "canEdit",
          "canManageManagers",
          "checkedInParticipants",
          "registeredParticipants");

  /** The name prefixes of the profit-flow aggregates the bank stays independent of. */
  static final Set<String> PROFIT_FLOW_NAME_PREFIXES =
      Set.of("Mission", "Operation", "JobOrder", "PriceLine", "Season");

  /** The domain package names of the profit-flow aggregates, once they have modules. */
  static final Set<String> PROFIT_FLOW_DOMAINS =
      Set.of("mission", "operation", "joborder", "priceline", "season");

  @Test
  void serviceLayerShouldNotReachIntoSecurityContext() {
    assertClassFloor("serviceLayerShouldNotReachIntoSecurityContext", serviceSelection(), 272);
    serviceLayerShouldNotReachIntoSecurityContextRule(serviceSelection()).check(CLASSES);
  }

  static DescribedPredicate<JavaClass> serviceSelection() {
    return SERVICE_CODE.and(
        DescribedPredicate.not(JavaClass.Predicates.equivalentTo(AuthHelperService.class)));
  }

  static ArchRule serviceLayerShouldNotReachIntoSecurityContextRule(
      DescribedPredicate<JavaClass> selection) {
    return noClasses()
        .that(selection)
        .should()
        .dependOnClassesThat()
        .belongToAnyOf(SecurityContextHolder.class)
        .because(
            "Business services must not pull the authenticated principal directly; use a"
                + " controller-side @PreAuthorize check or inject AuthHelperService instead.");
  }

  @Test
  void controllerLayerShouldNotReachIntoSecurityContext() {
    assertClassFloor("controllerLayerShouldNotReachIntoSecurityContext", CONTROLLER_CODE, 99);
    controllerLayerShouldNotReachIntoSecurityContextRule().check(CLASSES);
  }

  static ArchRule controllerLayerShouldNotReachIntoSecurityContextRule() {
    return noClasses()
        .that(CONTROLLER_CODE)
        .should()
        .dependOnClassesThat()
        .belongToAnyOf(SecurityContextHolder.class)
        .because(
            "Controllers must read the principal via @AuthenticationPrincipal / "
                + "Authentication parameters, or delegate to AuthHelperService — direct "
                + "SecurityContextHolder access splits the auth contract across the codebase.");
  }

  @Test
  void identityMustBeReadThroughTheSeamNotTheAuthenticationType() {
    DescribedPredicate<JavaClass> selection = outsideTheAuthenticationSeam();
    assertClassFloor(
        "identityMustBeReadThroughTheSeamNotTheAuthenticationType",
        selection,
        WHOLE_BACKEND_FLOOR - 2);
    identityMustBeReadThroughTheSeamRule(selection).check(CLASSES);
  }

  static DescribedPredicate<JavaClass> outsideTheAuthenticationSeam() {
    return DescribedPredicate.not(
        JavaClass.Predicates.belongToAnyOf(AuthenticatedSubject.class, ActingMemberFilter.class));
  }

  static ArchRule identityMustBeReadThroughTheSeamRule(DescribedPredicate<JavaClass> selection) {
    return noClasses()
        .that(selection)
        .should()
        .dependOnClassesThat()
        .belongToAnyOf(JwtAuthenticationToken.class)
        .because(
            "asking for the authentication TYPE instead of the subject splits every consumer into "
                + "fail-closed and fail-open the moment a second authentication type exists "
                + "(ADR-0129): the acting member carries no token, so an instanceof test skipped "
                + "the consent gate and 403'd the argument resolver. Read the subject via "
                + "AuthenticatedSubject; only the authentication seam (AuthenticatedSubject,"
                + " ActingMemberFilter) may name the token.");
  }

  @Test
  void mapperLayerShouldNotReachIntoSecurityContext() {
    assertClassFloor("mapperLayerShouldNotReachIntoSecurityContext", MAPPER_CODE, 100);
    mapperLayerShouldNotReachIntoSecurityContextRule().check(CLASSES);
  }

  static ArchRule mapperLayerShouldNotReachIntoSecurityContextRule() {
    return noClasses()
        .that(MAPPER_CODE)
        .should()
        .dependOnClassesThat()
        .belongToAnyOf(SecurityContextHolder.class)
        .because(
            "Mappers must stay pure transformers; route any auth lookup through a dependency-leaf "
                + "SPI (e.g. MissionViewerAccess) so the mapper depends on neither the "
                + "request-scoped SecurityContextHolder nor the service layer.");
  }

  @Test
  void controllerMethodsShouldNotReturnJpaEntities() {
    DescribedPredicate<JavaMethod> selection = publicMethodsOf(CONTROLLER_CODE);
    assertMethodFloor("controllerMethodsShouldNotReturnJpaEntities", selection, 725);
    controllerMethodsShouldNotReturnJpaEntitiesRule(selection).check(CLASSES);
  }

  static ArchRule controllerMethodsShouldNotReturnJpaEntitiesRule(
      DescribedPredicate<JavaMethod> selection) {
    return noMethods()
        .that(selection)
        .should()
        .haveRawReturnType(
            DescribedPredicate.describe(
                "a JPA @Entity", (JavaClass c) -> c.isAnnotatedWith(Entity.class)))
        .because(
            "Controllers must return DTOs (or Page<Dto>/ResponseEntity<Dto>), never raw JPA"
                + " entities.");
  }

  @Test
  void toOneAssociationsAreDeclaredLazy() {
    assertFieldFloor("toOneAssociationsAreDeclaredLazy", TO_ONE_ASSOCIATIONS, 148);
    toOneAssociationsAreDeclaredLazyRule().check(CLASSES);
  }

  /** Fields mapping a {@code @ManyToOne} or {@code @OneToOne} association. */
  static final DescribedPredicate<JavaField> TO_ONE_ASSOCIATIONS =
      DescribedPredicate.describe(
          "are @ManyToOne or @OneToOne associations",
          f -> f.isAnnotatedWith(ManyToOne.class) || f.isAnnotatedWith(OneToOne.class));

  static ArchRule toOneAssociationsAreDeclaredLazyRule() {
    return fields()
        .that(TO_ONE_ASSOCIATIONS)
        .should(declareFetchTypeLazy())
        .because(
            "to-one associations are LAZY by project rule (BE-PERF-11); fetch what a read needs"
                + " with an @EntityGraph or JOIN FETCH instead of making every load eager");
  }

  @Test
  void controllersMustNotInjectTheLazyMembershipMapper() {
    assertClassFloor("controllersMustNotInjectTheLazyMembershipMapper", CONTROLLER_CODE, 99);
    controllersMustNotInjectRule(OrgUnitMembershipMapper.class).check(CLASSES);
  }

  static ArchRule controllersMustNotInjectRule(Class<?> lazyMapper) {
    return noClasses()
        .that(CONTROLLER_CODE)
        .should()
        .dependOnClassesThat()
        .belongToAnyOf(lazyMapper)
        .because(
            "Membership DTO projection happens inside OrgUnitMembershipService (ADR-0067) — a"
                + " controller-side mapping outside a transaction throws"
                + " LazyInitializationException on the LAZY user association once the service"
                + " transaction has committed (the write succeeds but the response 500s). Use the"
                + " service's …Dto projection methods instead.");
  }

  @Test
  void everyRestControllerShouldDeclareAtLeastOneAuthorisationAnnotation() {
    assertClassFloor(
        "everyRestControllerShouldDeclareAtLeastOneAuthorisationAnnotation", WEB_CONTROLLERS, 99);
    everyRestControllerShouldDeclareAnAuthorisationAnnotationRule().check(CLASSES);
  }

  static ArchRule everyRestControllerShouldDeclareAnAuthorisationAnnotationRule() {
    return classes()
        .that(WEB_CONTROLLERS)
        .should(haveAtLeastOnePreAuthorizeAnnotation())
        .because(
            "Every REST controller class must declare at least one @PreAuthorize annotation (either"
                + " on the class or on any method) so it cannot silently bypass authorisation."
                + " Public endpoints must use @PreAuthorize(\"permitAll()\") and are limited to"
                + " the four REQ-SEC-052 names.");
  }

  @Test
  void permitAllIsDeclaredOnlyOnTheFourPublicEndpoints() {
    assertClassFloor("permitAllIsDeclaredOnlyOnTheFourPublicEndpoints", CONTROLLER_CODE, 99);
    List<String> declarations =
        CLASSES.stream()
            .filter(CONTROLLER_CODE)
            .flatMap(c -> permitAllDeclarations(c).stream())
            .toList();
    assertFloor(
        "permitAllIsDeclaredOnlyOnTheFourPublicEndpoints (permitAll declarations)",
        declarations.size(),
        4);
    for (Method allowed : PERMIT_ALL_ALLOWED_METHODS) {
      assertThat(declarations)
          .as("the allow-listed handler %s must still declare permitAll()", allowed)
          .anyMatch(
              d ->
                  d.startsWith(
                      allowed.getDeclaringClass().getName() + "." + allowed.getName() + "("));
    }
    permitAllRule(PERMIT_ALL_ALLOWED_METHODS, PERMIT_ALL_ALLOWED_CONTROLLERS).check(CLASSES);
  }

  static ArchRule permitAllRule(Set<Method> allowedMethods, Set<Class<?>> allowedControllers) {
    return classes()
        .that(CONTROLLER_CODE)
        .should(declarePermitAllOnlyOnTheAllowList(allowedMethods, allowedControllers))
        .because(
            "REQ-SEC-052: only the two anonymous reads, the Keycloak SPI precheck and Spring's"
                + " own /error dispatch may declare permitAll() -- the entries of"
                + " PERMIT_ALL_ALLOWED_METHODS and PERMIT_ALL_ALLOWED_CONTROLLERS. A new one is a"
                + " widening of the public surface and needs the requirement amended first.");
  }

  @Test
  void readEndpointsMustDeclareAnAuthorisationAnnotation() {
    DescribedPredicate<JavaMethod> selection =
        publicMethodsOf(CONTROLLER_CODE)
            .and(annotatedWithAnyOf(GetMapping.class, RequestMapping.class));
    assertMethodFloor("readEndpointsMustDeclareAnAuthorisationAnnotation", selection, 246);
    endpointsMustDeclareAnAuthorisationAnnotationRule(selection)
        .because(
            "Every read endpoint must carry an explicit @PreAuthorize (method- or class-level)."
                + " REQ-SEC-052 leaves the URL matrix naming only the public surface, so a read"
                + " without a gate of its own is protected by nothing that lives next to it.")
        .check(CLASSES);
  }

  static ArchRule endpointsMustDeclareAnAuthorisationAnnotationRule(
      DescribedPredicate<JavaMethod> selection) {
    return methods().that(selection).should(haveMethodOrClassLevelPreAuthorize());
  }

  @Test
  void orgUnitBankSettingsMutationsMustCallAnAuthorizationHelper() {
    DescribedPredicate<JavaMethod> selection =
        bankSettingsMutations(OrgUnitBankAccessService.class, OrgUnitBankAccountSettingsDto.class);
    assertMethodFloor("orgUnitBankSettingsMutationsMustCallAnAuthorizationHelper", selection, 15);
    orgUnitBankSettingsMutationsRule(selection).check(CLASSES);
  }

  static DescribedPredicate<JavaMethod> bankSettingsMutations(
      Class<?> accessService, Class<?> settingsDto) {
    return publicMethodsOf(JavaClass.Predicates.equivalentTo(accessService))
        .and(
            DescribedPredicate.describe(
                "are set*/add*/remove*/clear* methods returning " + settingsDto.getSimpleName(),
                m ->
                    m.getName().matches("(set|add|remove|clear).*")
                        && m.getRawReturnType().isEquivalentTo(settingsDto)));
  }

  static ArchRule orgUnitBankSettingsMutationsRule(DescribedPredicate<JavaMethod> selection) {
    return methods()
        .that(selection)
        .should(callARequireCanAuthorizationHelper())
        .because(
            "Every org-unit bank settings mutation must authorize via a requireCan* helper; a"
                + " dropped check would be reachable by any authenticated member (the controller"
                + " and proxy only require isAuthenticated()).");
  }

  @Test
  void controllerLayerShouldNotDependOnRepositoryLayer() {
    assertClassFloor("controllerLayerShouldNotDependOnRepositoryLayer", CONTROLLER_CODE, 99);
    assertClassFloor(
        "controllerLayerShouldNotDependOnRepositoryLayer (targets)", REPOSITORY_CODE, 115);
    controllerLayerShouldNotDependOnRepositoryLayerRule().check(CLASSES);
  }

  static ArchRule controllerLayerShouldNotDependOnRepositoryLayerRule() {
    return noClasses()
        .that(CONTROLLER_CODE)
        .should()
        .dependOnClassesThat(REPOSITORY_CODE)
        .because(
            "Controllers must go through the service layer — a controller depending on a "
                + "repository bypasses @Transactional boundaries, owner filtering and the "
                + "@PreAuthorize seam, all of which live in services.");
  }

  @Test
  void controllerLayerMustNotWriteAuditRowsDirectly() {
    assertClassFloor("controllerLayerMustNotWriteAuditRowsDirectly", CONTROLLER_CODE, 99);
    controllerLayerMustNotWriteAuditRowsDirectlyRule(
            AuditRecorder.class, AuditService.class, BankAuditService.class)
        .check(CLASSES);
  }

  static ArchRule controllerLayerMustNotWriteAuditRowsDirectlyRule(Class<?>... auditServices) {
    return noClasses()
        .that(CONTROLLER_CODE)
        .should()
        .callMethodWhere(callsAuditRecord(auditServices))
        .because(
            "record(...) needs a surrounding transaction (MANDATORY) that a controller cannot "
                + "provide. Put the call in the service that owns the operation — inside the "
                + "business transaction for a mutation, or in a small @Transactional method of its "
                + "own for an audited read (see DataExportService#recordExport / "
                + "PersonSearchService#recordSearch).");
  }

  /**
   * Matches a call to {@code record(..)} on one of the given audit services, whatever its
   * signature.
   *
   * @param auditServices the audit services whose {@code record} method is meant
   * @return the predicate, matched on owner and method name so a future parameter change cannot
   *     silently disarm the rule
   */
  static DescribedPredicate<JavaCall<?>> callsAuditRecord(Class<?>... auditServices) {
    Arrays.stream(auditServices).forEach(s -> methodName(s, "record"));
    Set<String> owners =
        Arrays.stream(auditServices).map(Class::getName).collect(Collectors.toSet());
    return DescribedPredicate.describe(
        "a call to record(..) on " + owners,
        call ->
            owners.contains(call.getTargetOwner().getFullName())
                && "record".equals(call.getTarget().getName()));
  }

  @Test
  void everyExchangeControllerMethodCarriesTheExchangeGate() {
    DescribedPredicate<JavaMethod> selection =
        publicMethodsOf(CONTROLLER_CODE.and(EXCHANGE_DOMAIN));
    assertMethodFloor("everyExchangeControllerMethodCarriesTheExchangeGate", selection, 14);
    everyExchangeControllerMethodCarriesTheExchangeGateRule(selection).check(CLASSES);
  }

  static ArchRule everyExchangeControllerMethodCarriesTheExchangeGateRule(
      DescribedPredicate<JavaMethod> selection) {
    String gate = "@" + beanName(ExchangeGate.class) + ".";
    return methods()
        .that(selection)
        .should(
            new ArchCondition<JavaMethod>("gate on " + gate + " in @PreAuthorize") {
              @Override
              public void check(JavaMethod method, ConditionEvents events) {
                if (!preAuthorizeValue(method).contains(gate)) {
                  events.add(
                      SimpleConditionEvent.violated(
                          method, method.getFullName() + " is not gated by " + gate));
                }
              }
            })
        .because("REQ-XCH-004: the backend re-checks every relayed capability itself");
  }

  @Test
  void exchangeControllersCallExchangeServicesOnly() {
    DescribedPredicate<JavaClass> selection = CONTROLLER_CODE.and(EXCHANGE_DOMAIN);
    assertClassFloor("exchangeControllersCallExchangeServicesOnly", selection, 8);
    exchangeControllersCallExchangeServicesOnlyRule(selection).check(CLASSES);
  }

  static ArchRule exchangeControllersCallExchangeServicesOnlyRule(
      DescribedPredicate<JavaClass> selection) {
    return noClasses()
        .that(selection)
        .should()
        .dependOnClassesThat(REPOSITORY_CODE.or(MAPPER_CODE))
        .orShould()
        .dependOnClassesThat(
            SERVICE_CODE
                .and(DescribedPredicate.not(EXCHANGE_DOMAIN))
                .as("a service outside the exchange domain"))
        .because(
            "REQ-XCH-009: the exchange layer reaches the domain only through its own services");
  }

  @Test
  void exchangeDtosStayInTheExchangeLayer() {
    DescribedPredicate<JavaClass> selection = exchangeDtos();
    assertClassFloor("exchangeDtosStayInTheExchangeLayer", selection, 34);
    exchangeDtosStayInTheExchangeLayerRule(selection).check(CLASSES);
  }

  static DescribedPredicate<JavaClass> exchangeDtos() {
    return EXCHANGE_DOMAIN.and(inDomain("dto")).as("exchange DTOs");
  }

  static ArchRule exchangeDtosStayInTheExchangeLayerRule(DescribedPredicate<JavaClass> selection) {
    return classes()
        .that(selection)
        .should()
        .onlyBeAccessed()
        .byClassesThat(EXCHANGE_DOMAIN.or(REPOSITORY_CODE))
        .because("the exchange contract must not leak into the web or app API");
  }

  @Test
  void exchangeServicesNeverUseAdminGatesOrTheAdminScope() {
    DescribedPredicate<JavaClass> selection = SERVICE_CODE.and(EXCHANGE_DOMAIN);
    assertClassFloor("exchangeServicesNeverUseAdminGatesOrTheAdminScope", selection, 52);
    exchangeServicesNeverUseAdminGatesRule(selection, OwnerScopeService.class).check(CLASSES);
  }

  static ArchRule exchangeServicesNeverUseAdminGatesRule(
      DescribedPredicate<JavaClass> selection, Class<?> ownerScope) {
    String scopeMethod = methodName(ownerScope, "currentScopePredicate");
    return noClasses()
        .that(selection)
        .should()
        .callMethodWhere(
            DescribedPredicate.describe(
                "an ADMIN-gated method",
                (JavaMethodCall call) ->
                    call.getTarget().resolveMember().stream()
                        .anyMatch(member -> preAuthorizeValue(member).contains("ADMIN"))))
        .orShould()
        .callMethodWhere(
            DescribedPredicate.describe(
                ownerScope.getSimpleName() + "." + scopeMethod + "(..)",
                (JavaMethodCall call) ->
                    call.getTargetOwner().isEquivalentTo(ownerScope)
                        && scopeMethod.equals(call.getTarget().getName())))
        .because("REQ-XCH-009: an ADMIN member acts on the exchange with own data only");
  }

  @Test
  void leafHelpersMustStayDependencyLeaves() {
    assertClassFloor("leafHelpersMustStayDependencyLeaves", LEAF_HELPERS, 63);
    leafHelpersMustStayDependencyLeavesRule(
            LEAF_HELPERS, LEAF_HELPERS.or(MODEL_CODE).or(REPOSITORY_CODE), ROOT_PACKAGE)
        .check(CLASSES);
  }

  static ArchRule leafHelpersMustStayDependencyLeavesRule(
      DescribedPredicate<JavaClass> helpers,
      DescribedPredicate<JavaClass> allowedInside,
      String rootPackage) {
    return classes()
        .that(helpers)
        .should()
        .onlyDependOnClassesThat(
            allowedInside.or(
                DescribedPredicate.describe(
                    "lie outside " + rootPackage,
                    (JavaClass c) -> !isInPackageTree(c.getPackageName(), rootPackage))))
        .because(
            "a leaf helper may depend only on the other leaf helpers, the entity model and the"
                + " repositories, never on a service, mapper, controller or any other layer. Logic"
                + " that a mapper and a service share belongs to the module that owns it, inverted"
                + " through a small SPI there.");
  }

  @Test
  void backendPackagesShouldBeFreeOfDependencyCycles() {
    long topLevelPackages =
        CLASSES.stream()
            .map(JavaClass::getPackageName)
            .filter(p -> p.startsWith(ROOT_PACKAGE + "."))
            .map(p -> p.substring(ROOT_PACKAGE.length() + 1).split("\\.")[0])
            .distinct()
            .count();
    assertFloor("backendPackagesShouldBeFreeOfDependencyCycles (slices)", topLevelPackages, 21);
    backendPackagesShouldBeFreeOfDependencyCyclesRule(ROOT_PACKAGE).check(CLASSES);
  }

  static ArchRule backendPackagesShouldBeFreeOfDependencyCyclesRule(String rootPackage) {
    return slices()
        .matching(rootPackage + ".(*)..")
        .should()
        .beFreeOfCycles()
        .because(
            "backend packages must form an acyclic dependency graph (ADR-0047); invert the"
                + " dependency through a small SPI owned by the lower package instead of closing a"
                + " package cycle.");
  }

  /**
   * The layers inside each module are free of cycles (ADR-0047): a top-level package named in
   * {@link #LAYER_PACKAGE_NAMES} is a layer of the root module, any other top-level package is a
   * domain module whose sub-packages are its layers.
   */
  @Test
  void layersInsideEachModuleShouldBeFreeOfDependencyCycles() {
    Set<String> modules = layerModules(CLASSES, ROOT_PACKAGE, LAYER_PACKAGE_NAMES);
    long layerSlices =
        CLASSES.stream()
            .map(c -> layerModuleOf(c, ROOT_PACKAGE, LAYER_PACKAGE_NAMES))
            .filter(java.util.Objects::nonNull)
            .distinct()
            .count();
    assertFloor(
        "layersInsideEachModuleShouldBeFreeOfDependencyCycles (modules)", modules.size(), 1);
    assertFloor("layersInsideEachModuleShouldBeFreeOfDependencyCycles (layers)", layerSlices, 21);
    for (String module : modules) {
      layersInsideModuleRule(module, ROOT_PACKAGE, LAYER_PACKAGE_NAMES).check(CLASSES);
    }
  }

  /**
   * The modules found below a root package.
   *
   * @param classes the imported classes
   * @param rootPackage the root package
   * @param layerNames the top-level package names that are layers of the root module
   * @return the module names, {@code ""} for the root module
   */
  static Set<String> layerModules(JavaClasses classes, String rootPackage, Set<String> layerNames) {
    return classes.stream()
        .map(c -> layerModuleOf(c, rootPackage, layerNames))
        .filter(java.util.Objects::nonNull)
        .map(id -> id.get(0))
        .collect(Collectors.toCollection(TreeSet::new));
  }

  /**
   * The module and layer of a class, or {@code null} for a class outside every layer.
   *
   * @param javaClass the class
   * @param rootPackage the root package
   * @param layerNames the top-level package names that are layers of the root module
   * @return a two-element list of module ({@code ""} for the root module) and layer, or {@code
   *     null}
   */
  static List<String> layerModuleOf(
      JavaClass javaClass, String rootPackage, Set<String> layerNames) {
    String pkg = javaClass.getPackageName();
    if (!pkg.startsWith(rootPackage + ".")) {
      return null;
    }
    String[] segments = pkg.substring(rootPackage.length() + 1).split("\\.");
    if (layerNames.contains(segments[0])) {
      return List.of("", segments[0]);
    }
    return List.of(segments[0], segments.length > 1 ? segments[1] : "");
  }

  static ArchRule layersInsideModuleRule(
      String module, String rootPackage, Set<String> layerNames) {
    String moduleName = module.isEmpty() ? "the root module" : "module " + module;
    return slices()
        .assignedFrom(
            new SliceAssignment() {
              @Override
              public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
                List<String> id = layerModuleOf(javaClass, rootPackage, layerNames);
                if (id == null || !id.get(0).equals(module)) {
                  return SliceIdentifier.ignore();
                }
                return SliceIdentifier.of(id.get(1).isEmpty() ? "(module root)" : id.get(1));
              }

              @Override
              public String getDescription() {
                return "the layers of " + moduleName;
              }
            })
        .should()
        .beFreeOfCycles()
        .because(
            "the layers inside a module must form an acyclic dependency graph (ADR-0047); invert"
                + " the dependency through a small SPI owned by the lower layer.");
  }

  @Test
  void mapperLayerShouldNotDependOnServiceLayer() {
    assertClassFloor("mapperLayerShouldNotDependOnServiceLayer", MAPPER_CODE, 100);
    mustNotDependOnServicesRule(MAPPER_CODE)
        .because(
            "the service layer depends on mappers, so a mapper -> service edge re-creates the "
                + "mapper <-> service package cycle (ADR-0047). Invert it through a small SPI"
                + " owned by the mapper's domain.")
        .check(CLASSES);
  }

  static ArchRule mustNotDependOnServicesRule(DescribedPredicate<JavaClass> selection) {
    return noClasses().that(selection).should().dependOnClassesThat(SERVICE_CODE);
  }

  @Test
  void integrationLayerShouldNotDependOnServiceLayer() {
    assertClassFloor("integrationLayerShouldNotDependOnServiceLayer", INTEGRATION_CODE, 2);
    mustNotDependOnServicesRule(INTEGRATION_CODE)
        .because(
            "the service layer orchestrates the integration clients, so an integration -> service "
                + "edge re-creates the integration <-> service package cycle (ADR-0047). "
                + "Orchestrators that call services belong in service.scwiki, not integration.")
        .check(CLASSES);
  }

  @Test
  void eventLayerShouldNotDependOnServiceLayer() {
    assertClassFloor("eventLayerShouldNotDependOnServiceLayer", EVENT_CODE, 19);
    mustNotDependOnServicesRule(EVENT_CODE)
        .because(
            "event payloads are data-only, so an event -> service edge re-creates the "
                + "event <-> service package cycle (ADR-0047). Event listeners/producers belong in "
                + "the service layer.")
        .check(CLASSES);
  }

  @Test
  void validationLayerMustStayADependencyLeaf() {
    assertClassFloor("validationLayerMustStayADependencyLeaf", VALIDATION_CODE, 8);
    validationLayerMustStayADependencyLeafRule(VALIDATION_CODE).check(CLASSES);
  }

  static ArchRule validationLayerMustStayADependencyLeafRule(
      DescribedPredicate<JavaClass> selection) {
    return noClasses()
        .that(selection)
        .should()
        .dependOnClassesThat(MODEL_CODE.or(REPOSITORY_CODE).or(SERVICE_CODE))
        .because(
            "model.dto references the constraint annotations, so validation must stay a leaf; a"
                + " validation -> model/repository/service edge re-creates the model <-> validation"
                + " package cycle (ADR-0047). Invert through a leaf SPI like"
                + " MaterialPieceTypeLookup.");
  }

  @Test
  void controllerMethodsShouldNotExposeJpaEntitiesInGenericWrappers() {
    DescribedPredicate<JavaMethod> selection = publicMethodsOf(CONTROLLER_CODE);
    assertMethodFloor(
        "controllerMethodsShouldNotExposeJpaEntitiesInGenericWrappers", selection, 725);
    controllerMethodsShouldNotExposeJpaEntitiesInGenericWrappersRule(selection).check(CLASSES);
  }

  static ArchRule controllerMethodsShouldNotExposeJpaEntitiesInGenericWrappersRule(
      DescribedPredicate<JavaMethod> selection) {
    return methods()
        .that(selection)
        .should(notReturnAnEntityInsideAGenericWrapper())
        .because(
            "Controllers must wrap DTOs, never JPA entities, even when the entity is "
                + "tucked inside ResponseEntity<…>/Page<…>/List<…>/Optional<…>/etc.");
  }

  @Test
  void mutatingServiceMethodsInReadOnlyClassesNeedExplicitTransactional() {
    assertClassFloor(
        "mutatingServiceMethodsInReadOnlyClassesNeedExplicitTransactional", SERVICE_CODE, 273);
    mutatingServiceMethodsInReadOnlyClassesRule().check(CLASSES);
  }

  static ArchRule mutatingServiceMethodsInReadOnlyClassesRule() {
    return classes()
        .that(SERVICE_CODE)
        .should(declareTransactionalForMutatingMethodsWhenClassIsReadOnly())
        .because(
            "A class-level @Transactional(readOnly = true) silently propagates to every "
                + "method — mutating operations must explicitly override it with their own "
                + "@Transactional, otherwise the write happens in a read-only transaction.");
  }

  @Test
  void repositoriesMustNotDeclareNoArgFindAll() {
    DescribedPredicate<JavaMethod> selection = methodsOf(REPOSITORY_CODE);
    assertMethodFloor("repositoriesMustNotDeclareNoArgFindAll", selection, 705);
    repositoriesMustNotDeclareNoArgFindAllRule(selection).check(CLASSES);
  }

  static ArchRule repositoriesMustNotDeclareNoArgFindAllRule(
      DescribedPredicate<JavaMethod> selection) {
    return methods()
        .that(selection)
        .should(notBeANoArgFindAll())
        .because(
            "Repositories must not override no-arg findAll() (M-9 from the performance "
                + "audit). Use findAll(Pageable) or a scoped query method instead.");
  }

  @Test
  void writeEndpointsMustDeclareAnAuthorisationAnnotation() {
    DescribedPredicate<JavaMethod> selection =
        publicMethodsOf(CONTROLLER_CODE)
            .and(
                annotatedWithAnyOf(
                    PostMapping.class,
                    PutMapping.class,
                    DeleteMapping.class,
                    PatchMapping.class,
                    RequestMapping.class));
    assertMethodFloor("writeEndpointsMustDeclareAnAuthorisationAnnotation", selection, 330);
    endpointsMustDeclareAnAuthorisationAnnotationRule(selection)
        .because(
            "Every state-changing HTTP endpoint must carry an explicit @PreAuthorize "
                + "(either method-level or class-level). For deliberately public endpoints "
                + "use @PreAuthorize(\"permitAll()\") so the auth contract stays visible "
                + "next to the handler instead of buried in SecurityConfig.")
        .check(CLASSES);
  }

  /**
   * Staffel-scoped aggregate services must inject {@code AuthHelperService} or {@code
   * OwnerScopeService}, so their data cannot leak across org units.
   */
  @Test
  void staffelScopedServicesMustWireOwnerScopeOrAuthHelper() {
    DescribedPredicate<JavaClass> selection = classesIn(STAFFEL_SCOPED_SERVICES);
    assertClassFloor("staffelScopedServicesMustWireOwnerScopeOrAuthHelper", selection, 12);
    staffelScopedServicesMustWireAScopeGuardRule(selection, SCOPE_GUARDS).check(CLASSES);
  }

  static ArchRule staffelScopedServicesMustWireAScopeGuardRule(
      DescribedPredicate<JavaClass> selection, Set<Class<?>> guards) {
    Set<String> guardNames = guards.stream().map(Class::getName).collect(Collectors.toSet());
    return classes()
        .that(selection)
        .should(
            new ArchCondition<>("inject one of " + guardNames) {
              @Override
              public void check(JavaClass javaClass, ConditionEvents events) {
                boolean hasIt =
                    javaClass.getFields().stream()
                        .map(f -> f.getRawType().getFullName())
                        .anyMatch(guardNames::contains);
                if (!hasIt) {
                  events.add(
                      SimpleConditionEvent.violated(
                          javaClass,
                          javaClass.getName()
                              + " is in the staffel-scoped service list but injects none of "
                              + guardNames
                              + " - that means it cannot enforce the multi-tenant filter /"
                              + " org-unit stamp."));
                }
              }
            });
  }

  /**
   * Mission endpoints whose gate admits members below Logistician and return a PII-carrying mission
   * DTO must call a {@code cleanup…ForPeer} helper (REQ-SEC-007).
   */
  @Test
  void peerReadableMissionEndpointsMustRedactPii() {
    DescribedPredicate<JavaMethod> selection = peerReadableEndpoints(MISSION_PII_CARRYING_DTOS);
    assertMethodFloor("peerReadableMissionEndpointsMustRedactPii", selection, 22);
    peerReadableMissionEndpointsMustRedactPiiRule(selection, MISSION_PII_CARRYING_DTOS)
        .check(CLASSES);
  }

  static DescribedPredicate<JavaMethod> peerReadableEndpoints(Set<Class<?>> piiDtos) {
    return publicMethodsOf(CONTROLLER_CODE)
        .and(hasPeerReachableGate())
        .and(
            DescribedPredicate.describe(
                "return a PII-carrying DTO, or a known generic wrapper of one",
                (JavaMethod m) -> !protectedDtosOf(m, piiDtos).isEmpty()));
  }

  static ArchRule peerReadableMissionEndpointsMustRedactPiiRule(
      DescribedPredicate<JavaMethod> selection, Set<Class<?>> piiDtos) {
    return methods()
        .that(selection)
        .should(callOneOfTheGuestRedactionHelpers(piiDtos))
        .because(
            "Mission endpoints reachable by a member below Logistician must apply "
                + "cleanupMissionForPeer or cleanupParticipantForPeer before returning "
                + "(REQ-SEC-007) — audit finding C-1: addParticipantPublic / addParticipantSlim "
                + "leaked full participant emails and real names because the redaction pass was"
                + " skipped on the write paths.");
  }

  static DescribedPredicate<JavaMethod> bodyAcceptingWrites() {
    return publicMethodsOf(CONTROLLER_CODE)
        .and(annotatedWithAnyOf(PostMapping.class, PutMapping.class, PatchMapping.class));
  }

  /** The Mission write requests must not declare server-managed components (audit finding C-4). */
  @Test
  void missionWriteRequestDtosMustNotCarryServerManagedFields() {
    DescribedPredicate<JavaClass> selection = classesIn(MISSION_WRITE_REQUESTS);
    assertClassFloor("missionWriteRequestDtosMustNotCarryServerManagedFields", selection, 2);
    missionWriteRequestDtosRule(selection, UpdateMissionRequest.class).check(CLASSES);
  }

  static ArchRule missionWriteRequestDtosRule(
      DescribedPredicate<JavaClass> selection, Class<?> updateRequest) {
    return classes()
        .that(selection)
        .should(
            new ArchCondition<JavaClass>(
                "not declare any server-managed record component (owningSquadron, owner, parent,"
                    + " …)") {
              @Override
              public void check(JavaClass clazz, ConditionEvents events) {
                boolean isUpdateDto = clazz.isEquivalentTo(updateRequest);
                clazz.getFields().stream()
                    .filter(f -> !f.getModifiers().contains(JavaModifier.STATIC))
                    .map(JavaField::getName)
                    .filter(FORBIDDEN_MISSION_REQUEST_COMPONENTS::contains)
                    .filter(name -> !(isUpdateDto && "version".equals(name)))
                    .forEach(
                        name ->
                            events.add(
                                SimpleConditionEvent.violated(
                                    clazz,
                                    clazz.getFullName()
                                        + " declares record component `"
                                        + name
                                        + "` — that field is server-managed (audit finding C-4)."
                                        + " Stamp it inside MissionService, not from the request"
                                        + " body. If it is legitimately client-supplied, carve it"
                                        + " out of FORBIDDEN_MISSION_REQUEST_COMPONENTS and give"
                                        + " the reason in the commit message and the PR.")));
              }
            })
        .because(
            "MissionService.createMission / addSubMission stamp owner / owningSquadron / parent"
                + " from the authenticated principal and the path-resolved parent — never from the"
                + " request body. The request records must not grow components for those concerns"
                + " or the squadron-stamp-forgery vector returns. See audit finding C-4.");
  }

  /**
   * The {@code participants} collection of {@code Mission} must keep
   * {@code @OptimisticLock(excluded = true)}, so concurrent signups never bump the mission version
   * and 409.
   */
  @Test
  void missionParticipantsCollectionMustExcludeOptimisticLock() {
    assertClassFloor(
        "missionParticipantsCollectionMustExcludeOptimisticLock",
        classesIn(Set.of(Mission.class)),
        1);
    missionParticipantsCollectionRule(Mission.class).check(CLASSES);
  }

  static ArchRule missionParticipantsCollectionRule(Class<?> mission) {
    return classes()
        .that(classesIn(Set.of(mission)))
        .should(missionParticipantsFieldHasOptimisticLockExcluded())
        .because(
            "Mission.participants must remain @OptimisticLock(excluded = true) so concurrent"
                + " participant signups do not bump Mission.version. Removing the annotation"
                + " re-opens 409s on parallel \"Anmelden\" clicks — see"
                + " MissionParticipantConcurrencyTest.");
  }

  /**
   * {@code PromotionTopic.owningSquadron} must stay typed {@code Squadron}, not {@code OrgUnit}, so
   * a special command can never be assigned.
   */
  @Test
  void promotionTopicOwningSquadronMustStayTypedSquadronNotOrgUnit() {
    assertClassFloor(
        "promotionTopicOwningSquadronMustStayTypedSquadronNotOrgUnit",
        classesIn(Set.of(PromotionTopic.class)),
        1);
    promotionTopicOwningSquadronRule(PromotionTopic.class, Squadron.class).check(CLASSES);
  }

  static ArchRule promotionTopicOwningSquadronRule(Class<?> topic, Class<?> squadron) {
    return classes()
        .that(classesIn(Set.of(topic)))
        .should(
            new ArchCondition<JavaClass>(
                "declare an `owningSquadron` field whose raw type is "
                    + squadron.getSimpleName()
                    + " (not OrgUnit)") {
              @Override
              public void check(JavaClass javaClass, ConditionEvents events) {
                var owningSquadronField =
                    javaClass.getFields().stream()
                        .filter(f -> "owningSquadron".equals(f.getName()))
                        .findFirst();
                if (owningSquadronField.isEmpty()) {
                  events.add(
                      SimpleConditionEvent.violated(
                          javaClass,
                          javaClass.getSimpleName()
                              + " is missing the `owningSquadron` field —"
                              + " SPEZIALKOMMANDO_PLAN.md §3.3 explicitly keeps this field"
                              + " typed Squadron (not OrgUnit) so promotion data can only"
                              + " reference Squadron rows. If you renamed it, restore the"
                              + " field; if you removed it entirely, drop this guard and give"
                              + " the reason in the commit message and the PR."));
                  return;
                }
                JavaClass rawType = owningSquadronField.get().getRawType();
                if (!rawType.isEquivalentTo(squadron)) {
                  events.add(
                      SimpleConditionEvent.violated(
                          owningSquadronField.get(),
                          javaClass.getSimpleName()
                              + ".owningSquadron has raw type "
                              + rawType.getFullName()
                              + " — must stay "
                              + squadron.getName()
                              + ". Loosening it to OrgUnit lets a SpecialCommand reference"
                              + " sneak past the application-side guard"
                              + " (SPEZIALKOMMANDO_PLAN.md §8.2 / §11 R4)."));
                }
              }
            });
  }

  /**
   * The {@code addParticipant} methods of the mission services must not call {@code
   * MissionRepository#save*}, which would dirty the mission row and cause 409s on concurrent
   * signups.
   */
  @Test
  void missionServiceAddParticipantMustNotSaveMission() {
    DescribedPredicate<JavaMethod> selection =
        addParticipantMethods(Set.of(MissionParticipantService.class, MissionService.class));
    assertMethodFloor("missionServiceAddParticipantMustNotSaveMission", selection, 4);
    addParticipantMustNotSaveRule(selection, MissionRepository.class).check(CLASSES);
  }

  static DescribedPredicate<JavaMethod> addParticipantMethods(Set<Class<?>> services) {
    services.forEach(s -> methodName(s, "addParticipant"));
    return methodsOf(classesIn(services))
        .and(
            DescribedPredicate.describe(
                "are named addParticipant", m -> m.getName().equals("addParticipant")));
  }

  static ArchRule addParticipantMustNotSaveRule(
      DescribedPredicate<JavaMethod> selection, Class<?> missionRepository) {
    return methods()
        .that(selection)
        .should(notCallSaveOn(missionRepository))
        .because(
            "MissionParticipantService.addParticipant (and the MissionService facade delegation)"
                + " must not bump Mission.version under concurrent signups — calling"
                + " missionRepository.save(mission) inside the flow would dirty the parent row and"
                + " re-open 409s on parallel \"Anmelden\" clicks. Persist the new participant via"
                + " missionParticipantRepository.save(participant) and let Hibernate's cascade"
                + " handle the rest. See MissionParticipantConcurrencyTest.");
  }

  /**
   * Every top-level class in {@code ScWikiClient}'s package except the client itself must inject
   * {@code ScWikiClient}, unless listed in {@link #SCWIKI_CLIENT_INJECTION_EXEMPT}.
   */
  @Test
  void scWikiIntegrationClassesMustWireScWikiClient() {
    DescribedPredicate<JavaClass> selection =
        packageTreeOf("ScWikiClient's package", ScWikiClient.class);
    assertClassFloor("scWikiIntegrationClassesMustWireScWikiClient", selection, 1);
    scWikiIntegrationClassesMustWireTheClientRule(
            selection, ScWikiClient.class, SCWIKI_CLIENT_INJECTION_EXEMPT)
        .check(CLASSES);
  }

  static ArchRule scWikiIntegrationClassesMustWireTheClientRule(
      DescribedPredicate<JavaClass> selection, Class<?> client, Set<Class<?>> exempt) {
    return classes()
        .that(selection)
        .should(
            new ArchCondition<JavaClass>("be " + client.getSimpleName() + " or inject it") {
              @Override
              public void check(JavaClass javaClass, ConditionEvents events) {
                if (!javaClass.isTopLevelClass()
                    || javaClass.isEquivalentTo(client)
                    || exempt.stream().anyMatch(javaClass::isEquivalentTo)) {
                  return;
                }
                boolean injectsClient =
                    javaClass.getFields().stream()
                        .anyMatch(f -> f.getRawType().isEquivalentTo(client));
                if (!injectsClient) {
                  events.add(
                      SimpleConditionEvent.violated(
                          javaClass,
                          javaClass.getName()
                              + " lives beside "
                              + client.getSimpleName()
                              + " but does not inject it. Either depend on the shared HTTP client"
                              + " or move the class to a different package (e.g."
                              + " service.scwiki)."));
                }
              }
            });
  }

  /**
   * No model class outside {@link #SQUADRON_ID_COLUMN_GRANDFATHERED} may map
   * {@code @JoinColumn(name = "squadron_id")}; new aggregates use {@code owning_squadron_id} or
   * {@code owning_org_unit_id}.
   */
  @Test
  void noNewJoinColumnReferencingSquadronIdOutsideGrandfatheredEntities() {
    DescribedPredicate<JavaClass> selection = nonInterfaces(MODEL_CODE);
    assertClassFloor(
        "noNewJoinColumnReferencingSquadronIdOutsideGrandfatheredEntities", selection, 589);
    noSquadronIdJoinColumnRule(selection, SQUADRON_ID_COLUMN_GRANDFATHERED).check(CLASSES);
  }

  static DescribedPredicate<JavaClass> nonInterfaces(DescribedPredicate<JavaClass> selection) {
    return selection.and(
        DescribedPredicate.describe("are not interfaces", (JavaClass c) -> !c.isInterface()));
  }

  static ArchRule noSquadronIdJoinColumnRule(
      DescribedPredicate<JavaClass> selection, Set<Class<?>> grandfathered) {
    return classes()
        .that(selection)
        .should(
            new ArchCondition<JavaClass>(
                "not declare any @JoinColumn(name = \"squadron_id\") field outside the"
                    + " grandfathered legacy entities") {
              @Override
              public void check(JavaClass javaClass, ConditionEvents events) {
                if (grandfathered.stream().anyMatch(javaClass::isEquivalentTo)) {
                  return;
                }
                for (JavaField field : javaClass.getFields()) {
                  if (!field.isAnnotatedWith(JoinColumn.class)) {
                    continue;
                  }
                  String name =
                      field
                          .getAnnotationOfType(JoinColumn.class.getName())
                          .tryGetExplicitlyDeclaredProperty("name")
                          .map(Object::toString)
                          .orElse("");
                  if ("squadron_id".equals(name)) {
                    events.add(
                        SimpleConditionEvent.violated(
                            field,
                            javaClass.getFullName()
                                + "#"
                                + field.getName()
                                + " uses @JoinColumn(name = \"squadron_id\") — that column name"
                                + " is on the destructive-cleanup drop list"
                                + " (SPEZIALKOMMANDO_PLAN.md §4 R3). Use owning_squadron_id"
                                + " (legacy mirror) or owning_org_unit_id (new column) on new"
                                + " staffel-scoped aggregates. Only the grandfathered legacy"
                                + " entities in SQUADRON_ID_COLUMN_GRANDFATHERED may use this"
                                + " column name; a new entry needs its reason in the commit"
                                + " message and the PR."));
                  }
                }
              }
            });
  }

  /**
   * REQ-BANK-019 (season independence): no bank class may depend on the mission, operation,
   * job-order, price-line or season aggregates.
   */
  @Test
  void bankClassesMustStaySeasonAndProfitIndependent() {
    assertClassFloor("bankClassesMustStaySeasonAndProfitIndependent", BANK_DOMAIN, 121);
    assertClassFloor(
        "bankClassesMustStaySeasonAndProfitIndependent (targets)",
        profitFlowTypes(ROOT_PACKAGE),
        149);
    bankClassesMustStaySeasonAndProfitIndependentRule(ROOT_PACKAGE).check(CLASSES);
  }

  static DescribedPredicate<JavaClass> profitFlowTypes(String rootPackage) {
    return DescribedPredicate.describe(
        "mission/operation/job-order/price-line/season aggregates (REQ-BANK-019)",
        (JavaClass input) ->
            isInPackageTree(input.getPackageName(), rootPackage)
                && (PROFIT_FLOW_NAME_PREFIXES.stream()
                        .anyMatch(p -> outermost(input).getSimpleName().startsWith(p))
                    || PROFIT_FLOW_DOMAINS.stream().anyMatch(d -> inDomain(d).test(input))));
  }

  static ArchRule bankClassesMustStaySeasonAndProfitIndependentRule(String rootPackage) {
    return noClasses()
        .that(BANK_DOMAIN)
        .should()
        .dependOnClassesThat(profitFlowTypes(rootPackage))
        .because(
            "the bank has no coupling to seasons, price lines or profit flows (REQ-BANK-019);"
                + " integrations require a spec change first");
  }

  /**
   * No bank class may use {@code OwnerScopeService}; bank authorization depends only on the bank
   * roles and grants (REQ-BANK-008).
   */
  @Test
  void bankClassesMustNotConsultOrgUnitScope() {
    assertClassFloor("bankClassesMustNotConsultOrgUnitScope", BANK_DOMAIN, 121);
    bankClassesMustNotConsultOrgUnitScopeRule(OwnerScopeService.class).check(CLASSES);
  }

  static ArchRule bankClassesMustNotConsultOrgUnitScopeRule(Class<?> ownerScope) {
    return noClasses()
        .that(BANK_DOMAIN)
        .should()
        .dependOnClassesThat()
        .belongToAnyOf(ownerScope)
        .because(
            "bank gates are independent of org-unit membership in both directions"
                + " (REQ-BANK-008); only bank roles and bank_account_grant rows decide");
  }

  /**
   * Every production class named after the bank is classified: a bank class, the org-unit side, or
   * in the bank module package, so a new bank class cannot slip past the bank rules.
   */
  @Test
  void everyBankNamedClassIsClassified() {
    DescribedPredicate<JavaClass> selection =
        DescribedPredicate.describe(
            "top-level classes named after the bank",
            (JavaClass c) -> c.isTopLevelClass() && c.getSimpleName().contains("Bank"));
    assertClassFloor("everyBankNamedClassIsClassified", selection, 130);
    everyBankNamedClassIsClassifiedRule(selection).check(CLASSES);
  }

  static ArchRule everyBankNamedClassIsClassifiedRule(DescribedPredicate<JavaClass> selection) {
    return classes()
        .that(selection)
        .should(
            new ArchCondition<JavaClass>(
                "be a registered bank class, on the org-unit side, or in the bank module") {
              @Override
              public void check(JavaClass javaClass, ConditionEvents events) {
                if (BANK_DOMAIN.test(javaClass)
                    || ORG_UNIT_BANK_SIDE.stream().anyMatch(javaClass::isEquivalentTo)) {
                  return;
                }
                events.add(
                    SimpleConditionEvent.violated(
                        javaClass,
                        javaClass.getName()
                            + " is named after the bank but is neither in BANK_CLASSES nor in"
                            + " ORG_UNIT_BANK_SIDE; add it to the list it belongs to so the bank"
                            + " rules see it (REQ-BANK-008, REQ-BANK-019)."));
              }
            });
  }

  /**
   * {@code OrgUnitCascadeService} must not consult the security context, so the cascading scope is
   * a pure function of memberships and hierarchy and can never yield an admin scope (REQ-ORG-015).
   */
  @Test
  void cascadeServiceMustNotConsultTheSecurityContext() {
    assertClassFloor(
        "cascadeServiceMustNotConsultTheSecurityContext",
        classesIn(Set.of(OrgUnitCascadeService.class)),
        1);
    mustNotDependOnRule(OrgUnitCascadeService.class, AuthHelperService.class)
        .because(
            "the cascading-scope expansion must be a pure function of memberships + hierarchy and"
                + " must never branch on admin status, so it can never route an OL/Bereich"
                + " principal through adminAllScope / isAdmin (epic #692, REQ-ORG-015 hard"
                + " invariant)")
        .check(CLASSES);
  }

  static ArchRule mustNotDependOnRule(Class<?> subject, Class<?> forbidden) {
    return noClasses()
        .that(classesIn(Set.of(subject)))
        .should()
        .dependOnClassesThat()
        .belongToAnyOf(forbidden);
  }

  /**
   * {@code OrgRoleManagementSecurityService} must not depend on {@code OwnerScopeService}, so
   * delegated appointment verdicts use only the caller's own membership ranks and the hierarchy
   * (REQ-ROLE-004).
   */
  @Test
  void delegatedRoleAuthoriserMustNotConsultOwnerScope() {
    assertClassFloor(
        "delegatedRoleAuthoriserMustNotConsultOwnerScope",
        classesIn(Set.of(OrgRoleManagementSecurityService.class)),
        1);
    mustNotDependOnRule(OrgRoleManagementSecurityService.class, OwnerScopeService.class)
        .because(
            "the delegated appointment verdict must read only the caller's own membership ranks +"
                + " the persisted hierarchy, never the admin-pin / admin-all / cascading scope that"
                + " OwnerScopeService carries (epic #800, REQ-ROLE-004 no-self-promotion / no-admin"
                + " invariant)")
        .check(CLASSES);
  }

  /**
   * Only {@code OrgUnitBankAccessService} may couple {@code OwnerScopeService} with the
   * bank-account repository (ADR-0020, ADR-0028), keeping the rest of the bank org-unit-blind.
   */
  @Test
  void orgUnitAwareBankSeamIsContainedToOneClass() {
    DescribedPredicate<JavaClass> selection =
        bridgesOrgUnitScopeAndTheBank(OwnerScopeService.class, BANK_DOMAIN);
    assertClassFloor("orgUnitAwareBankSeamIsContainedToOneClass", selection, 1);
    orgUnitAwareBankSeamRule(selection, OrgUnitBankAccessService.class).check(CLASSES);
  }

  /**
   * Classes outside the bank that depend both on the org-unit scope and on a bank class.
   *
   * @param ownerScope the org-unit scope service
   * @param bank the bank domain
   * @return the predicate
   */
  static DescribedPredicate<JavaClass> bridgesOrgUnitScopeAndTheBank(
      Class<?> ownerScope, DescribedPredicate<JavaClass> bank) {
    return DescribedPredicate.describe(
        "depend on both " + ownerScope.getSimpleName() + " and a bank class",
        (JavaClass input) -> {
          boolean dependsOnOwnerScope = false;
          boolean dependsOnTheBank = false;
          for (Dependency dependency : input.getDirectDependenciesFromSelf()) {
            JavaClass target = dependency.getTargetClass();
            dependsOnOwnerScope |= target.isEquivalentTo(ownerScope);
            dependsOnTheBank |= bank.test(target);
          }
          return dependsOnOwnerScope && dependsOnTheBank;
        });
  }

  static ArchRule orgUnitAwareBankSeamRule(
      DescribedPredicate<JavaClass> selection, Class<?> bridge) {
    return classes()
        .that(selection)
        .should()
        .be(bridge)
        .because(
            "officer/lead bank access bridges org-unit oversight and the bank through exactly one"
                + " sanctioned seam outside the bank domain (ADR-0020); BankSecurityService stays"
                + " org-unit-blind (REQ-BANK-008)");
  }

  /**
   * The bank ledger repositories stay insert-only (REQ-BANK-004): they declare no
   * {@code @Modifying} methods, and no production class calls their {@code delete*} methods.
   */
  @Test
  void bankLedgerRepositoriesMustStayInsertOnly() {
    DescribedPredicate<JavaMethod> selection =
        ledgerMethods(LEDGER_REPOSITORIES, APPROVED_LEDGER_MUTATIONS);
    assertMethodFloor("bankLedgerRepositoriesMustStayInsertOnly", selection, 21);
    assertClassFloor(
        "bankLedgerRepositoriesMustStayInsertOnly (callers)",
        DescribedPredicate.alwaysTrue(),
        WHOLE_BACKEND_FLOOR);
    bankLedgerRepositoriesMustStayInsertOnlyRule(selection, LEDGER_REPOSITORIES).check(CLASSES);
  }

  static DescribedPredicate<JavaMethod> ledgerMethods(
      Set<Class<?>> ledgerRepositories, Set<String> approvedMutations) {
    return methodsOf(classesIn(ledgerRepositories))
        .and(
            DescribedPredicate.describe(
                "are not an approved ledger mutation",
                (JavaMethod m) ->
                    !approvedMutations.contains(m.getOwner().getFullName() + "." + m.getName())));
  }

  static ArchRule bankLedgerRepositoriesMustStayInsertOnlyRule(
      DescribedPredicate<JavaMethod> selection, Set<Class<?>> ledgerRepositories) {
    Set<String> names = ledgerRepositories.stream().map(Class::getName).collect(Collectors.toSet());
    return CompositeArchRule.of(
            noMethods()
                .that(selection)
                .should()
                .beAnnotatedWith(Modifying.class)
                .because(
                    "ledger rows are insert-only (ADR-0010) — no UPDATE/DELETE query may exist"))
        .and(
            noClasses()
                .should()
                .callMethodWhere(
                    DescribedPredicate.describe(
                        "a delete method on a bank ledger repository (append-only, ADR-0010)",
                        (JavaMethodCall input) ->
                            names.contains(input.getTargetOwner().getFullName())
                                && input.getTarget().getName().startsWith("delete")))
                .because("corrections are REVERSAL transactions, never deletes (REQ-BANK-004)"));
  }

  /**
   * A layer: classes of a role, the classes nested in them, and the package tree of a legacy anchor
   * class, so today's package is still covered and a role class stays covered wherever it moves.
   *
   * @param name the layer's name
   * @param role the role predicate
   * @param legacyAnchor a class of the layer's legacy package
   * @return the layer predicate
   */
  static DescribedPredicate<JavaClass> layer(
      String name, DescribedPredicate<JavaClass> role, Class<?> legacyAnchor) {
    String anchorPackage = legacyAnchor.getPackageName();
    return DescribedPredicate.describe(
        "are "
            + name
            + " ("
            + role.getDescription()
            + ", the classes nested in them, or the"
            + " package tree of "
            + legacyAnchor.getSimpleName()
            + ")",
        c ->
            role.test(c)
                || role.test(outermost(c))
                || isInPackageTree(c.getPackageName(), anchorPackage));
  }

  /**
   * The classes in the package tree of an anchor class.
   *
   * @param name the description
   * @param anchor the anchor class
   * @return the predicate
   */
  static DescribedPredicate<JavaClass> packageTreeOf(String name, Class<?> anchor) {
    String anchorPackage = anchor.getPackageName();
    return DescribedPredicate.describe(
        "are " + name + " (the package tree of " + anchor.getSimpleName() + ")",
        c -> isInPackageTree(c.getPackageName(), anchorPackage));
  }

  /**
   * Classes whose package has a segment of the given domain name.
   *
   * @param domain the package segment
   * @return the predicate
   */
  static DescribedPredicate<JavaClass> inDomain(String domain) {
    return DescribedPredicate.describe(
        "are in the " + domain + " domain (a package segment named " + domain + ")",
        c -> Arrays.asList(c.getPackageName().split("\\.")).contains(domain));
  }

  /**
   * The given classes and the classes nested in them.
   *
   * @param name the description
   * @param types the classes
   * @return the predicate
   */
  static DescribedPredicate<JavaClass> nestedInAnyOf(String name, Set<Class<?>> types) {
    Set<String> names = types.stream().map(Class::getName).collect(Collectors.toSet());
    return DescribedPredicate.describe(
        "are " + name + " or nested in them", c -> names.contains(outermost(c).getName()));
  }

  /**
   * The MapStruct-generated implementations of the given mapper types.
   *
   * @param types the classes whose {@code @Mapper} members count
   * @return the predicate
   */
  static DescribedPredicate<JavaClass> generatedImplementationsOf(Set<Class<?>> types) {
    Set<String> names = types.stream().map(Class::getName).collect(Collectors.toSet());
    return DescribedPredicate.describe(
        "are generated implementations of the registered mappers",
        c ->
            Stream.concat(c.getAllRawInterfaces().stream(), c.getAllRawSuperclasses().stream())
                .anyMatch(s -> names.contains(s.getName()) && s.isAnnotatedWith(Mapper.class)));
  }

  /**
   * Exactly the given classes, named by class literal.
   *
   * @param types the classes
   * @return the predicate
   */
  static DescribedPredicate<JavaClass> classesIn(Set<Class<?>> types) {
    Set<String> names = types.stream().map(Class::getName).collect(Collectors.toSet());
    return DescribedPredicate.describe(
        "are one of " + types.stream().map(Class::getSimpleName).sorted().toList(),
        c -> names.contains(c.getName()));
  }

  static DescribedPredicate<JavaMethod> methodsOf(DescribedPredicate<? super JavaClass> owners) {
    return DescribedPredicate.describe(
        "are declared in classes that " + owners.getDescription(), m -> owners.test(m.getOwner()));
  }

  static DescribedPredicate<JavaMethod> publicMethodsOf(
      DescribedPredicate<? super JavaClass> owners) {
    return methodsOf(owners)
        .and(
            DescribedPredicate.describe(
                "are public", (JavaMethod m) -> m.getModifiers().contains(JavaModifier.PUBLIC)));
  }

  @SafeVarargs
  static DescribedPredicate<JavaMethod> annotatedWithAnyOf(
      Class<? extends java.lang.annotation.Annotation>... annotations) {
    return DescribedPredicate.describe(
        "are annotated with any of "
            + Arrays.stream(annotations).map(Class::getSimpleName).toList(),
        m -> Arrays.stream(annotations).anyMatch(m::isAnnotatedWith));
  }

  static JavaClass outermost(JavaClass javaClass) {
    JavaClass current = javaClass;
    while (current.getEnclosingClass().isPresent()) {
      current = current.getEnclosingClass().get();
    }
    return current;
  }

  /**
   * The classes in the {@code api.events} package tree of any module directly below a root package.
   *
   * @param name the description
   * @param rootPackage the root package whose top-level packages are the modules
   * @return the predicate
   */
  static DescribedPredicate<JavaClass> moduleApiEventsCode(String name, String rootPackage) {
    return DescribedPredicate.describe(
        "are " + name + " (the api.events package tree of every module below " + rootPackage + ")",
        c -> {
          String pkg = c.getPackageName();
          if (!pkg.startsWith(rootPackage + ".")) {
            return false;
          }
          String[] segments = pkg.substring(rootPackage.length() + 1).split("\\.");
          return segments.length >= 3 && segments[1].equals("api") && segments[2].equals("events");
        });
  }

  static boolean isInPackageTree(String packageName, String root) {
    return packageName.equals(root) || packageName.startsWith(root + ".");
  }

  /**
   * The Spring bean name of a class: its explicit {@code @Service}/{@code @Component} value, else
   * the decapitalised simple name.
   *
   * @param type the bean class
   * @return the bean name a SpEL expression refers to it by
   */
  static String beanName(Class<?> type) {
    Service service = type.getAnnotation(Service.class);
    if (service != null && !service.value().isEmpty()) {
      return service.value();
    }
    Component component = type.getAnnotation(Component.class);
    if (component != null && !component.value().isEmpty()) {
      return component.value();
    }
    String simple = type.getSimpleName();
    return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
  }

  /**
   * Resolves a public method by class literal and name, failing loudly when it is gone.
   *
   * @param owner the declaring class
   * @param name the method name
   * @param parameterTypes the parameter types
   * @return the method
   */
  static Method method(Class<?> owner, String name, Class<?>... parameterTypes) {
    try {
      return owner.getMethod(name, parameterTypes);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(
          "architecture rule key " + owner.getName() + "." + name + " no longer resolves", e);
    }
  }

  /**
   * Returns a method name after checking the class declares a method of that name, so a renamed
   * method fails the build instead of silently disarming a rule.
   *
   * @param owner the class
   * @param name the method name
   * @return {@code name}
   */
  static String methodName(Class<?> owner, String name) {
    boolean present =
        Stream.concat(Arrays.stream(owner.getMethods()), Arrays.stream(owner.getDeclaredMethods()))
            .anyMatch(m -> m.getName().equals(name));
    if (!present) {
      throw new IllegalStateException(
          "architecture rule key " + owner.getName() + "." + name + " no longer resolves");
    }
    return name;
  }

  static long countClasses(JavaClasses classes, DescribedPredicate<? super JavaClass> selection) {
    return classes.stream().filter(JavaClass::isTopLevelClass).filter(selection).count();
  }

  static long countMethods(JavaClasses classes, DescribedPredicate<? super JavaMethod> selection) {
    return classes.stream().flatMap(c -> c.getMethods().stream()).filter(selection).count();
  }

  static long countFields(JavaClasses classes, DescribedPredicate<? super JavaField> selection) {
    return classes.stream().flatMap(c -> c.getFields().stream()).filter(selection).count();
  }

  static void assertClassFloor(
      String rule, DescribedPredicate<? super JavaClass> selection, long floor) {
    assertFloor(rule, countClasses(CLASSES, selection), floor);
  }

  static void assertMethodFloor(
      String rule, DescribedPredicate<? super JavaMethod> selection, long floor) {
    assertFloor(rule, countMethods(CLASSES, selection), floor);
  }

  static void assertFieldFloor(
      String rule, DescribedPredicate<? super JavaField> selection, long floor) {
    assertFloor(rule, countFields(CLASSES, selection), floor);
  }

  /**
   * Asserts that a rule still selects at least as many elements as it did when its floor was set.
   *
   * @param rule the rule name
   * @param selected the number of elements the rule selects now
   * @param floor the minimum
   */
  static void assertFloor(String rule, long selected, long floor) {
    assertThat(selected)
        .as(
            "selection floor of %s: it selects %d elements, fewer than the %d it was set to. A"
                + " smaller selection means the rule silently checks less (a moved, renamed or"
                + " deleted class); if the shrink is deliberate, lower the floor in the same"
                + " change.",
            rule, selected, floor)
        .isGreaterThanOrEqualTo(floor);
  }

  static String preAuthorizeValue(HasAnnotations<?> element) {
    if (!element.isAnnotatedWith(PreAuthorize.class)) {
      return "";
    }
    return element
        .getAnnotationOfType(PreAuthorize.class.getName())
        .tryGetExplicitlyDeclaredProperty("value")
        .map(Object::toString)
        .orElse("");
  }

  static List<String> permitAllDeclarations(JavaClass clazz) {
    List<String> declarations = new ArrayList<>();
    if (preAuthorizeValue(clazz).contains("permitAll")) {
      declarations.add(clazz.getFullName());
    }
    for (JavaMethod method : clazz.getMethods()) {
      if (preAuthorizeValue(method).contains("permitAll")) {
        declarations.add(method.getFullName());
      }
    }
    return declarations;
  }

  static boolean matches(JavaMethod javaMethod, Method method) {
    return javaMethod.getOwner().isEquivalentTo(method.getDeclaringClass())
        && javaMethod.getName().equals(method.getName())
        && javaMethod.getRawParameterTypes().stream()
            .map(JavaClass::getName)
            .toList()
            .equals(Arrays.stream(method.getParameterTypes()).map(Class::getName).toList());
  }

  static ArchCondition<JavaClass> declarePermitAllOnlyOnTheAllowList(
      Set<Method> allowedMethods, Set<Class<?>> allowedControllers) {
    return new ArchCondition<>("declare permitAll() only on the allow-listed handlers") {
      @Override
      public void check(JavaClass clazz, ConditionEvents events) {
        boolean allowedController = allowedControllers.stream().anyMatch(clazz::isEquivalentTo);
        if (preAuthorizeValue(clazz).contains("permitAll") && !allowedController) {
          events.add(
              SimpleConditionEvent.violated(
                  clazz, clazz.getFullName() + " declares a class-level permitAll()"));
        }
        for (JavaMethod method : clazz.getMethods()) {
          if (!preAuthorizeValue(method).contains("permitAll") || allowedController) {
            continue;
          }
          if (allowedMethods.stream().noneMatch(m -> matches(method, m))) {
            events.add(
                SimpleConditionEvent.violated(
                    method, method.getFullName() + " declares permitAll() off the allow-list"));
          }
        }
      }
    };
  }

  /**
   * The condition behind {@link #toOneAssociationsAreDeclaredLazy()}: the field's {@code ManyToOne}
   * or {@code OneToOne} annotation states {@code fetch = FetchType.LAZY}.
   *
   * @return the condition
   */
  static ArchCondition<JavaField> declareFetchTypeLazy() {
    return new ArchCondition<>("declare fetch = FetchType.LAZY") {
      @Override
      public void check(JavaField field, ConditionEvents events) {
        FetchType fetch =
            field.isAnnotatedWith(ManyToOne.class)
                ? field.getAnnotationOfType(ManyToOne.class).fetch()
                : field.getAnnotationOfType(OneToOne.class).fetch();
        if (fetch != FetchType.LAZY) {
          events.add(
              SimpleConditionEvent.violated(
                  field, field.getFullName() + " is fetched " + fetch + ", not LAZY"));
        }
      }
    };
  }

  static ArchCondition<JavaClass> haveAtLeastOnePreAuthorizeAnnotation() {
    return new ArchCondition<>("declare @PreAuthorize on the class or on at least one method") {
      @Override
      public void check(JavaClass clazz, ConditionEvents events) {
        if (clazz.isAnnotatedWith(PreAuthorize.class)
            || clazz.getMethods().stream().anyMatch(m -> m.isAnnotatedWith(PreAuthorize.class))) {
          return;
        }
        events.add(
            SimpleConditionEvent.violated(
                clazz,
                clazz.getFullName()
                    + " is a web controller but declares no @PreAuthorize "
                    + "annotation on the class or on any of its methods"));
      }
    };
  }

  static ArchCondition<JavaMethod> notReturnAnEntityInsideAGenericWrapper() {
    Set<String> wrappers =
        ENTITY_GENERIC_WRAPPERS.stream().map(Class::getName).collect(Collectors.toSet());
    return new ArchCondition<>("not return a JPA entity inside a known generic wrapper") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        JavaType returnType = method.getReturnType();
        if (!(returnType instanceof JavaParameterizedType parameterized)) {
          return;
        }
        JavaClass rawType = parameterized.toErasure();
        if (!wrappers.contains(rawType.getFullName())) {
          return;
        }
        for (JavaType arg : parameterized.getActualTypeArguments()) {
          JavaClass argClass = arg.toErasure();
          if (argClass.isAnnotatedWith(Entity.class)) {
            events.add(
                SimpleConditionEvent.violated(
                    method,
                    method.getFullName()
                        + " returns "
                        + rawType.getSimpleName()
                        + "<"
                        + argClass.getSimpleName()
                        + "> — JPA entities must not "
                        + "be exposed through generic wrappers; map to a DTO first."));
          }
        }
      }
    };
  }

  static ArchCondition<JavaClass> declareTransactionalForMutatingMethodsWhenClassIsReadOnly() {
    return new ArchCondition<>(
        "declare method-level @Transactional on mutating methods when the class is"
            + " @Transactional(readOnly = true)") {
      @Override
      public void check(JavaClass clazz, ConditionEvents events) {
        if (!isClassReadOnlyTransactional(clazz)) {
          return;
        }
        for (JavaMethod method : clazz.getMethods()) {
          if (!method.getModifiers().contains(JavaModifier.PUBLIC)
              || !hasMutatingNamePrefix(method.getName())
              || method.isAnnotatedWith(Transactional.class)) {
            continue;
          }
          events.add(
              SimpleConditionEvent.violated(
                  method,
                  method.getFullName()
                      + " — declaring class is @Transactional(readOnly = true) "
                      + "but this mutating method has no @Transactional override; writes "
                      + "would happen in a read-only transaction. Annotate the method with "
                      + "@Transactional or rename it to a non-mutating prefix."));
        }
      }
    };
  }

  static ArchCondition<JavaMethod> notBeANoArgFindAll() {
    return new ArchCondition<>("not be a no-arg findAll() (M-9)") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        if ("findAll".equals(method.getName()) && method.getRawParameterTypes().isEmpty()) {
          events.add(
              SimpleConditionEvent.violated(
                  method,
                  method.getFullName()
                      + " — no-arg findAll() in a repository is the M-9 anti-pattern. "
                      + "Switch to findAll(Pageable) or a scoped query method."));
        }
      }
    };
  }

  static ArchCondition<JavaMethod> haveMethodOrClassLevelPreAuthorize() {
    return new ArchCondition<>("declare @PreAuthorize on the method or on the declaring class") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        if (method.isAnnotatedWith(PreAuthorize.class)
            || method.getOwner().isAnnotatedWith(PreAuthorize.class)) {
          return;
        }
        events.add(
            SimpleConditionEvent.violated(
                method,
                method.getFullName()
                    + " — endpoint without @PreAuthorize. "
                    + "Add @PreAuthorize on the method (or class-level) — use "
                    + "@PreAuthorize(\"permitAll()\") if the endpoint is deliberately public."));
      }
    };
  }

  static boolean isClassReadOnlyTransactional(JavaClass clazz) {
    if (!clazz.isAnnotatedWith(Transactional.class)) {
      return false;
    }
    JavaAnnotation<?> annotation = clazz.getAnnotationOfType(Transactional.class.getName());
    return annotation
        .tryGetExplicitlyDeclaredProperty("readOnly")
        .map(Boolean.TRUE::equals)
        .orElse(false);
  }

  static boolean hasMutatingNamePrefix(String methodName) {
    String lower = methodName.toLowerCase(Locale.ROOT);
    return MUTATING_METHOD_PREFIXES.stream().anyMatch(lower::startsWith);
  }

  /**
   * Whether a method name follows the {@code cleanup<EntityName>ForPeer} convention of the
   * participant-PII redaction helpers in {@code MissionPeerRedactor}.
   *
   * @param name candidate method name
   * @return {@code true} iff {@code name} matches the {@code cleanup…ForPeer} convention
   */
  static boolean isGuestRedactionHelperName(String name) {
    return name.startsWith("cleanup") && name.endsWith("ForPeer");
  }

  static DescribedPredicate<JavaMethod> hasPeerReachableGate() {
    return DescribedPredicate.describe(
        "are annotated with @PreAuthorize whose gate admits a member below Logistician",
        (JavaMethod method) -> {
          if (!method.isAnnotatedWith(PreAuthorize.class)) {
            return false;
          }
          String value = preAuthorizeValue(method);
          return !value.contains("hasRole(")
              && !value.contains("hasAnyRole(")
              && !value.contains("hasAuthority(")
              && !value.contains("hasAnyAuthority(");
        });
  }

  /**
   * Returns the PII-carrying DTOs a method's return type exposes, directly or as a type argument of
   * a known generic wrapper.
   *
   * @param method the method whose return type to inspect
   * @param piiDtos the PII-carrying DTO types
   * @return the matching type names; empty when the return type carries no participant PII
   */
  static Set<String> protectedDtosOf(JavaMethod method, Set<Class<?>> piiDtos) {
    Set<String> names = piiDtos.stream().map(Class::getName).collect(Collectors.toSet());
    Set<String> wrappers =
        ENTITY_GENERIC_WRAPPERS.stream().map(Class::getName).collect(Collectors.toSet());
    JavaClass rawReturnType = method.getRawReturnType();
    if (names.contains(rawReturnType.getFullName())) {
      return Set.of(rawReturnType.getFullName());
    }
    if (!(method.getReturnType() instanceof JavaParameterizedType parameterized)
        || !wrappers.contains(parameterized.toErasure().getFullName())) {
      return Set.of();
    }
    Set<String> exposed = new LinkedHashSet<>();
    for (JavaType arg : parameterized.getActualTypeArguments()) {
      String name = arg.toErasure().getFullName();
      if (names.contains(name)) {
        exposed.add(name);
      }
    }
    return exposed;
  }

  /**
   * Condition: the method body (or a method reference from it) invokes a {@code requireCan*}
   * authorization helper.
   *
   * @return the ArchUnit condition
   */
  static ArchCondition<JavaMethod> callARequireCanAuthorizationHelper() {
    return new ArchCondition<>("call a requireCan* authorization helper from its own body") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        boolean guarded =
            method.getMethodCallsFromSelf().stream()
                .map(call -> call.getTarget().getName())
                .anyMatch(name -> name.startsWith("requireCan"));
        if (!guarded) {
          events.add(
              SimpleConditionEvent.violated(
                  method,
                  method.getFullName()
                      + " mutates org-unit bank settings but does not call a requireCan*"
                      + " authorization helper — it would ship reachable by any authenticated"
                      + " member (the controller and proxy only require isAuthenticated())."));
        }
      }
    };
  }

  static ArchCondition<JavaMethod> callOneOfTheGuestRedactionHelpers(Set<Class<?>> piiDtos) {
    return new ArchCondition<>("call a cleanup…ForPeer redaction helper from its own body") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        boolean callsHelper =
            method.getMethodCallsFromSelf().stream()
                .map(call -> call.getTarget().getName())
                .anyMatch(ArchitectureTest::isGuestRedactionHelperName);
        boolean referencesHelper =
            method.getAccessesFromSelf().stream()
                .map(access -> access.getTarget().getName())
                .anyMatch(ArchitectureTest::isGuestRedactionHelperName);
        if (callsHelper || referencesHelper) {
          return;
        }
        Set<String> protectedByHandler = protectedDtosOf(method, piiDtos);
        boolean callsALocalHelperThatRedacts =
            method.getMethodCallsFromSelf().stream()
                .filter(
                    call ->
                        call.getTargetOwner().getFullName().equals(method.getOwner().getFullName()))
                .flatMap(call -> call.getTarget().resolveMember().stream())
                .filter(
                    target ->
                        !Collections.disjoint(protectedDtosOf(target, piiDtos), protectedByHandler))
                .anyMatch(
                    target ->
                        target.getAccessesFromSelf().stream()
                            .map(access -> access.getTarget().getName())
                            .anyMatch(ArchitectureTest::isGuestRedactionHelperName));
        if (callsALocalHelperThatRedacts) {
          return;
        }
        events.add(
            SimpleConditionEvent.violated(
                method,
                method.getFullName()
                    + " — a member below Logistician reaches this endpoint (its @PreAuthorize"
                    + " gate admits one) and the return type carries participant PII, but the"
                    + " method body does not invoke any cleanup…ForPeer redaction helper."
                    + " Participant e-mail addresses and real names will leak to a peer"
                    + " (REQ-SEC-007) — see audit findings C-1 / C-2."));
      }
    };
  }

  static ArchCondition<JavaClass> missionParticipantsFieldHasOptimisticLockExcluded() {
    return new ArchCondition<>("declare participants with @OptimisticLock(excluded = true)") {
      @Override
      public void check(JavaClass clazz, ConditionEvents events) {
        JavaField participantsField =
            clazz.getFields().stream()
                .filter(f -> "participants".equals(f.getName()))
                .findFirst()
                .orElse(null);
        if (participantsField == null) {
          events.add(
              SimpleConditionEvent.violated(
                  clazz,
                  clazz.getFullName()
                      + " — `participants` field is missing entirely; the signup concurrency"
                      + " contract assumes Mission has a participants collection annotated with"
                      + " @OptimisticLock(excluded = true)."));
          return;
        }
        if (!participantsField.isAnnotatedWith(OptimisticLock.class)) {
          events.add(
              SimpleConditionEvent.violated(
                  participantsField,
                  participantsField.getFullName()
                      + " — missing @OptimisticLock annotation; adding a participant would dirty"
                      + " the parent collection and bump Mission.version, breaking concurrent"
                      + " signups."));
          return;
        }
        boolean excluded =
            participantsField
                .getAnnotationOfType(OptimisticLock.class.getName())
                .tryGetExplicitlyDeclaredProperty("excluded")
                .map(Boolean.TRUE::equals)
                .orElse(false);
        if (!excluded) {
          events.add(
              SimpleConditionEvent.violated(
                  participantsField,
                  participantsField.getFullName()
                      + " — @OptimisticLock is present but `excluded` is not explicitly set to"
                      + " true. Concurrent signups will bump Mission.version and trigger 409s."
                      + " Restore `@OptimisticLock(excluded = true)`."));
        }
      }
    };
  }

  static ArchCondition<JavaMethod> notCallSaveOn(Class<?> missionRepository) {
    return new ArchCondition<>(
        "not invoke " + missionRepository.getSimpleName() + "#save* from its body") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        method.getMethodCallsFromSelf().stream()
            .filter(call -> call.getTarget().getOwner().isEquivalentTo(missionRepository))
            .filter(call -> call.getTarget().getName().startsWith("save"))
            .forEach(
                call ->
                    events.add(
                        SimpleConditionEvent.violated(
                            method,
                            method.getFullName()
                                + " calls "
                                + call.getTarget().getOwner().getSimpleName()
                                + "#"
                                + call.getTarget().getName()
                                + " — that dirties the parent Mission row and re-opens"
                                + " optimistic-locking failures on concurrent participant"
                                + " signups. Persist the new participant via"
                                + " missionParticipantRepository.save(participant) instead.")));
      }
    };
  }
}
