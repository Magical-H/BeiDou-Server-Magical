package org.gms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.message.MapMessage;
import org.gms.client.Character;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.dao.entity.ItemTraceLogsDO;
import org.gms.dao.mapper.ItemRecoveryLogsMapper;
import org.gms.dao.mapper.ItemTraceLogsMapper;
import org.gms.model.dto.TraceabilityQueryDTO;
import org.gms.model.pojo.TraceabilityRules;
import org.gms.server.ItemInformationProvider;
import org.gms.server.logging.AuditContext;
import org.gms.server.logging.AuditLogger;
import org.gms.server.logging.LogModule;
import org.gms.server.maps.MapFactory;
import org.gms.util.I18nUtil;
import org.gms.util.RequireUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.NoSuchMessageException;
import org.springframework.stereotype.Service;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.gms.dao.entity.table.ItemTraceLogsDOTableDef.ITEM_TRACE_LOGS_DO;

/**
 * 物品溯源服务 (V3.4 - 精细化日志字段).
 * <p>
 * 负责记录物品的全生命周期流转日志。
 * 本版本对日志字段进行了精细化拆分，明确了 action_source, target_info, memo 的职责。
 * </p>
 */
@Service
@Slf4j
public class TraceabilityService {

    private static final ExecutorService logExecutor = Executors.newSingleThreadExecutor();

    private final ItemTraceLogsMapper itemTraceLogsMapper;
    private final ItemRecoveryLogsMapper itemRecoveryLogsMapper;
    private final ObjectMapper objectMapper;
    private final TraceabilityConfigService configService;
    @Lazy
    private final CharacterService characterService; // 注入CharacterService

    @Autowired
    public TraceabilityService(
            ItemTraceLogsMapper itemTraceLogsMapper,
            ItemRecoveryLogsMapper itemRecoveryLogsMapper,
            @Qualifier("sparseItemObjectMapper") ObjectMapper objectMapper,
            TraceabilityConfigService configService,
            @Lazy CharacterService characterService,
            ItemInformationService itemInformationService) {
        this.itemTraceLogsMapper = itemTraceLogsMapper;
        this.itemRecoveryLogsMapper = itemRecoveryLogsMapper;
        this.objectMapper = objectMapper;
        this.configService = configService;
        this.characterService = characterService;
    }

    public Page<ItemTraceLogsDO> queryLogs(TraceabilityQueryDTO queryDTO) {
        Long uidForQuery = null;
        if (queryDTO.getUid() != null && !queryDTO.getUid().isEmpty()) {
            try {
                uidForQuery = Long.parseLong(queryDTO.getUid());
            } catch (NumberFormatException e) {
                log.warn("从前端接收到的UID格式不正确: {}", queryDTO.getUid());
                return new Page<>();
            }
        }

        QueryWrapper queryWrapper = QueryWrapper.create()
                .where(ITEM_TRACE_LOGS_DO.UID.eq(uidForQuery, uidForQuery != null))
                .and(ITEM_TRACE_LOGS_DO.ITEM_ID.eq(queryDTO.getItemId(), queryDTO.getItemId() != null))
                .and(ITEM_TRACE_LOGS_DO.CHARACTER_ID.eq(queryDTO.getCharacterId(), queryDTO.getCharacterId() != null))
                .and(ITEM_TRACE_LOGS_DO.ACTION_TYPE.like(queryDTO.getActionType(), RequireUtil.isNotEmpty(queryDTO.getActionType())))
                .and(ITEM_TRACE_LOGS_DO.ACTION_SOURCE.like(queryDTO.getActionSource(), RequireUtil.isNotEmpty(queryDTO.getActionSource())))
                .and(ITEM_TRACE_LOGS_DO.TIMESTAMP.ge(queryDTO.getStartTime(), queryDTO.getStartTime() != null))
                .and(ITEM_TRACE_LOGS_DO.TIMESTAMP.le(queryDTO.getEndTime(), queryDTO.getEndTime() != null))
                .orderBy(ITEM_TRACE_LOGS_DO.TIMESTAMP.desc());

        Page<ItemTraceLogsDO> page = itemTraceLogsMapper.paginate(queryDTO.getPageNumber(), queryDTO.getPageSize(), queryWrapper);

        if (page.getRecords().isEmpty()) {
            return page;
        }

        Set<Integer> characterIds = page.getRecords().stream()
                .map(ItemTraceLogsDO::getCharacterId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Integer, String> characterNames = characterService.getChrNamesByIds(characterIds);

        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (ItemTraceLogsDO record : page.getRecords()) {
            record.setItemName(ii.getName(record.getItemId()));
            if (record.getMapId() != null) {
                record.setMapName(MapFactory.loadPlaceName(record.getMapId()));
            }
            if (record.getCharacterId() != null) {
                record.setCharacterName(characterNames.getOrDefault(record.getCharacterId(), "未知角色"));
            }
        }

        return page;
    }

    public Map<String, Object> getTraceabilityStats() {
        Map<String, Object> stats = new HashMap<>();
        long totalRecords = itemTraceLogsMapper.selectCountByQuery(new QueryWrapper());
        long todayAdded = itemTraceLogsMapper.countToday();
        stats.put("totalRecords", totalRecords);
        stats.put("todayAdded", todayAdded);
        stats.put("avgPerHour", todayAdded > 0 ? todayAdded / 24.0 : 0);
        stats.put("dbTableSizeMB", 0.0);

        long twentyFourHoursAgo = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
        stats.put("hourlyCounts", itemTraceLogsMapper.countHourlyLast24h(twentyFourHoursAgo));
        stats.put("actionTypeCounts", itemTraceLogsMapper.countByActionType());
        stats.put("topItems", itemTraceLogsMapper.findTopItems());

        return stats;
    }

    /**
     * 行为类型 - 定义物品操作的领域或模块。
     * 命名规范: ENGLISH_UPPERCASE
     */
    public enum ActionType {
        INVENTORY, // 背包操作
        STORAGE, // 仓库操作
        CASH_SHOP, // 商城操作
        NPC_SHOP, // NPC商店操作
        PLAYER_SHOP, // 玩家商店操作
        MERCHANT_SHOP, // 雇佣商店
        TRADE, // 玩家交易
        DUEY, // 快递系统
        ITEM_USAGE, // 物品使用
        EQUIPMENT, // 装备操作
        SYSTEM, // 系统行为
        SCRIPT, // NPC脚本
        SCRIPT_ITEM, //物品脚本
        SCRIPT_QUEST, // 任务脚本
        SCRIPT_PORTAL, // 传送门脚本
        SCRIPT_EVENT, // 事件脚本
        SCRIPT_REACTOR, // 反应堆脚本
        RECOVER, // 物品找回
        GM, // GM操作
        ADMIN; // 管理员操作
        public String getI18nKey() { return "traceability.actionType." + this.name(); }
        public String getI18nVal() { return I18nUtil.getLogMessage(getI18nKey()); }
    }

    /**
     * 行为来源类型 - 定义了行为发生的具体场景和参与者，并包含格式化字符串。
     * 命名规范: ENGLISH_UPPERCASE
     */
    public enum ActionSourceType {
        // 规则: 自身行为不记录自己; 交互行为记录对方; 地图信息由独立字段提供，此处不记录。

        // INVENTORY
        PLAYER_DROP,
        PLAYER_PICKUP,
        INVENTORY_MERGE,

        // STORAGE
        STORAGE_PUT_IN,
        STORAGE_TAKE_OUT,

        // CASH_SHOP
        CS_BUY,
        CS_PUT_IN,
        CS_TAKE_OUT,

        // NPC_SHOP
        NPC_SHOP_BUY, // arg1: NPC ID, arg2: NPC名
        NPC_SHOP_SELL, // arg1: NPC ID, arg2: NPC名

        // PLAYER_SHOP
        MERCHANT_ADD, // arg1: 商店ID/所有者ID, arg2: 商店名/所有者名
        MERCHANT_BUY, // arg1: 商店ID/所有者ID, arg2: 商店名/所有者名
        MERCHANT_SELL, // arg1: 商店ID/所有者ID, arg2: 商店名/所有者名
        MERCHANT_RETURN, // arg1: 商店ID/所有者ID, arg2: 商店名/所有者名

        // TRADE
        TRADE_SENDER, // arg1: 对方角色ID, arg2: 对方角色名
        TRADE_RECEIVER, // arg1: 对方角色ID, arg2: 对方角色名
        // DUEY
        DUEY_SEND, // arg1: 对方角色ID, arg2: 对方角色名
        DUEY_RECEIVE, // arg1: 对方角色ID, arg2: 对方角色名
        DUEY_RETURN, // arg1: 对方角色ID, arg2: 对方角色名
        DUEY_DELETE,

        // ITEM_USAGE
        ITEM_USE, // arg1: 物品ID, arg2: 物品名, arg3: 数量
        ITEM_CONSUME, // arg1: 物品ID, arg2: 物品名, arg3: 数量
        ITEM_CRAFT, // arg1: 物品ID, arg2: 物品名, arg3: 数量

        // EQUIPMENT
        EQUIP_WEAR,
        EQUIP_UNEQUIP,
        EQUIP_SCROLL, // arg1: 卷轴ID, arg2: 卷轴名, arg3: 数量
        EQUIP_UPGRADE,
        EQUIP_POTENTIAL,

        // SYSTEM
        SYSTEM_MONSTER_DROP, // arg1: 怪物ID, arg2: 怪物名
        SYSTEM_QUEST_REWARD, // arg1: 任务ID, arg2: 任务名
        SYSTEM_QUEST_CONSUME, // arg1: 任务ID, arg2: 任务名
        SYSTEM_GACHAPON_REWARD,
        SYSTEM_REACTOR_DROP, // arg1: 反应堆ID, arg2: 反应堆名
        SYSTEM_MAP_SPAWN,
        SYSTEM_EXPIRED_DESPAWN,
        SYSTEM_DELETE, // 如重复UID、异常物品

        // SCRIPT (脚本行为)
        /**
         * 脚本 - 给予物品
         * arg1: 脚本名称/ID
         */
        SCRIPT_GAIN_ITEM,
        /**
         * 脚本 - 移除物品
         * arg1: 脚本名称/ID
         */
        SCRIPT_REMOVE_ITEM,

        // RECOVER
        RECOVER_COMPLETE, // 物品找回

        // ADMIN
        ADMIN_CREATE, // arg1: GM角色名, arg2: 命令名称
        ADMIN_MODIFY, // arg1: GM角色名, arg2: 命令名称
        ADMIN_DELETE, // arg1: GM角色名, arg2: 命令名称

        OTHER; // 未分类或通用来源

        public String format(Object... args) {
            try {
                String key = getI18nKey();
                if (args != null && args.length > 0) {
                    key += ".format";
                    return I18nUtil.getLogMessage(key, args);
                }
            } catch (Exception ignored) {}
            return args != null ? Arrays.stream(args).filter(Objects::nonNull).map(String::valueOf).collect(Collectors.joining()) : "";
        }
        public String getI18nKey() { return "traceability.actionSource." + this.name(); }
        public String getI18nVal() { return I18nUtil.getLogMessage(getI18nKey()); }
    }

    /**
     * [V3.4] 记录物品流转日志的核心方法。
     *
     * @param item 涉及的物品对象
     * @param character 操作的角色
     * @param domainType 行为领域类型 (ActionType)
     * @param specificActionType 具体行为来源类型 (ActionSourceType)
     * @param quantityChange 数量变化 (正数增加，负数减少，0表示状态变更)
     * @param targetInfo 交互对象信息 (如交易对手、NPC、商店等)
     * @param memo 备注信息 (如操作结果、异常等)
     * @param sourceArgs 用于格式化 targetInfo 的参数
     */
    public void log(Item item, Character character, ActionType domainType, ActionSourceType specificActionType, int quantityChange, String targetInfo, String memo, Object... sourceArgs) {
        if (character == null) {
            log(item, 0, 0, 0, domainType, specificActionType, quantityChange, targetInfo, memo, sourceArgs);
            return;
        }
        log(item, character.getAccountId(), character.getId(), character.getMapId(), domainType, specificActionType, quantityChange, targetInfo, memo, sourceArgs);
    }

    /**
     * [V3.4] 记录物品流转日志的核心方法 (通过ID)。
     *
     * @param item 涉及的物品对象
     * @param accountId 账号ID
     * @param characterId 角色ID
     * @param mapId 地图ID
     * @param domainType 行为领域类型 (ActionType)
     * @param specificActionType 具体行为来源类型 (ActionSourceType)
     * @param quantityChange 数量变化 (正数增加，负数减少，0表示状态变更)
     * @param targetInfo 交互对象信息 (如交易对手、NPC、商店等)
     * @param memo 备注信息 (如操作结果、异常等)
     * @param sourceArgs 用于格式化 targetInfo 的参数
     */
    public void log(Item item, int accountId, int characterId, int mapId, ActionType domainType, ActionSourceType specificActionType, int quantityChange, String targetInfo, String memo, Object... sourceArgs) {
        String dbActionSource = specificActionType.name();
        String formattedSource = specificActionType.format(sourceArgs);
        String lokiMessage = specificActionType.getI18nVal();
        if (item != null) {
            if (targetInfo != null && !targetInfo.isEmpty()) lokiMessage = String.format("交互: %s ; \r\n %s", targetInfo, lokiMessage);
            if (item.getInventoryType() == InventoryType.EQUIP) {
                lokiMessage += String.format(" : [%d] %s", item.getItemId(), ItemInformationProvider.getInstance().getName(item.getItemId()));
            } else {
                lokiMessage += String.format(" : [%d] %s × %d", item.getItemId(), ItemInformationProvider.getInstance().getName(item.getItemId()), quantityChange <= 0 ? quantityChange / -1 : quantityChange);
            }
        }
        if (formattedSource != null && !formattedSource.isEmpty()) lokiMessage += " : " + formattedSource;

        String dbTargetInfo = (domainType == ActionType.ITEM_USAGE) ? null : formattedSource;
        if (targetInfo != null) {
            if (dbTargetInfo != null && !dbTargetInfo.isEmpty()) dbTargetInfo += " ; ";
            dbTargetInfo += targetInfo;
        }
        if (dbTargetInfo != null && dbTargetInfo.isEmpty()) {
            dbTargetInfo = null;
        }
        if (memo != null && memo.isEmpty()) {
            memo = null;
        }
        // 调试语句
        //System.out.println("log: " + item + ", " + accountId + ", " + characterId + ", " + mapId + ", " + domainType + ", " + dbActionSource + ", " + quantityChange + " , " + dbTargetInfo + ", " + memo + ", " + lokiMessage);
        logInternal(item, accountId, characterId, mapId, domainType, specificActionType, dbActionSource, quantityChange, dbTargetInfo, memo, lokiMessage);
    }

    /**
     * [V3.4] 便捷方法: 记录物品流转日志 (无 targetInfo 和 memo)。
     */
    public void log(Item item, Character character, ActionType domainType, ActionSourceType specificActionType, int quantityChange, Object... sourceArgs) {
        log(item, character, domainType, specificActionType, quantityChange, null, null, sourceArgs);
    }

    /**
     * [V3.4] 便捷方法: 记录物品流转日志 (通过ID, 无 targetInfo 和 memo)。
     */
    public void log(Item item, int accountId, int characterId, int mapId, ActionType domainType, ActionSourceType specificActionType, int quantityChange, Object... sourceArgs) {
        log(item, accountId, characterId, mapId, domainType, specificActionType, quantityChange, null, null, sourceArgs);
    }

    private void logInternal(Item item, int accountId, int characterId, int mapId, ActionType actionType,ActionSourceType actionSourceType, String dbActionSource, int quantityChange, String targetInfo, String memo, String lokiMsg) {
        if (item == null) return;

        TraceabilityRules config = configService.getTraceabilityConfig();
        TraceabilityRules.Enabled enabled = config.getEnabled();

        if (enabled == null || (!enabled.isDatabase() && !enabled.isLoki())) {
            return;
        }

        TraceabilityRules.LogActionSwitches logActionSwitches = config.getLogActionSwitches();
        if (logActionSwitches != null) {
            boolean isActionEnabled = true;
            switch (actionType) {
                case TRADE: isActionEnabled = logActionSwitches.isTRADE(); break;
                case INVENTORY: if (dbActionSource.equals(ActionSourceType.PLAYER_DROP.name())) isActionEnabled = logActionSwitches.isDROP(); break;
                case NPC_SHOP: if (dbActionSource.equals(ActionSourceType.NPC_SHOP_SELL.name())) isActionEnabled = logActionSwitches.isSELL(); break;
                case STORAGE:
                    if (dbActionSource.equals(ActionSourceType.STORAGE_PUT_IN.name())) isActionEnabled = logActionSwitches.isSTORAGE_IN();
                    else if (dbActionSource.equals(ActionSourceType.STORAGE_TAKE_OUT.name())) isActionEnabled = logActionSwitches.isSTORAGE_OUT();
                    break;
                case ADMIN:
                case GM:
                    if (dbActionSource.equals(ActionSourceType.ADMIN_CREATE.name())) isActionEnabled = logActionSwitches.isGM_CREATE();
                    break;
                default: break;
            }
            if (!isActionEnabled) return;
        }

        TraceabilityRules.TemporaryDisables temporaryDisables = config.getTemporaryDisables();
        if (temporaryDisables != null) {
            TraceabilityRules.DisableDetail disableRule = null;
            if (actionType == ActionType.SYSTEM && dbActionSource.equals(ActionSourceType.SYSTEM_MONSTER_DROP.name())) {
                disableRule = temporaryDisables.getLOOT();
            } else if (actionType == ActionType.NPC_SHOP && dbActionSource.equals(ActionSourceType.NPC_SHOP_BUY.name())) {
                disableRule = temporaryDisables.getSHOP_BUY();
            }

            if (disableRule != null && disableRule.isEnabled() && disableRule.getDisableUntil() != null) {
                try {
                    Date disableUntil = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").parse(disableRule.getDisableUntil());
                    if (new Date().before(disableUntil)) return;
                } catch (ParseException e) {
                    log.warn("解析临时禁用时间失败: {}", disableRule.getDisableUntil());
                }
            }
        }

        TraceabilityRules.Performance performance = config.getPerformance();
        if (performance != null && performance.getIgnoredMapIds() != null && performance.getIgnoredMapIds().contains(mapId)) {
            return;
        }

        boolean isValuable = InventoryManipulator.isValuableForRecovery(item);
        TraceabilityRules.RecordingTargets recordingTargets = config.getRecordingTargets();
        if (recordingTargets == null) return;

        TraceabilityRules.Target target = isValuable ? recordingTargets.getValuable() : recordingTargets.getNonValuable();
        boolean recordToDb = enabled.isDatabase() && target != null && target.isDatabase();
        boolean recordToLoki = enabled.isLoki() && target != null && target.isLoki();

        if (!recordToDb && !recordToLoki) return;

        Map<String, String> contextData = AuditContext.get();
        logExecutor.submit(() -> {
            try {
                String itemSnapshotJson = objectMapper.writeValueAsString(item.toInfoRtnDTO(true));

                if (recordToDb) {
                    ItemTraceLogsDO.ItemTraceLogsDOBuilder builder = ItemTraceLogsDO.builder()
                            .uid(item.getUid())
                            .accountId(accountId)
                            .characterId(characterId)
                            .actionType(actionType.name())
                            .actionSource(dbActionSource)
                            .mapId(mapId)
                            .itemId(item.getItemId())
                            .quantityChange(quantityChange)
                            .targetInfo(targetInfo)
                            .itemSnapshot(itemSnapshotJson)
                            .timestamp(System.currentTimeMillis())
                            .memo(memo);
                    itemTraceLogsMapper.insert(builder.build());
                }

                if (recordToLoki) {
                    MapMessage logData = new MapMessage()
                            .with("actsou", actionSourceType.name())
                            .with("uid", item.getUid())
                            .with("itmId", item.getItemId())
                            .with("itmName", String.valueOf(ItemInformationProvider.getInstance().getName(item.getItemId())))
                            .with("cnt", quantityChange)
                            .with("isVal", isValuable)
                            .with("itmData", itemSnapshotJson)
                            .with("msg", lokiMsg != null ? lokiMsg : dbActionSource);

                    for (Map.Entry<String, String> entry : contextData.entrySet()) {
                        logData.with(entry.getKey(), entry.getValue());
                    }
                    if (targetInfo != null && !targetInfo.isEmpty()) logData.with("target", targetInfo);
                    if (memo != null && !memo.isEmpty()) logData.with("memo", memo);

                    AuditLogger.info(accountId,characterId,mapId, MapFactory.loadPlaceName(mapId),LogModule.ITEM_TRACEAB.name(), actionType.name(), logData);
                }
            } catch (Exception e) {
                log.error("写入物品溯源双重日志失败，物品UID: " + item.getUid(), e);
            }
        });
    }

    /**
     * 检查并列出所有未在i18n属性文件中注册的物品溯源相关键值
     */
    public static boolean checkMissingI18nKeys() {
        List<String> missingKeys = new ArrayList<>();

        // 检查 ActionType
        for (ActionType type : ActionType.values()) {
            try {
                I18nUtil.logSource.getMessage(type.getI18nKey(), null, I18nUtil.LANGUAGE);
            } catch (NoSuchMessageException e) {
                missingKeys.add(type.getI18nKey());
            }
        }

        // 检查 ActionSourceType
        for (ActionSourceType type : ActionSourceType.values()) {
            try {
                I18nUtil.logSource.getMessage(type.getI18nKey(), null, I18nUtil.LANGUAGE);
            } catch (NoSuchMessageException e) {
                missingKeys.add(type.getI18nKey());
            }
        }

        if (!missingKeys.isEmpty()) {
            log.warn("检测到以下 i18n 物品溯源 键值未注册，请添加到 `log_zh_CN.properties` 和 `log_en_US.properties` 文件中：");
            for (String key : missingKeys) {
                log.warn(" - " + key);
            }
            return false;
        }
        return true;
    }
}
