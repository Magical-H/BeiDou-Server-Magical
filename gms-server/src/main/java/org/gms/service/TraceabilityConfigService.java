package org.gms.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.dao.mapper.GameConfigMapper;
import org.gms.model.pojo.TraceabilityRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

import static org.gms.dao.entity.table.GameConfigDOTableDef.GAME_CONFIG_DO;

/**
 * 物品溯源配置服务 (V2.11 - 统一后端JSON字段命名).
 * <p>
 * 作为溯源配置的唯一权威来源。它从GameConfig获取原始配置字符串，
 * 并使用项目统一的Jackson库进行解析，映射为强类型对象 TraceabilityRules，完全遵循项目架构规范。
 * 1. 使用静态变量替代魔法字符串"traceability_rules"。
 * 2. 增强保存逻辑：当配置项在数据库中不存在时，自动执行新增操作，而不是失败。
 * 3. 修复JSON存储格式：在保存时，如果传入的JSON包含"data"字段，则只保存"data"字段的内容。
 * 4. 统一后端JSON字段命名：所有字段在序列化时都将转换为全大写，以匹配前端预期。
 * </p>
 */
@Service
public class TraceabilityConfigService {

    private static final Logger log = LoggerFactory.getLogger(TraceabilityConfigService.class);
    private static final String CONFIG_CODE = "traceability_rules";

    private final ObjectMapper objectMapper; // 使用Spring注入的全局Jackson ObjectMapper
    private final GameConfigMapper gameConfigMapper;
    private final ConfigService configService;

    private volatile TraceabilityRules currentConfig; // 缓存当前的配置对象

    // 默认配置字符串，所有字段名都已改为全大写
    private static final String DEFAULT_TRACEABILITY_RULES = """
            {
              "ENABLED": {
                "DATABASE": true, // 是否启用写入到数据库表ITEM_TRACE_LOGS
                "LOKI": true      // 是否启用并行写入Loki日志系统
              },
              "RECORDINGTARGETS": { // 记录目标
                "VALUABLE": { "DATABASE": true, "LOKI": true },     // 有价值物品(通过下方的VALUECONDITIONS判断)的记录目标
                "NONVALUABLE": { "DATABASE": false, "LOKI": false } // 无价值物品(普通物品)的记录目标
              },
              "RETENTION": { // 保留期限
                "VALUABLE": { "DAYS": 90, "MAXCOUNT": 1000000 },    // 有价值物品在数据库中保留的最大天数和最大条数
                "NONVALUABLE": { "DAYS": 7, "MAXCOUNT": 500000 }    // 无价值物品在数据库中保留的最大天数和最大条数
              },
              "TEMPORARYDISABLES": { // 临时禁用
                "LOOT": { "ENABLED": true, "DISABLEUNTIL": null },     // 是否临时禁用 怪物掉落/拾取 记录，以及禁用截止时间
                "SHOP_BUY": { "ENABLED": true, "DISABLEUNTIL": null }  // 是否临时禁用 商店购买 记录，以及禁用截止时间
              },
              "LOGACTIONSWITCHES": { // 日志动作开关
                "TRADE": true,       // 是否记录 玩家间交易 (TRADE)
                "DROP": true,        // 是否记录 玩家丢弃 (DROP)
                "SELL": true,        // 是否记录 玩家出售给NPC (SELL)
                "STORAGE_IN": true,  // 是否记录 存入仓库 (STORAGE_IN)
                "STORAGE_OUT": true, // 是否记录 从仓库取出 (STORAGE_OUT)
                "GM_CREATE": true    // 是否记录 GM通过命令创造物品 (GM_CREATE)
              },
              "VALUECONDITIONS": { // 值判断条件
                "EQUIP": {
                  "MINLEVEL": 120,               // 装备最低穿戴等级限制（满足即视为有价值）
                  "MINUPGRADESLOTSUSED": 1,      // 装备最低已砸卷次数（满足即视为有价值）
                  "MINGROWTHLEVEL": 1,           // 装备最低成长等级（满足即视为有价值）
                  "MINVICIOUSHAMMERUSED": 1,     // 装备最低金锤子使用次数（满足即视为有价值）
                  "MINSTATSABOVEBASE": 1         // 装备属性总和(不含HP/MP)超过白板属性的最低值（满足即视为有价值）
                },
                "ITEM": {
                  "THROWINGWEAPONS": {           // 投掷武器
                    "ENABLED": true,             // 是否启用
                    "MINATTACKPOWER": 20,        // 飞镖最小攻击力
                    "MINATTACKPOWER_BULLET": 15  // 子弹最小攻击力
                  },
                  "POTIONS": {  // 强化药水
                    "ENABLED": true,             // 是否启用
                    "MINTOTALSTATBONUS": 50      // 最小总属性（不含HP/MP/四维属性）增益
                  },
                  "ITEMTYPES": [
                    { "T": 204, "D": "所有强化卷轴", "ENABLED": true },  // 所有强化卷轴
                    { "T": 228, "D": "所有技能书", "ENABLED": true },  // 所有技能书
                    { "T": 229, "D": "所有能手册", "ENABLED": true },  // 所能手册
                    { "T": 210, "D": "所有召唤包", "ENABLED": true },  // 所有召唤包
                    { "T": 301, "D": "所有椅子", "ENABLED": true }    // 所有椅子
                  ],
                  "SPECIFICITEMIDS": [          // 特定物品ID
                    { "ID": 2049100, "D": "混沌卷轴 (60%)", "ENABLED": true },
                    { "ID": 2340000, "D": "祝福卷轴", "ENABLED": true }
                  ]
                }
              },
              "PERFORMANCE": { // 性能优化
                "IGNOREDMAPIDS": [          // 忽略的地图ID
                  { "ID": 910000000, "D": "自由市场", "ENABLED": false }
                ]
              }
            }
            """;

    private static final TraceabilityRules defaultConfig;

    // 使用静态初始化块，在类加载时解析一次默认配置，无需@PostConstruct
    static {
        TraceabilityRules tempNode;
        try {
            // 此处不能使用注入的objectMapper，因为它在静态块执行时还未注入
            // 因此创建一个临时的、私有的mapper专门用于解析静态字符串
            ObjectMapper localMapper = new ObjectMapper();
            // 启用对JSON注释的解析支持 (Jackson的一个特性)
            localMapper.configure(JsonParser.Feature.ALLOW_COMMENTS, true);
            // 启用不区分大小写的属性反序列化，以提高容错性
            localMapper.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
            tempNode = localMapper.readValue(DEFAULT_TRACEABILITY_RULES, TraceabilityRules.class);
        } catch (IOException e) {
            log.error("FATAL: 无法解析内置的默认溯源规则，系统将使用空配置！", e);
            tempNode = new TraceabilityRules(); // 如果解析失败给个空对象防止NPE
        }
        defaultConfig = tempNode;
    }

    public TraceabilityConfigService(ObjectMapper objectMapper, GameConfigMapper gameConfigMapper, ConfigService configService) {
        this.objectMapper = objectMapper;
        this.gameConfigMapper = gameConfigMapper;
        this.configService = configService;
    }

    /**
     * 更新并保存溯源配置。
     * 如果配置项不存在，则自动创建。
     * @param config 新的配置JSON对象
     * @return 是否保存成功
     */
    public boolean saveTraceabilityConfig(JsonNode config) {
        // 提取实际的配置内容
        JsonNode actualConfigContent = config;
        if (config.has("requestId") && config.has("data")) {
            actualConfigContent = config.get("data");
        }
        String configValue = actualConfigContent.toString();

        QueryWrapper query = QueryWrapper.create().where(GAME_CONFIG_DO.CONFIG_CODE.eq(CONFIG_CODE));
        GameConfigDO gameConfigDO = gameConfigMapper.selectOneByQuery(query);

        if (gameConfigDO != null) {
            // 更新现有配置
            gameConfigDO.setConfigValue(configValue);
            configService.updateConfig(gameConfigDO);
        } else {
            // 新增配置
            log.warn("配置项 '{}' 在数据库中不存在，将执行新增操作。", CONFIG_CODE);
            GameConfigDO newConfig = GameConfigDO.builder()
                    .configType("server")
                    .configSubType("Debug") // 默认使用Debug子类型
                    .configCode(CONFIG_CODE)
                    .configValue(configValue)
                    .configClazz(String.class.getName())
                    .configDesc("物品溯源系统规则配置 (JSON)")
                    .build();
            configService.addConfig(newConfig);
        }
        return true;
    }

    /**
     * 重载配置：从 GameConfig 中获取最新的配置并解析缓存。
     */
    public void reloadConfig() {
        // 1. 从GameConfig获取原始字符串
        String configStr = GameConfig.getServerJsonString(CONFIG_CODE);

        // 2. 如果字符串为空，返回静态的、已解析好的默认配置 (恢复原始逻辑)
        if (configStr == null || configStr.isEmpty() || configStr.equals("{}")) {
            this.currentConfig = defaultConfig;
            return;
        }

        // 3. 使用业务层统一的Jackson ObjectMapper解析字符串为强类型对象
        try {
            this.currentConfig = objectMapper.readValue(configStr, TraceabilityRules.class);
            log.info("溯源系统规则已成功重载。");
        } catch (IOException e) {
            log.error("解析数据库中的溯源配置字符串失败，将使用默认配置。Error: {}", e.getMessage());
            this.currentConfig = defaultConfig;
        }
    }

    /**
     * 获取当前应用的溯源配置强类型对象。
     *
     * @return 当前有效的溯源规则 (TraceabilityRules).
     */
    public TraceabilityRules getTraceabilityConfig() {
        if (this.currentConfig == null) {
            reloadConfig();
        }
        return this.currentConfig;
    }

    /**
     * 获取内置的默认溯源配置强类型对象。
     *
     * @return 默认的溯源规则 (TraceabilityRules).
     */
    public TraceabilityRules getDefaultConfig() {
        return defaultConfig;
    }
}
