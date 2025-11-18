-- 游戏配置表
INSERT INTO `game_config`(`config_type`, `config_sub_type`, `config_clazz`, `config_code`, `config_value`, `config_desc`)
VALUES ('server', 'Game Mechanics', 'java.lang.Boolean', 'usb_cash_item_teleportrock', 'true', 'usb_cash_item_teleportrock');

-- 多语言资源表
INSERT INTO `lang_resources`(`lang_type`, `lang_base`, `lang_code`, `lang_value`)
VALUES
    ('zh-CN', 'game_config', 'usb_cash_item_teleportrock', '是否允许使用缩地石。（true:允许,false:禁止）'),
    ('en-US', 'game_config', 'usb_cash_item_teleportrock', 'Whether to allow the use of Teleport Rock. (true:allow, false:disable)');