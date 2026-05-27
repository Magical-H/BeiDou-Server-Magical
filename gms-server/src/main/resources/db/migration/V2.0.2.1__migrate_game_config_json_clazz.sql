-- V2.0.3.0: 将已确认 JSON 结构参数迁移为统一 config_clazz = 'json'
-- 关联方案：docs/后端/开发中/GameConfig_JSON参数改造详细开发计划.md
-- 前置条件：后端 GameConfig 已兼容 config_clazz='json'（Class.forName("json") 不再触发）；
--           cpu_mon 读取逻辑已改为显式 Jackson 解析，不再依赖 DTO clazz。

-- 1. use_equipment_level_up_vicious_levelrange_chance：二维数组，已是严格 JSON
UPDATE game_config
SET config_clazz = 'json'
WHERE config_code = 'use_equipment_level_up_vicious_levelrange_chance';

-- 2. traceability_rules：JSON 对象，已是严格 JSON
UPDATE game_config
SET config_clazz = 'json'
WHERE config_code = 'traceability_rules';

-- 3. cpu_mon：DTO JSON，读取逻辑已改为显式 Jackson 解析
UPDATE game_config
SET config_clazz = 'json'
WHERE config_code = 'cpu_mon';

-- 4. npcs_scriptable：需清洗 value 为严格 JSON
--    如果当前 value 是有效的 JSON（如 {"9001105":"Rescue Gaga!"}），只更新 clazz；
--    如果 value 存在未加引号的整型 key（如 {9001105:"Rescue Gaga!"}），尝试修复。
UPDATE game_config
SET config_value = REGEXP_REPLACE(
        REGEXP_REPLACE(config_value, '\\{([0-9]+):', '{"$1":'),
        ',([0-9]+):', ',"$1":'
    ),
    config_clazz = 'json'
WHERE config_code = 'npcs_scriptable'
  AND config_value REGEXP '^\\{[0-9]+:';

-- 对于已经是严格 JSON 的 npcs_scriptable，仅更新 clazz
UPDATE game_config
SET config_clazz = 'json'
WHERE config_code = 'npcs_scriptable'
  AND config_clazz != 'json';
