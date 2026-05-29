package org.gms.server.life;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.gms.config.GameConfig;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Getter
@Slf4j
public class BossKillNoticeConfig {
    public static final String CONFIG_CODE = "wild_boss_kill_notice_config";
    private static final int DEFAULT_DAMAGE_TIMEOUT_SECONDS = 300;
    private static final BossKillNoticeConfig DEFAULT_CONFIG = new BossKillNoticeConfig(
            false,
            false,
            Collections.emptySet(),
            DEFAULT_DAMAGE_TIMEOUT_SECONDS);

    private final boolean enabled;
    private final boolean broadcastAll;
    private final Set<Integer> bossIds;
    private final int damageTimeoutSeconds;

    private BossKillNoticeConfig(boolean enabled, boolean broadcastAll, Set<Integer> bossIds, int damageTimeoutSeconds) {
        this.enabled = enabled;
        this.broadcastAll = broadcastAll;
        this.bossIds = Collections.unmodifiableSet(bossIds);
        this.damageTimeoutSeconds = Math.max(1, damageTimeoutSeconds);
    }

    public static BossKillNoticeConfig load() {
        String rawConfig = GameConfig.getServerJsonString(CONFIG_CODE);
        if (rawConfig == null || rawConfig.isBlank()) {
            return DEFAULT_CONFIG;
        }

        try {
            JSONObject json = JSONObject.parseObject(rawConfig);
            if (json == null) {
                return DEFAULT_CONFIG;
            }

            return new BossKillNoticeConfig(
                    json.getBooleanValue("enabled", DEFAULT_CONFIG.enabled),
                    json.getBooleanValue("broadcastAll", DEFAULT_CONFIG.broadcastAll),
                    parseBossIds(json.getJSONArray("bossIds")),
                    json.getIntValue("damageTimeoutSeconds", DEFAULT_DAMAGE_TIMEOUT_SECONDS));
        } catch (JSONException | ClassCastException e) {
            log.warn("野外BOSS击杀播报配置解析失败，使用默认配置。configCode={}", CONFIG_CODE, e);
            return DEFAULT_CONFIG;
        }
    }

    private static Set<Integer> parseBossIds(JSONArray bossIds) {
        if (bossIds == null || bossIds.isEmpty()) {
            return Collections.emptySet();
        }

        Set<Integer> ids = new HashSet<>();
        for (Object bossId : bossIds) {
            if (bossId instanceof Number number) {
                ids.add(number.intValue());
            } else if (bossId instanceof String text && !text.isBlank()) {
                ids.add(Integer.parseInt(text.trim()));
            }
        }
        return ids;
    }

    public boolean shouldBroadcast(int bossId) {
        return enabled && (broadcastAll || bossIds.contains(bossId));
    }
}
