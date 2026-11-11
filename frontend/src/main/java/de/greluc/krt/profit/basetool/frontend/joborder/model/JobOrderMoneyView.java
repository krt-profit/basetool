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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.List;
import java.util.UUID;

/**
 * Reconstructed manual money figures for one order.
 *
 * @param target requested net amount
 * @param targetVersion independent target edit revision
 * @param provided total recorded new provision
 * @param holdings currently held purpose-bound amount
 * @param netHandedOver effective net delivered to the requester
 * @param remaining net amount still due
 * @param holders current order-local holders and their booked amounts
 * @param history durable accepted provisions, projected with permitted labels
 * @param canProvideSelf whether the caller can report a new own provision
 * @param canProvideOnBehalf whether the caller is also the current responsible writer
 * @param canDelete whether an administrator can delete before any accepted submission
 * @param fees exact fees charged to the order
 * @param overpaid net amount delivered above the target
 * @param recipients current and historical recipient designations
 * @param canDesignateRecipient whether the caller may edit the current designations
 * @param canEditTarget whether the caller may edit the requested net amount
 * @param canEditInformation whether the caller may edit general order information
 * @param informationVersion the contextual order information revision
 * @param canMoveForOthers whether the caller may record arbitrary order movements
 * @param canRecordRequesterHandover whether the caller may record delivery to the requester
 * @param targetChanges explicit amendments to the requested net amount
 * @param purposeInflow funds reassigned from other orders
 * @param purposeOutflow funds reassigned to other orders
 * @param released funds released from the order purpose
 * @param shortages actual later losses
 * @param settled principal paid to a third-party beneficiary
 * @param settlementOpen whether a terminal order retains purpose-bound funds or an overpayment
 * @param responsibilityValid whether the current responsibility permits money actions
 * @param canMaintainHolders whether the caller can maintain identities and coordinate settlement
 * @param canRecordRequesterReturn whether the caller represents the requesting unit
 * @param holderIdentityHistory changes to holder identity and current account links
 * @param canRecordSettlement whether the caller currently owns a permitted settlement action
 * @param canCorrectRecipient whether the caller can correct a false recipient designation
 * @param returnableRecipientIds designations with a positive retained net receipt
 */
@DtoMirror
public record JobOrderMoneyView(
    String target,
    long targetVersion,
    String provided,
    String holdings,
    String netHandedOver,
    String remaining,
    List<JobOrderMoneyHolderView> holders,
    List<JobOrderMoneyOutcome> history,
    boolean canProvideSelf,
    boolean canProvideOnBehalf,
    boolean canDelete,
    String fees,
    String overpaid,
    List<JobOrderMoneyRecipientView> recipients,
    boolean canDesignateRecipient,
    boolean canEditTarget,
    boolean canEditInformation,
    long informationVersion,
    boolean canMoveForOthers,
    boolean canRecordRequesterHandover,
    List<JobOrderMoneyTargetChangeView> targetChanges,
    String purposeInflow,
    String purposeOutflow,
    String released,
    String shortages,
    String settled,
    boolean settlementOpen,
    boolean responsibilityValid,
    boolean canMaintainHolders,
    boolean canRecordRequesterReturn,
    List<JobOrderMoneyHolderChangeView> holderIdentityHistory,
    boolean canRecordSettlement,
    boolean canCorrectRecipient,
    List<UUID> returnableRecipientIds) {}
