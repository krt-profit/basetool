/**
 * Ambient declarations for the page constants that a Thymeleaf bootstrap
 * block injects into the global scope.
 *
 * Every page module is preceded by a small `th:inline="javascript"` block
 * declaring the localized message dictionaries and server-side values that
 * module consumes (the bootstrap-dict handoff of ADR-0069). Those
 * declarations live in `.html` files, which the checker cannot see — this
 * file restates them so the consuming module type-checks.
 *
 * Scope note: these names are declared GLOBALLY here, while at runtime each
 * exists only on the page whose bootstrap declared it. That per-page scoping
 * stays enforced by ESLint's `no-undef` against the `global` comment header
 * each module carries; TypeScript's job here is the TYPE, not the visibility.
 * The two checks are complementary and both must stay in place.
 *
 * Generated once from the checker's own "cannot find name" output plus the
 * matching template declarations, then maintained by hand: when you add a
 * bootstrap constant, add it here and to the module's `global` comment header.
 *
 * See ADR-0125 and REQ-FE-018.
 */

/** Opens the mission finance edit modal; declared inline in mission-detail.html. */
declare function openEditFinanceModal(
    id: string,
    note: string,
    type: string,
    amount: number,
    version: number,
): void;

// --- consumed by: admin-material-aliases.js
/** Injected by the page bootstrap (declared in material-aliases.html). */
declare const ALIAS_CONFLICT: KrtI18nDict;
/** Injected by the page bootstrap (declared in material-aliases.html). */
declare const ALIAS_MSG: KrtI18nDict;

// --- consumed by: admin-materials.js
/** Injected by the page bootstrap (declared in materials.html). */
declare const CAT_CONFLICT: KrtI18nDict;
/** Injected by the page bootstrap (declared in materials.html). */
declare const CAT_MSG: KrtI18nDict;
/** Injected by the page bootstrap (declared in materials.html). */
declare const MSG_CREATE_ERROR: string;
/** Injected by the page bootstrap (declared in materials.html). */
declare const MSG_CREATE_SUCCESS: string;

// --- consumed by: admin-materials.js, orders-detail.js
/** Injected by the page bootstrap (declared in materials.html). */
declare const MSG_UPDATE_ERROR: string;
/** Injected by the page bootstrap (declared in materials.html). */
declare const MSG_UPDATE_SUCCESS: string;

// --- consumed by: admin-settings.js
/** Injected by the page bootstrap (declared in admin-settings.html). */
declare const MSG_DISABLED: string;
/** Injected by the page bootstrap (declared in admin-settings.html). */
declare const MSG_ENABLED: string;
/** Injected by the page bootstrap (declared in admin-settings.html). */
declare const MSG_PROFIT_DISABLED: string;
/** Injected by the page bootstrap (declared in admin-settings.html). */
declare const MSG_PROFIT_ENABLED: string;
/** Injected by the page bootstrap (declared in admin-settings.html). */
declare const MSG_PROFIT_ERROR: string;
/** Injected by the page bootstrap (declared in admin-settings.html). */
declare const MSG_PROFIT_SAVED: string;
/** Injected by the page bootstrap (declared in admin-settings.html). */
declare const SAVE_CONFLICT: KrtI18nDict;
/** Injected by the page bootstrap (declared in admin-settings.html). */
declare const SAVE_ERROR: string;
/** Injected by the page bootstrap (declared in admin-settings.html). */
declare const SAVE_SUCCESS: string;

// --- consumed by: admin-settings.js, promotion-admin-rank-requirements.js, promotion-admin-topics.js, promotion-manage.js, uex.js
/** Injected by the page bootstrap (declared in admin-settings.html). */
declare const MSG_ERROR: string;
/** Injected by the page bootstrap (declared in admin-settings.html). */
declare const MSG_SAVED: string;

// --- consumed by: announcement.js
/** Injected by the page bootstrap (declared in announcement.html). */
declare const ANNOUNCE_CONFLICT: KrtI18nDict;
/** Injected by the page bootstrap (declared in announcement.html). */
declare const ANNOUNCE_MSG: KrtI18nDict;

// --- consumed by: discord-registrations.js
/** Injected by the page bootstrap (declared in discord-registrations.html). */
declare const DISCORD_MSG: KrtI18nDict;

// --- consumed by: hangar.js
/** Injected by the page bootstrap (declared in hangar.html). */
declare const hangarConflict: KrtI18nDict;
/** Injected by the page bootstrap (declared in hangar.html). */
declare const hangarI18n: KrtI18nDict;
// --- consumed by: inventory-admin.js, inventory-my.js
/** Injected by the page bootstrap (declared in inventory-admin.html). */
declare const assocI18n: KrtI18nDict;
/** Injected by the page bootstrap (declared in inventory-admin.html). */
declare const bookOutI18n: KrtI18nDict;
/** Injected by the page bootstrap (declared in inventory-admin.html). */
declare const inventoryConflictI18n: KrtI18nDict;
/** Injected by the page bootstrap (declared in inventory-admin.html). */
declare const stackEntriesI18n: KrtI18nDict;
/** Injected by the page bootstrap (declared in inventory-admin.html). */
declare const umbuchenI18n: KrtI18nDict;

// --- consumed by: inventory-input.js
/** Injected by the page bootstrap (declared in inventory-input.html). */
declare const INV_ADD_MSG: KrtI18nDict;

// --- consumed by: inventory-input.js, orders-create.js, orders-detail.js
/** Injected by the page bootstrap (declared in inventory-input.html). */
declare const MSG_UNIT_PIECE: string;
/** Injected by the page bootstrap (declared in inventory-input.html). */
declare const MSG_UNIT_SCU: string;

// --- consumed by: inventory-my.js
/** Injected by the page bootstrap (declared in inventory-my.html). */
declare const bulkI18n: KrtI18nDict;
/** Injected by the page bootstrap (declared in inventory-my.html). */
declare const bulkRebookI18n: KrtI18nDict;

// --- consumed by: inventory-note-modal.js
/** Injected by the page bootstrap (declared in inventory-admin.html). */
declare const noteI18n: KrtI18nDict;

// --- consumed by: item-collection.js, material-collection.js
/** Injected by the page bootstrap (declared in item-collection.html). */
declare const MSG_DELIVERED_UPDATED: string;
/** Injected by the page bootstrap (declared in item-collection.html). */
declare const MSG_ERROR_GENERIC: string;
/** Injected by the page bootstrap (declared in item-collection.html). */
declare const MSG_LOCATION_UPDATED: string;
/** Injected by the page bootstrap (declared in item-collection.html). */
declare const MSG_OWNER_UPDATED: string;

// --- consumed by: locations.js
/** Injected by the page bootstrap (declared in locations.html). */
declare const LOCATION_CONFLICT: KrtI18nDict;
/** Injected by the page bootstrap (declared in locations.html). */
declare const LOCATION_MSG: KrtI18nDict;
// --- consumed by: mission-data.js
/** Injected by the page bootstrap (declared in mission-data.html). */
declare const MISSION_CONFLICT: KrtI18nDict;
/** Injected by the page bootstrap (declared in mission-data.html). */
declare const MISSION_MSG: KrtI18nDict;
/** Injected by the page bootstrap (declared in mission-data.html). */
declare const MISSION_TITLES: KrtI18nDict;

// --- consumed by: mission-detail.js
/** Injected by the page bootstrap (declared in mission-detail.html). */
declare const MSG_CONFIRM_MANAGER_REMOVE: string;
/** Injected by the page bootstrap (declared in mission-detail.html). */
declare const MSG_CONFIRM_OWNER_CHANGE: string;
/** Injected by the page bootstrap (declared in mission-detail.html). */
declare const MSG_CONFIRM_OWNING_ORG_UNIT_CHANGE: string;
/** Injected by the page bootstrap (declared in mission-detail.html). */
declare const MSG_ERROR_MANAGER_ADD: string;
/** Injected by the page bootstrap (declared in mission-detail.html). */
declare const MSG_ERROR_MANAGER_REMOVE: string;
/** Injected by the page bootstrap (declared in mission-detail.html). */
declare const MSG_ERROR_OWNER_CHANGE: string;
/** Injected by the page bootstrap (declared in mission-detail.html). */
declare const MSG_ERROR_OWNING_ORG_UNIT_CHANGE: string;
/** Injected by the page bootstrap (declared in mission-detail.html). */
declare const MSG_ERROR_PAYOUT_UPDATE: string;
/** Injected by the page bootstrap (declared in mission-detail.html). */
declare const MSG_ERROR_USER_REQUIRED: string;
/** Injected by the page bootstrap (declared in mission-detail.html). */
declare const missionId: string | null;
// --- consumed by: operation-detail.js
/** Injected by the page bootstrap (declared in operation-detail.html). */
declare const MSG_PAYOUT_PAID_ERROR: string;
/** Injected by the page bootstrap (declared in operation-detail.html). */
declare const MSG_PAYOUT_PAID_FORBIDDEN: string;
/** Injected by the page bootstrap (declared in operation-detail.html). */
declare const MSG_PAYOUT_PAID_UNSET_LOCKED: string;
/** Injected by the page bootstrap (declared in operation-detail.html). */
declare const OPS_DETAIL_MSG: KrtI18nDict;
/** Injected by the page bootstrap (declared in operation-detail.html). */
declare const OPS_FINANCE_DETAIL_ERROR: string;

// --- consumed by: operations-index.js
/** Injected by the page bootstrap (declared in operations-index.html). */
declare const OPS_MSG: KrtI18nDict;

// --- consumed by: orders-create.js
/** Injected by the page bootstrap (declared in orders-create.html). */
declare const EDIT_ITEMS: any;
/** Injected by the page bootstrap (declared in orders-create.html). */
declare const ITEM_I18N: KrtI18nDict;
/** Injected by the page bootstrap (declared in orders-create.html). */
declare const MSG_AMOUNT_LABEL: string;
/** Injected by the page bootstrap (declared in orders-create.html). */
declare const MSG_CREATE_FAILED: string;
/** Injected by the page bootstrap (declared in orders-create.html). */
declare const MSG_ITEM_INVALID: string;
/** Injected by the page bootstrap (declared in orders-create.html). */
declare const MSG_MATERIAL_LABEL: string;
/** Injected by the page bootstrap (declared in orders-create.html). */
declare const MSG_MINQUALITY_LABEL: string;
/** Injected by the page bootstrap (declared in orders-create.html). */
declare const MSG_SCMDB_NOT_FOUND: string;
/** Injected by the page bootstrap (declared in orders-create.html). */
declare const MSG_SCMDB_NO_MATCH: string;
/** Injected by the page bootstrap (declared in orders-create.html). */
declare const MSG_SCMDB_SOME_UNKNOWN: string;
/** Injected by the page bootstrap (declared in orders-create.html). */
declare const MSG_SCMDB_SUCCESS: string;
/** Injected by the page bootstrap (declared in orders-create.html). */
declare const MSG_UPDATE_FAILED: string;
/** Injected by the page bootstrap (declared in orders-create.html). */
declare const SCU_HINT_TEXT: string;
/** Injected by the page bootstrap (declared in orders-create.html). */
declare const materialIndex: number;

// --- consumed by: orders-create.js, orders-detail.js
/** Injected by the page bootstrap (declared in orders-create.html). */
declare const MSG_MATERIAL_INVALID: string;

// --- consumed by: orders-detail.js
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const I18N_ADDED: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const I18N_ADD_ERROR: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const I18N_NOTE_CONFLICT: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const I18N_NOTE_DELETED: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const I18N_NOTE_ERROR: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const I18N_NOTE_FOR: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const I18N_NOTE_FORBIDDEN: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const I18N_NOTE_SAVED: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const I18N_REMOVED: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const I18N_REMOVE_ERROR: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const IS_LOGISTICIAN: boolean;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const KRT_ORDER_LIVESYNC_UPDATES: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const KRT_ORDER_SECTION_REFRESH_ERROR: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_BP_COUNTING_ERROR: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_BP_COUNTING_SUCCESS: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_CLAIM_ERROR: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_CLAIM_MAX_HINT: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_CLAIM_SUCCESS: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_CLAIM_TITLE_ADD: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_CLAIM_TITLE_EDIT: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_CLAIM_VALIDATION_AMOUNT: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_CLAIM_VALIDATION_OVERCLAIM: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_CLAIM_VALIDATION_SQUADRON: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_CLAIM_WITHDRAW_SUCCESS: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_DELETE_CANCEL: string;
/** Injected by the page bootstrap (declared in members.html). */
declare const MSG_DELETE_CONFIRM: string;
/** Injected by the page bootstrap (declared in members.html). */
declare const MSG_DELETE_ERROR: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_DELETE_MESSAGE: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_EMPTY_INVENTORY: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_HANDOVER_FAILED: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_HANDOVER_MISSION_HERKUNFT: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_HANDOVER_MISSION_MIN: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_HANDOVER_MISSION_REST: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_HANDOVER_NOITEMS: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_HANDOVER_REPORT_ERROR: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_HANDOVER_REPORT_VALIDATION_AMOUNT: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_HANDOVER_REPORT_VALIDATION_DATE: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_HANDOVER_REPORT_VALIDATION_HANDLE: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_HANDOVER_REPORT_VALIDATION_ITEMS: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_HANDOVER_REPORT_VALIDATION_TIME: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_HANDOVER_SUCCESS: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_INVENTORY_UNLINK_ERROR: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_INVENTORY_UNLINK_SUCCESS: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_INVENTORY_UNLINK_TOOLTIP: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_LOADING_INVENTORY: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_LOCATION: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_OWNER: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_QUALITY: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_QUALITY_GOOD: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_QUALITY_NONE: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_QUANTITY: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_SQUADRON: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_STATUS_ERROR: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const MSG_STATUS_SUCCESS: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const ORDER_AGE_RED: number;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const ORDER_AGE_YELLOW: number;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const ORDER_CONFLICT: KrtI18nDict;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const ORDER_REQUESTING_SQUADRON_ID: string | null;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const PRODUCTION_I18N: KrtI18nDict;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const labelMenge: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const labelPiece: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const labelScu: string;
/** Injected by the page bootstrap (declared in orders-detail.html). */
declare const scuHintText: string;

// --- consumed by: orders-detail.js, promotion-admin-rank-requirements.js
/** Injected by the page bootstrap (declared in members.html). */
declare const MSG_DELETE_TITLE: string;

// --- consumed by: orders-index-reorder.js
/** Injected by the page bootstrap (declared in orders-index.html). */
declare const KRT_ORDERS_REORDER_I18N: KrtI18nDict;

// --- consumed by: orders-index.js
/** Injected by the page bootstrap (declared in orders-index.html). */
declare const KRT_ORDERS_AGE_RED: number;
/** Injected by the page bootstrap (declared in orders-index.html). */
declare const KRT_ORDERS_AGE_YELLOW: number;
/** Injected by the page bootstrap (declared in orders-index.html). */
declare const KRT_ORDERS_LIVESYNC_UPDATES: string;
/** Injected by the page bootstrap (declared in orders-index.html). */
declare const KRT_ORDERS_SECTION_REFRESH_ERROR: string;

// --- consumed by: orders-material-demand.js
/** Injected by the page bootstrap (declared in orders-material-demand.html). */
declare const KRT_DEMAND_LIVESYNC_UPDATES: string;
/** Injected by the page bootstrap (declared in orders-material-demand.html). */
declare const KRT_DEMAND_SECTION_REFRESH_ERROR: string;

// --- consumed by: org-chart.js
/** Injected by the page bootstrap (declared in org-chart.html). */
declare const OC_I18N: KrtI18nDict;

// --- consumed by: promotion-admin-rank-requirements.js
/** Injected by the page bootstrap (declared in promotion-admin-rank-requirements.html). */
declare const AR_CATEGORIES_BY_TOPIC: KrtI18nDict;
/** Injected by the page bootstrap (declared in promotion-admin-rank-requirements.html). */
declare const MSG_DELETE_GROUP_MSG: string;
/** Injected by the page bootstrap (declared in promotion-admin-rank-requirements.html). */
declare const MSG_DELETE_GROUP_TITLE: string;
/** Injected by the page bootstrap (declared in promotion-admin-rank-requirements.html). */
declare const MSG_DELETE_MSG: string;
/** Injected by the page bootstrap (declared in promotion-admin-rank-requirements.html). */
declare const MSG_GROUP_DELETED: string;
/** Injected by the page bootstrap (declared in promotion-admin-rank-requirements.html). */
declare const MSG_INVALID_STEP: string;

// --- consumed by: promotion-admin-rank-requirements.js, promotion-admin-topics.js
/** Injected by the page bootstrap (declared in promotion-admin-rank-requirements.html). */
declare const MSG_CANCEL: string;
/** Injected by the page bootstrap (declared in promotion-admin-rank-requirements.html). */
declare const MSG_DELETED: string;
/** Injected by the page bootstrap (declared in promotion-admin-rank-requirements.html). */
declare const MSG_OK: string;

// --- consumed by: promotion-admin-rank-requirements.js, promotion-admin-topics.js, promotion-manage.js
/** Injected by the page bootstrap (declared in promotion-admin-rank-requirements.html). */
declare const MSG_CONFLICT: string;
/** Injected by the page bootstrap (declared in promotion-admin-rank-requirements.html). */
declare const MSG_REFRESH_FAILED: string;

// --- consumed by: promotion-admin-topics.js
/** Injected by the page bootstrap (declared in promotion-admin-topics.html). */
declare const MSG_DELETE_CATEGORY_MSG: string;
/** Injected by the page bootstrap (declared in promotion-admin-topics.html). */
declare const MSG_DELETE_CATEGORY_TITLE: string;
/** Injected by the page bootstrap (declared in promotion-admin-topics.html). */
declare const MSG_DELETE_TOPIC_MSG: string;
/** Injected by the page bootstrap (declared in promotion-admin-topics.html). */
declare const MSG_DELETE_TOPIC_TITLE: string;
/** Injected by the page bootstrap (declared in promotion-admin-topics.html). */
declare const MSG_DIRTY_LEAVE: string;

// --- consumed by: promotion-manage.js
/** Injected by the page bootstrap (declared in promotion-manage.html). */
declare const MSG_BULK_CONFIRM_MSG: string;
/** Injected by the page bootstrap (declared in promotion-manage.html). */
declare const MSG_BULK_CONFIRM_TITLE: string;
/** Injected by the page bootstrap (declared in promotion-manage.html). */
declare const MSG_BULK_NEED_CAT: string;
/** Injected by the page bootstrap (declared in promotion-manage.html). */
declare const MSG_BULK_NEED_LEVEL: string;
/** Injected by the page bootstrap (declared in promotion-manage.html). */
declare const MSG_CSV_HEADER_ELIG: string;
/** Injected by the page bootstrap (declared in promotion-manage.html). */
declare const MSG_CSV_HEADER_LAST: string;
/** Injected by the page bootstrap (declared in promotion-manage.html). */
declare const MSG_CSV_HEADER_MEMBER: string;
/** Injected by the page bootstrap (declared in promotion-manage.html). */
declare const MSG_CSV_HEADER_RANK: string;
/** Injected by the page bootstrap (declared in promotion-manage.html). */
declare const MSG_CSV_NAME: string;
/** Injected by the page bootstrap (declared in promotion-manage.html). */
declare const MSG_LAST_EVAL: string;
/** Injected by the page bootstrap (declared in promotion-manage.html). */
declare const SORT_LABELS: string[];
/** Injected by the page bootstrap (declared in promotion-manage.html). */
declare const STORAGE_KEY_COLLAPSED: string;
/** Injected by the page bootstrap (declared in promotion-manage.html). */
declare const STORAGE_KEY_FILTERS: string;
/** Injected by the page bootstrap (declared in promotion-manage.html). */
declare const STORAGE_KEY_SORT: string;

// --- consumed by: refinery-orders-create.js
/** Injected by the page bootstrap (declared in refinery-orders-create.html). */
declare const MSG_RFC_CREATE_FAILED: string;
/** Injected by the page bootstrap (declared in refinery-orders-create.html). */
declare const MSG_RFC_IMPORT_FAILED: string;
/** Injected by the page bootstrap (declared in refinery-orders-create.html). */
declare const MSG_RFC_MATERIAL_INVALID: string;
/** Injected by the page bootstrap (declared in refinery-orders-create.html). */
declare const REFINERY_HANDOFF_ID: string | null;

// --- consumed by: refinery-orders-create.js, refinery-orders-details.js
/** Injected by the page bootstrap (declared in refinery-orders-create.html). */
declare const MATERIAL_ENTRY_TITLE_LABEL: string;
/** Injected by the page bootstrap (declared in refinery-orders-create.html). */
declare const MATERIAL_REMOVE_LABEL: string;
/**
 * Injected by the page bootstrap (declared in refinery-orders-create.html).
 * The order's refinery yield map, `materialId -> bonusPercent`; the values are
 * numbers, not strings — the controller renders a `Map<String, Integer>`.
 */
declare const MATERIAL_YIELD_BONUSES: Record<string, number>;
/** Injected by the page bootstrap (declared in refinery-orders-create.html). */
declare const MATERIAL_YIELD_BONUS_HELP: string;
/** Injected by the page bootstrap (declared in refinery-orders-create.html). */
declare const RATING_LEVELS: KrtI18nDict;
/** Injected by the page bootstrap (declared in refinery-orders-create.html). */
declare const SPEED_LEVELS: KrtI18nDict;

// --- consumed by: refinery-orders-details.js
/**
 * In-place save/store success toasts plus the two live-sync strings (the deferred-refresh pill
 * label and the section-refresh error). Injected by the page bootstrap (declared in
 * refinery-orders-details.html).
 */
declare const REFINERY_DETAIL_MSG: KrtI18nDict;
/** Injected by the page bootstrap (declared in refinery-orders-details.html). */
declare const MSG_CANCEL_CONFIRM: string;
/** Injected by the page bootstrap (declared in refinery-orders-details.html). */
declare const MSG_CANCEL_DISMISS: string;
/** Injected by the page bootstrap (declared in refinery-orders-details.html). */
declare const MSG_CANCEL_TITLE: string;
/** Injected by the page bootstrap (declared in refinery-orders-details.html). */
declare const MSG_REFINERY_CANCEL_FAILED: string;
/** Injected by the page bootstrap (declared in refinery-orders-details.html). */
declare const MSG_REFINERY_STORE_FAILED: string;
/** Injected by the page bootstrap (declared in refinery-orders-details.html). */
declare const MSG_REFINERY_UPDATE_FAILED: string;
/** Injected by the page bootstrap (declared in refinery-orders-details.html). */
declare const MSG_SAVING: string;
/** Injected by the page bootstrap (declared in refinery-orders-details.html). */
declare const STORE_INHERITED_ORG_UNIT_ID: string | null;
/** Injected by the page bootstrap (declared in refinery-orders-details.html). */
declare const STORE_ORG_UNIT_PLACEHOLDER: string;

// --- consumed by: ship-data.js
/** Injected by the page bootstrap (declared in ship-data.html). */
declare const shipDataConflict: KrtI18nDict;
/** Injected by the page bootstrap (declared in ship-data.html). */
declare const shipDataI18n: KrtI18nDict;
/** Injected by the page bootstrap (declared in ship-data.html). */
declare const shipDataResetUrl: string;

// --- consumed by: special-command-detail.js
/** Injected by the page bootstrap (declared in special-command-detail.html). */
declare const MEMBER_CONFLICT: KrtI18nDict;
/** Injected by the page bootstrap (declared in special-command-detail.html). */
declare const MEMBER_MSG: KrtI18nDict;

// --- consumed by: special-commands.js
/** Injected by the page bootstrap (declared in special-commands.html). */
declare const SC_CONFLICT: KrtI18nDict;
/** Injected by the page bootstrap (declared in special-commands.html). */
declare const SC_MSG: KrtI18nDict;

// --- consumed by: sync-reports.js
/** Injected by the page bootstrap (declared in sync-reports.html). */
declare const SYNC_MSG: KrtI18nDict;
