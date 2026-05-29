INSERT INTO `game_config`(`config_type`, `config_sub_type`, `config_clazz`, `config_code`, `config_value`, `config_desc`)
VALUES
    ('server', 'Anti Cheat', 'json', 'wild_boss_kill_notice_config',
     '{"enabled":true,"broadcastAll":true,"bossIds":[],"damageTimeoutSeconds":300}',
     'wild_boss_kill_notice_config');

INSERT INTO `lang_resources`(`lang_type`, `lang_base`, `lang_code`, `lang_value`)
VALUES
    ('zh-CN', 'game_config', 'wild_boss_kill_notice_config',
     '野外BOSS击杀播报配置'),
    ('en-US', 'game_config', 'wild_boss_kill_notice_config',
     'Wild boss kill notice config'),
    ('zh-CN', 'game_config_json_i18n', 'wild_boss_kill_notice_config',
     '{"_self":"野外BOSS击杀播报配置","enabled":"是否启用全服击杀播报","broadcastAll":"是否播报全部符合野外条件的BOSS","bossIds":"指定播报的BOSS ID列表，broadcastAll=false时生效","bossIdsItem":"BOSS怪物ID","damageTimeoutSeconds":"BOSS在该秒数内未受到伤害时清空参与记录，恢复HP/MP并清除状态、debuff与仇恨"}'),
    ('en-US', 'game_config_json_i18n', 'wild_boss_kill_notice_config',
     '{"_self":"Wild boss kill notice config","enabled":"Whether to enable server-wide kill notices","broadcastAll":"Whether to announce every eligible wild boss","bossIds":"Boss ID allow-list used when broadcastAll=false","bossIdsItem":"Boss monster ID","damageTimeoutSeconds":"Seconds without damage before clearing participation, restoring HP/MP, and clearing statuses, debuffs, and aggro"}');
