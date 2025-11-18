/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.gms.client.autoban;

import org.gms.client.Character;
import org.gms.client.Disease;
import org.gms.client.SkillFactory;
import org.gms.config.GameConfig;
import org.gms.constants.skills.Bowmaster;
import org.gms.constants.skills.Corsair;
import org.gms.constants.skills.WindArcher;
import org.gms.net.server.Server;
import org.gms.server.life.MobSkillFactory;
import org.gms.server.life.MobSkillType;
import org.gms.util.PacketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 自动封禁管理器
 * 负责管理玩家的违规行为检测和封禁处理
 * 采用点数累计系统，不同类型的违规行为有不同的点数和过期时间
 *
 * @author kevintjuh93
 */
public class AutobanManager {
    private static final Logger log = LoggerFactory.getLogger(AutobanManager.class);

    private final Character chr; // 关联的玩家玩家
    private final Map<AutobanFactory, Integer> punishPoints = new HashMap<>(); // 惩罚点数存储
    private final Map<AutobanFactory, Integer> banPoints = new HashMap<>(); // 封号点数存储
    private final Map<AutobanFactory, Long> lastTime = new HashMap<>(); // 最后一次违规时间
    private int misses = 0; // 未命中计数
    private int lastmisses = 0; // 上一次的未命中计数
    private int samemisscount = 0; // 相同未命中计数次数
    private final long[] spam = new long[20]; // 频繁操作时间记录数组
    private final int[] timestamp = new int[20]; // 时间戳记录数组
    private final byte[] timestampcounter = new byte[20]; // 时间戳计数器

    /**
     * 构造函数
     * @param chr 关联的玩家玩家
     */
    public AutobanManager(Character chr) {
        this.chr = chr;
    }

    /**
     * 是否使用自动封禁功能
     * @return true-使用自动封禁, false-不使用自动封禁
     */
    public boolean useAutoBan() {
        return GameConfig.getServerBoolean("use_auto_ban");
    }
    
    /**
     * 是否使用自动封禁日志
     * @return true-使用自动封禁日志, false-不使用自动封禁日志
     */
    public boolean useAutoBanLog () {
        return GameConfig.getServerBoolean("use_auto_ban_log");
    }

    /**
     * 是否启用反作弊自动检测
     * @return true-启用自动检测, false-不启用自动检测
     */
    public boolean useAntiCheat() {
        return GameConfig.getServerBoolean("anti_cheat_auto_detection");
    }
    /**
     * 是否启用反作弊自动断开连接
     * @return true-启用自动断开, false-不启用自动断开
     */
    public boolean useAntiCheatDisconnect() {
        return GameConfig.getServerBoolean("anti_cheat_auto_disconnect");
    }

    /**
     * 是否启用反作弊自动扣除HP/MP
     * @return true-启用自动扣除, false-不启用自动扣除
     */
    public boolean useAntiCheatLoseHpMp() {
        return GameConfig.getServerBoolean("anti_cheat_auto_losehpmp");
    }

    /**
     * 添加违规点数
     * 当惩罚点数达到阈值时清零惩罚点数并增加封号点数
     * 当封号点数达到阈值时执行封号操作
     *
     * @param fac 违规类型工厂
     * @param reason 违规原因描述
     * @return 1-已封号, 0-需要惩罚, -1-未触发任何操作
     */
    public int addPoint(AutobanFactory fac, String reason) {
        if (!useAntiCheat()) return -1;
        // GM或已被封禁的玩家不处理
        if (chr.gmLevel() >= 4 || chr.isBanned()) {
            return -1;
        }

        // 检查是否超过过期时间，超过则惩罚点数减半
        if (lastTime.containsKey(fac)) {
            if (lastTime.get(fac) <= (Server.getInstance().getCurrentTime() - fac.getExpire())) {
                punishPoints.put(fac, punishPoints.get(fac) / 2);
            }
        }

        // 记录本次违规时间
        if (fac.getExpire() != -1) {
            lastTime.put(fac, Server.getInstance().getCurrentTime());
        }

        // 增加惩罚点数
        int currentPunishPoints = punishPoints.getOrDefault(fac, 0) + 1;
        punishPoints.put(fac, currentPunishPoints);

        // 记录日志
        if (useAutoBanLog() && (fac != AutobanFactory.FAST_ATTACK || currentPunishPoints > 3)) {
            log.warn("[异常预警] 玩家 {} 在地图 {}({}) 触发 {} {}, 惩罚点数: {}",chr.getName(), chr.getMap().getMapName(),chr.getMapId(), fac.getName(), reason, currentPunishPoints);
        }

        // 惩罚点数达到最大值时增加封号点数并清零惩罚点数
        if (currentPunishPoints >= fac.getMaximum()) {
            // 清零惩罚点数
            punishPoints.put(fac, 0);

            // 增加封号点数
            int currentBanPoints = banPoints.getOrDefault(fac, 0) + 1;
            banPoints.put(fac, currentBanPoints);

            // 封号点数达到阈值时执行封号
            if (currentBanPoints >= 3) {
                // 清零封号点数
                //banPoints.put(fac, 0);
                if (useAutoBan()) {
                    chr.autoBan(reason);
                    return 1; // 已封号
                } else if (useAntiCheatDisconnect()) {
                    chr.getClient().disconnect(false,false);
                    Server.getInstance().broadcastGMMessage(chr.getWorld(), PacketCreator.sendYellowTip("[异常触发] 玩家 " + chr.getName() + " 在地图 " + chr.getMap().getMapName() + "(" + chr.getMapId() + ") 因触发 " + fac.getName() + " 而被断开连接"));
                    if (useAutoBanLog()) log.warn("[异常触发] 玩家 {} 在地图 {}({}) 因被检测到 {} 超过允许检测点数而被断开连接，具体原因 {}。",chr.getName(), chr.getMap().getMapName(),chr.getMapId(), fac.getName(), reason);
                    return 1;
                } else if (useAutoBanLog()) {
                    // 记录日志但不执行封号
                    Server.getInstance().broadcastGMMessage(chr.getWorld(), PacketCreator.sendYellowTip("[异常触发] 玩家 " + chr.getName() + " 在地图 " + chr.getMap().getMapName() + "(" + chr.getMapId() + ") 因触发 " + fac.getName() + " 但未启用自动封禁，因此无事发生。"));
                    log.warn("[异常触发] 玩家 {} 在地图 {}({}) 因被检测到 {} 达到封号条件但未启用自动封禁，具体原因 {}。",chr.getName(), chr.getMap().getMapName(),chr.getMapId(), fac.getName(), reason);
                }
            } else {
                // 记录日志
                if (useAutoBanLog()) {
                    Server.getInstance().broadcastGMMessage(chr.getWorld(), PacketCreator.sendYellowTip("[异常触发] 玩家 " + chr.getName() + " 在地图 " + chr.getMap().getMapName() + "(" + chr.getMapId() + ") 触发 " + fac.getName() + " - " + reason));
                    log.warn("[异常触发] 玩家 {} 在地图 {}({}) 触发 {} 惩罚点数已满 {}，增加封号点数至 {}", chr.getName(), chr.getMap().getMapName(),chr.getMapId(), fac.getName(), fac.getMaximum(),currentBanPoints);
                }
            }
            return 0; // 需要惩罚但未达到封号条件
        }
        return -1; // 未触发任何操作
    }

    /**
     * 自动扣除HP和MP惩罚
     * 当玩家触发违规时自动扣除一定量的HP和MP
     * 扣除量不会超过玩家当前的HP和MP上限
     * 
     * @param hpToLose 要扣除的HP值
     * @param mpToLose 要扣除的MP值
     */
    public void applyLoseHpMp(int hpToLose, int mpToLose) {
        if (!useAntiCheatLoseHpMp() || chr == null || chr.gmLevel() >= 4) {
            return;
        }

        // 限制扣除的HP不超过角色最大HP
        int actualHpLoss = Math.min(hpToLose, chr.getMaxHp());
        // 限制扣除的MP不超过角色最大MP
        int actualMpLoss = Math.min(mpToLose, chr.getMaxMp());

        // 使用addMPHP同时扣除HP和MP
        chr.addMPHP(-actualHpLoss, -actualMpLoss);

        // 记录日志
        if (useAutoBanLog()) {
            log.warn("[自动惩罚] 玩家 {} 因违规被扣除HP: {} MP: {}", chr.getName(), actualHpLoss, actualMpLoss);
        }
    }

    /**
     * 自动扣除HP和MP惩罚（带消息提示）
     * 当玩家触发违规时自动扣除一定量的HP和MP
     * 扣除量不会超过玩家当前的HP和MP上限
     * 
     * @param hpToLose 要扣除的HP值
     * @param mpToLose 要扣除的MP值
     * @param msg 提示消息
     */
    public void applyLoseHpMp(int hpToLose, int mpToLose, String msg) {
        // 调用原有的applyLoseHpMp方法处理HP/MP扣除逻辑
        applyLoseHpMp(hpToLose, mpToLose);
        
        // 如果消息不为空，则发送封包
        if (msg != null && !msg.isEmpty()) {
            chr.sendPacket(PacketCreator.earnTitleMessage(msg + " 超出部分对你自身造成 " + hpToLose + " 伤害"));
        }
    }
    /**
     * 施加debuff惩罚
     * @param duration 惩罚持续时间（毫秒）
     */
    public void applyDebuffPunishment(int duration) {
        if (chr == null || chr.gmLevel() >= 4) return;
        // 给玩家施加惩罚buff（眩晕，封印，致盲），防止客户端高频发包。
        MobSkillFactory.getMobSkill(MobSkillType.STUN, 7).ifPresent(skill -> chr.giveDebuff(Disease.STUN, skill, duration));
        MobSkillFactory.getMobSkill(MobSkillType.SEAL, 1).ifPresent(skill -> chr.giveDebuff(Disease.SEAL, skill, duration));
        MobSkillFactory.getMobSkill(MobSkillType.DARKNESS, 1).ifPresent(skill -> chr.giveDebuff(Disease.DARKNESS, skill, duration));
        if (useAutoBanLog()) {
            log.warn("[自动惩罚] 玩家 {} 因违规被施加debuff惩罚，持续时间: {}ms", chr.getName(), duration);
        }
    }

    /**
     * 处理快速攻击检测
     * 根据点数系统进行惩罚或封号
     * @return true-触发了惩罚或封号, false-未触发任何操作
     */
    public boolean Detection_FastAttack(int skill, int skilllevel) {
        int minAttackInterval = GameConfig.getServerInt("anti_cheat_fast_attack_interval");
        if (minAttackInterval <= 0) {
            return false;
        }
        long currentTime = Server.getInstance().getCurrentTime();
        long lastAttackTime = getLastSpam(8);

        // 如果是第一次攻击，记录时间并返回
        if (lastAttackTime == 0) {
            spam(8);
            return false;
        }

        // 计算攻击间隔
        long timeBetweenAttacks = currentTime - lastAttackTime;

        // 更新攻击时间和技能
        spam(8);

        // 检查攻击间隔是否小于最小允许间隔，且为相同技能，暴风箭雨/金属风暴 不做检测
        if (skill == 0 || (skill != WindArcher.HURRICANE && skill != Bowmaster.HURRICANE && skill != Corsair.RAPID_FIRE) && timeBetweenAttacks < minAttackInterval) {
            // 使用点数系统处理
            int result = addPoint(AutobanFactory.FAST_ATTACK, (skill > 0 ? "技能: " + SkillFactory.getSkillName(skill) + "[Lv." + skilllevel + "](" + skill + ")" : "普通攻击") + " 频率异常，间隔: " + timeBetweenAttacks + "ms (最小允许间隔: " + minAttackInterval + "ms)");

            if (result == 0) {
                // 需要惩罚，设置惩罚间隔
                int punishmentDuration = getBanPoints(AutobanFactory.FAST_ATTACK) * 10000; // 惩罚间隔至少10秒
                applyDebuffPunishment(punishmentDuration);
//                AutobanFactory.FAST_ATTACK.alert(chr, "惩罚时间: " + punishmentDuration + "ms ,攻击间隔: " + timeBetweenAttacks + "ms");
                chr.sendPacket(PacketCreator.earnTitleMessage("由于攻速过快，还需等待 " + punishmentDuration / 1000f + " 秒后才能恢复攻击。"));
            }
            return getPunishPoints(AutobanFactory.FAST_ATTACK) > 3;
        }
        return false;
    }

    /**
     * 增加未命中计数
     * 用于检测miss无敌模式外挂
     */
    public void addMiss() {
        this.misses++;
    }

    /**
     * 重置未命中计数并检测miss无敌模式
     * 连续多次出现相同的高miss计数时判定为使用外挂
     */
    public void resetMisses() {
        // 检测是否连续出现相同的高miss计数
        if (lastmisses == misses && misses > 6) {
            samemisscount++;
        }

        // 连续多次相同高miss，使用点数系统处理
        if (samemisscount > 4) {
            int result = addPoint(AutobanFactory.MISS_HACK, "连续高miss计数: " + samemisscount);
            if (result == 0 || result == 1) {
                chr.sendPolice("您将因miss无敌模式而被断开连接。");
            }
            samemisscount = 0; // 重置计数
        } else if (samemisscount > 0) {
            this.lastmisses = misses;
        }
        this.misses = 0;
    }

    /**
     * 记录频繁操作时间
     * @param type 操作类型
     */
    public void spam(int type) {
        this.spam[type] = Server.getInstance().getCurrentTime();
    }

    /**
     * 记录频繁操作时间（指定时间戳）
     * @param type 操作类型
     * @param timestamp 时间戳
     */
    public void spam(int type, long timestamp) {
        this.spam[type] = timestamp;
    }

    /**
     * 获取最后一次频繁操作时间
     * @param type 操作类型
     * @return 最后一次操作的时间戳
     */
    public long getLastSpam(int type) {
        return spam[type];
    }

    /**
     * 时间戳检查器
     * <code>type</code> 类型说明:<br>
     * 1: 宠物食品<br>
     * 2: 背包合并<br>
     * 3: 背包排序<br>
     * 4: 特殊移动<br>
     * 5: 使用捕捉道具<br>
     * 6: 物品丢弃<br>
     * 7: 聊天<br>
     * 8: 持续回复HP<br>
     * 9: 持续回复MP<br>
     * @param type 操作类型
     * @param time 当前时间戳
     * @param times 允许的最大次数
     */
    public void setTimestamp(int type, int time, int times) {
        if (this.timestamp[type] == time) {
            this.timestampcounter[type]++;
            if (this.timestampcounter[type] >= times) {
                if (useAutoBan()) {
                    chr.getClient().disconnect(false, false);
                    log.info("自动封禁 - 玩家 {} 因频繁操作类型 {} 被断开连接", chr, type);
                }
            }
        } else {
            this.timestamp[type] = time;
            this.timestampcounter[type] = 0;
        }
    }

    /**
     * 获取指定违规类型的当前惩罚点数
     * @param fac 违规类型
     * @return 当前惩罚点数
     */
    public int getPunishPoints(AutobanFactory fac) {
        return punishPoints.getOrDefault(fac, 0);
    }

    /**
     * 获取指定违规类型的当前封号点数
     * @param fac 违规类型
     * @return 当前封号点数
     */
    public int getBanPoints(AutobanFactory fac) {
        return banPoints.getOrDefault(fac, 0);
    }

    /**
     * 移除指定违规类型的惩罚点数
     * @param fac 违规类型
     */
    public void removePunishPoint(AutobanFactory fac) {
        if (punishPoints.containsKey(fac)) {
            int currentPoints = punishPoints.get(fac);
            if (currentPoints > 0) {
                punishPoints.put(fac, currentPoints - 1);
            }
        }
    }

    /**
     * 重置指定违规类型的惩罚点数
     * @param fac 违规类型
     */
    public void resetPunishPoints(AutobanFactory fac) {
        punishPoints.put(fac, 0);
        lastTime.remove(fac);
    }

    /**
     * 重置指定违规类型的封号点数
     * @param fac 违规类型
     */
    public void resetBanPoints(AutobanFactory fac) {
        banPoints.put(fac, 0);
    }
}