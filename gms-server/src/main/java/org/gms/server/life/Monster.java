/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as
 published by the Free Software Foundation version 3 as published by
 the Free Software Foundation. You may not use, modify or distribute
 this program under any other version of the GNU Affero General Public
 License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.gms.server.life;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.FamilyEntry;
import org.gms.client.Job;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.client.status.MonsterStatus;
import org.gms.client.status.MonsterStatusEffect;
import org.gms.config.GameConfig;
import org.gms.constants.id.MobId;
import org.gms.constants.skills.Crusader;
import org.gms.constants.skills.FPMage;
import org.gms.constants.skills.Hermit;
import org.gms.constants.skills.ILMage;
import org.gms.constants.skills.NightLord;
import org.gms.constants.skills.NightWalker;
import org.gms.constants.skills.Priest;
import org.gms.constants.skills.Shadower;
import org.gms.constants.skills.WhiteKnight;
import org.gms.net.packet.Packet;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.coordinator.world.MonsterAggroCoordinator;
import org.gms.net.server.services.task.channel.MobAnimationService;
import org.gms.net.server.services.task.channel.MobClearSkillService;
import org.gms.net.server.services.task.channel.MobStatusService;
import org.gms.net.server.services.task.channel.OverallService;
import org.gms.net.server.services.type.ChannelServices;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import org.gms.util.*;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.StatEffect;
import org.gms.server.TimerManager;
import org.gms.server.life.LifeFactory.BanishInfo;
import org.gms.server.loot.LootManager;
import org.gms.server.maps.AbstractAnimatedMapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Summon;

import java.awt.*;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Setter @Getter
public class Monster extends AbstractLoadedLife {
    private static final long MIN_BOSS_DPS_DURATION_MILLIS = 1000L;
    private static final long BOSS_DAMAGE_TIMEOUT_CHECK_INTERVAL_MILLIS = 5000L;

    /*
    ======================================
     C核心属性 (Core Attributes)
    ======================================
    */
    /** 基础属性-怪物的静态数据模板 */
    private MonsterStats stats;
    /** 动态属性-怪物可覆盖的动态属性 */
    private ChangeableStats ostats = null;
    /** 当前生命值-怪物的当前HP */
    private final AtomicInteger hp = new AtomicInteger(1);
    /** 当前魔法值-怪物的当前MP */
    private int mp;
    /** 最大生命值记录-用于计算总伤害，包含治疗量 */
    private final AtomicLong maxHpPlusHeal = new AtomicLong(1);
    /** 所在地图-怪物当前所在的地图实例 */
    private MapleMap map;

    /*
    ======================================
     状态与效果 (Status & Effects)
    ======================================
    */
    /** 状态效果-怪物当前承受的状态效果集合 */
    private final EnumMap<MonsterStatus, MonsterStatusEffect> stati = new EnumMap<>(MonsterStatus.class);
    /** 临时元素抗性-怪物的临时元素属性效果 */
    private final Map<Element, ElementalEffectiveness> tempEffectiveness = new EnumMap<>(Element.class);
    /** 已应用Buff列表-记录已施加的状态以避免重复 */
    private final ArrayList<MonsterStatus> alreadyBuffed = new ArrayList<>();
    /** 中毒效果倍率-用于计算持续伤害 */
    private int VenomMultiplier = 0;

    /*
    ======================================
     行为与交互 (Behavior & Interaction)
    ======================================
    */
    /** 控制者-当前控制该怪物的玩家 */
    private WeakReference<Character> controller = new WeakReference<>(null);
    /** 控制者仇恨状态-标记控制者是否对怪物有仇恨 */
    private boolean controllerHasAggro;
    /** 控制者仇恨感知-标记控制者是否已知晓其仇恨状态 */
    private boolean controllerKnowsAboutAggro;
    /** 控制者傀儡状态-标记控制者是否拥有傀儡 */
    private boolean controllerHasPuppet;
    /** 伤害记录-记录所有玩家对该怪物的伤害量 */
    private final HashMap<Integer, AtomicLong> takenDamage = new HashMap<>();
    private final HashMap<Integer, BossDamageRecord> bossDamageRecords = new HashMap<>();
    private long bossDamageEncounterStartTime = 0L;
    private long bossDamageLastTime = 0L;
    private int bossDamageEncounterMobId = 0;
    private long bossDamageTimeoutToken = 0L;
    private ScheduledFuture<?> bossDamageTimeoutTask = null;
    /** 最近伤害时间-记录每个玩家的最后伤害时间戳 */
    private final HashMap<Integer, Long> takenDamageTime = new HashMap<>();
    /** 召唤兽伤害标记-区分伤害来源是玩家还是召唤兽 */
    private final HashMap<Integer, Boolean> summonDamageFlag = new HashMap<>();
    /** 最近攻击技能ID-记录造成伤害的最后一个技能ID */
    private final AtomicInteger lastSkillId = new AtomicInteger(0);
    /** 最近攻击目标数-记录最后一个技能攻击的目标数量 */
    private final AtomicInteger lastSkillTargetCount = new AtomicInteger(0);
    /** 已使用技能-记录已进入冷却的技能 */
    private final Set<MobSkillId> usedSkills = new HashSet<>();
    /** 已使用攻击-记录已进入冷却的普通攻击 */
    private final Set<Integer> usedAttacks = new HashSet<>();
    /** 召唤者-召唤出此怪物的父怪物 */
    private WeakReference<Monster> callerMob = new WeakReference<>(null);
    /** 被召唤物列表-记录此怪物召唤出的其他怪物的OID */
    private Set<Integer> calledMobOids = null;
    /** 被偷物品-记录被玩家偷走的物品ID */
    private final List<Integer> stolenItems = new ArrayList<>(5);
    /** 标记玩家ID-用于特殊技能，标记怪物的玩家ID，比如导航标记 */
    private int markedBy = 0;

    /*
    ======================================
     特殊与元数据 (Special & Metadata)
    ======================================
    */
    /** 阵营-用于怪物嘉年华等玩法的队伍标识 */
    private int team;
    /** 父怪物OID-用于召唤物，关联召唤者 */
    private int parentMobOid = 0;
    /** 刷新特效-怪物出生时的视觉特效ID */
    private int spawnEffect = 0;
    /** 伪装状态-标记是否为假怪物（无实际交互） */
    private boolean fake = false;
    /** 禁用掉落-标记是否禁止该怪物掉落物品 */
    private boolean dropsDisabled = false;
    /** 持久化标记-标记该怪物是否需要在死亡后将其刷新时间持久化到数据库 */
    private boolean shouldPersist = false;
    /** 持久化ID-数据库里对应的记录ID */
    private int shouldId = -1;
    /** 出场消息-用于怪物出生时显示的提示消息 */
    private String msgRebirth = null; //出场广播消息，在当前地图广播
    /** 死亡消息-用于怪物死亡时显示的提示消息 */
    private String msgDeath = null; //死亡广播消息，在当前地图广播


    /*
    ======================================
     定时任务与监听器 (Tasks & Listeners)
    ======================================
    */
    /** 怪物监听器-用于在特定事件发生时通知其他对象 */
    private final Collection<MonsterListener> listeners = new LinkedList<>();
    /** 怪物定时掉落-用于月妙等需要定时产出物品的怪物 */
    private ScheduledFuture<?> monsterItemDrop = null;
    /** 消失动作-用于在怪物被移除时执行的额外逻辑 */
    private Runnable removeAfterAction = null;
    /** 傀儡更新可用性-防止短时间内重复更新傀儡仇恨 */
    private boolean availablePuppetUpdate = true;

    /*
    ======================================
     线程锁 (Locks)
    ======================================
    */
    /** 外部锁-用于保护怪物对象的并发访问 */
    private final Lock externalLock = new ReentrantLock();
    /** 怪物内部锁-保护怪物核心数据的线程安全 */
    private final Lock monsterLock = new ReentrantLock(true);
    /** 状态锁-保护怪物状态效果集合的线程安全 */
    private final Lock statiLock = new ReentrantLock();
    /** 动画锁-防止动画效果的并发冲突 */
    private final Lock animationLock = new ReentrantLock();
    /** 仇恨更新锁-保护仇恨控制器切换逻辑的线程安全 */
    private final Lock aggroUpdateLock = new ReentrantLock();

    public Monster(int id, MonsterStats stats) {
        super(id);
        initWithStats(stats);
    }

    public Monster(Monster monster) {
        super(monster);
        initWithStats(monster.stats);
    }

    public void lockMonster() {
        externalLock.lock();
    }

    public void unlockMonster() {
        externalLock.unlock();
    }

    private void initWithStats(MonsterStats baseStats) {
        setStance(5);
        this.stats = baseStats.copy();
        hp.set(stats.getHp());
        mp = stats.getMp();

        maxHpPlusHeal.set(hp.get());
    }

    public void disableDrops() {
        this.dropsDisabled = true;
    }

    public void enableDrops() {
        this.dropsDisabled = false;
    }

    public int countAvailableMobSummons(int summonsSize, int skillLimit) {    // limit prop for summons has another conotation, found thanks to MedicOP
        int summonsCount;

        Set<Integer> calledOids = this.calledMobOids;
        if (calledOids != null) {
            summonsCount = calledOids.size();
        } else {
            summonsCount = 0;
        }

        return Math.min(summonsSize, skillLimit - summonsCount);
    }

    public void addSummonedMob(Monster mob) {
        Set<Integer> calledOids = this.calledMobOids;
        if (calledOids == null) {
            calledOids = Collections.synchronizedSet(new HashSet<>());
            this.calledMobOids = calledOids;
        }

        calledOids.add(mob.getObjectId());
        mob.setSummonerMob(this);
    }

    private void removeSummonedMob(int mobOid) {
        Set<Integer> calledOids = this.calledMobOids;
        if (calledOids != null) {
            calledOids.remove(mobOid);
        }
    }

    private void setSummonerMob(Monster mob) {
        this.callerMob = new WeakReference<>(mob);
    }

    private void dispatchClearSummons() {
        Monster caller = this.callerMob.get();
        if (caller != null) {
            caller.removeSummonedMob(this.getObjectId());
        }

        this.calledMobOids = null;
    }

    public void pushRemoveAfterAction(Runnable run) {
        this.removeAfterAction = run;
    }

    public Runnable popRemoveAfterAction() {
        Runnable r = this.removeAfterAction;
        this.removeAfterAction = null;

        return r;
    }

    public int getHp() {
        return hp.get();
    }

    public synchronized void addHp(int hp) {
        if (this.hp.get() <= 0) {
            return;
        }
        this.hp.addAndGet(hp);
    }

    public synchronized void setStartingHp(int hp) {
        stats.setHp(hp);    // refactored mob stats after non-static HP pool suggestion thanks to twigs
        //this.hp.set(hp);  //这里注释掉，否则获取的经验值异常
    }

    public int getMaxHp() {
        return stats.getHp();
    }

    /**
     * 获取玩家上一次释放的技能ID
     * 
     * @return 上一次使用的技能ID
     */
    public int getLastSkillId() {
        return lastSkillId.get();
    }

    /**
     * 设置玩家上一次释放的技能ID
     * 
     * @param skillId 要设置的技能ID
     */
    public void setLastSkillId(int skillId) {
        lastSkillId.set(skillId);
    }

    /**
     * 获取玩家上一次伤害的目标数
     * 
     * @return 上一次技能的目标数
     */
    public int getLastSkillTargetCount() {
        return lastSkillTargetCount.get();
    }

    /**
     * 设置玩家上一次伤害的目标数
     * 
     * @param targetCount 要设置的目标数
     */
    public void setLastSkillTargetCount(int targetCount) {
        lastSkillTargetCount.set(targetCount);
    }

    public void setMp(int mp) {
        if (mp < 0) {
            mp = 0;
        }
        this.mp = mp;
    }

    public int getMaxMp() {
        return stats.getMp();
    }

    public int getExp() {
        return stats.getExp();
    }

    public int getLevel() {
        return stats.getLevel();
    }

    public int getCP() {
        return stats.getCP();
    }

    public int getVenomMulti() {
        return this.VenomMultiplier;
    }

    public void setVenomMulti(int multiplier) {
        this.VenomMultiplier = multiplier;
    }

    public boolean isBoss() {
        return stats.isBoss();
    }

    public int getAnimationTime(String name) {
        return stats.getAnimationTime(name);
    }

    private List<Integer> getRevives() {
        return stats.getRevives();
    }

    private byte getTagColor() {
        return stats.getTagColor();
    }

    private byte getTagBgColor() {
        return stats.getTagBgColor();
    }

    public void setHpZero() {     // force HP = 0
        applyAndGetHpDamage(Integer.MAX_VALUE, false);
        cancelBossDamageTimeout();
    }

    private boolean applyAnimationIfRoaming(int attackPos, MobSkill skill) {   // 漫游：不播放攻击或技能动画
        if (!animationLock.tryLock()) {
            return false;
        }

        try {
            long animationTime;

            if (skill == null) {
                animationTime = MonsterInformationProvider.getInstance().getMobAttackAnimationTime(this.getId(), attackPos);
            } else {
                animationTime = MonsterInformationProvider.getInstance().getMobSkillAnimationTime(skill);
            }

            if (animationTime > 0) {
                MobAnimationService service = (MobAnimationService) map.getChannelServer().getServiceAccess(ChannelServices.MOB_ANIMATION);
                return service.registerMobOnAnimationEffect(map.getId(), this.hashCode(), animationTime);
            } else {
                return true;
            }
        } finally {
            animationLock.unlock();
        }
    }

    public synchronized Integer applyAndGetHpDamage(int delta, boolean stayAlive) {
        int curHp = hp.get();
        if (curHp <= 0) {       // this monster is already dead
            return null;
        }

        if (delta >= 0) {
            if (stayAlive) {
                curHp--;
            }
            int trueDamage = Math.min(curHp, delta);

            hp.addAndGet(-trueDamage);
            return trueDamage;
        } else {
            int trueHeal = -delta;
            int hp2Heal = curHp + trueHeal;
            int maxHp = getMaxHp();

            if (hp2Heal > maxHp) {
                trueHeal -= (hp2Heal - maxHp);
            }

            hp.addAndGet(trueHeal);
            return trueHeal;
        }
    }

    public synchronized void disposeMapObject() {     // mob is no longer associated with the map it was in
        hp.set(-1);
    }

    public void broadcastMobHpBar(Character from) {
        if (hasBossHPBar()) {
            from.setPlayerAggro(this.hashCode());
            from.getMap().broadcastBossHpMessage(this, this.hashCode(), makeBossHPBarPacket(), getPosition());
        } else if (!isBoss()) {
            int remainingHP = (int) Math.max(1, hp.get() * 100f / getMaxHp());
            Packet packet = PacketCreator.showMonsterHP(getObjectId(), remainingHP);
            if (from.getParty() != null) {
                for (PartyCharacter mpc : from.getParty().getMembers()) {
                    Character member = from.getMap().getCharacterById(mpc.getId()); // god bless
                    if (member != null) {
                        member.sendPacket(packet);
                    }
                }
            } else {
                from.sendPacket(packet);
            }
        }
    }

    public boolean damage(Character attacker, int damage, boolean stayAlive) {
        boolean lastHit = false;

        this.lockMonster();
        try {
            if (!this.isAlive()) {
                return false;
            }

            /* pyramid not implemented
            Pair<Integer, Integer> cool = this.getStats().getCool();
            if (cool != null) {
                Pyramid pq = (Pyramid) chr.getPartyQuest();
                if (pq != null) {
                    if (damage > 0) {
                        if (damage >= cool.getLeft()) {
                            if ((Math.random() * 100) < cool.getRight()) {
                                pq.cool();
                            } else {
                                pq.kill();
                            }
                        } else {
                            pq.kill();
                        }
                        killed = true;
                    } else {
                        pq.miss();
                    }
                    killed = true;
                }
            }
            */

            if (damage > 0) {
                this.applyDamage(attacker, damage, stayAlive, false, false);
                if (!this.isAlive()) {  // monster just died
                    lastHit = true;
                }
            }
        } finally {
            this.unlockMonster();
        }

        return lastHit;
    }

    /**
     * 应用伤害到怪物
     * 
     * @param from 攻击者角色
     * @param damage 伤害值
     * @param stayAlive 是否保持存活（防止死亡）
     * @param fake 是否为虚假伤害（不触发监听器等逻辑）
     * @param isFromSummon 是否为召唤兽伤害，true为召唤兽伤害，false为玩家直接伤害
     */
    private void applyDamage(Character from, int damage, boolean stayAlive, boolean fake, boolean isFromSummon) {
        Integer trueDamage = applyAndGetHpDamage(damage, stayAlive);
        if (trueDamage == null) {
            return;
        }

        if (GameConfig.getServerBoolean("use_debug") && from.isGM()) {
            from.dropMessage(5, I18nUtil.getMessage("Monster.applyDamage.message1", 
                this.getObjectId(), 
                this.getName(), 
                this.getId(), 
                this.getLevel(),
                trueDamage));
        }

        if (!fake) {
            dispatchMonsterDamaged(from, trueDamage);
        }

        if (!takenDamage.containsKey(from.getId())) {
            takenDamage.put(from.getId(), new AtomicLong(trueDamage));
        } else {
            takenDamage.get(from.getId()).addAndGet(trueDamage);
        }
        // 记录伤害时间戳
        takenDamageTime.put(from.getId(), System.currentTimeMillis());
        // 记录是否是召唤兽伤害，如果玩家有直接伤害会覆盖召唤兽的标记
        if (!isFromSummon) {
            from.setLastMonsterDamageTime(System.currentTimeMillis());//更新玩家的最后活跃时间戳
            summonDamageFlag.put(from.getId(), false);//伤害来源：玩家
        } else if (!summonDamageFlag.containsKey(from.getId())) {
            summonDamageFlag.put(from.getId(), true);//伤害来源：召唤兽
        }

        recordBossDamage(from, damage, trueDamage, isFromSummon);
        broadcastMobHpBar(from);
    }

    public void applyFakeDamage(Character from, int damage, boolean stayAlive) {
        lockMonster();
        try {
            applyDamage(from, damage, stayAlive, true, false);
        } finally {
            unlockMonster();
        }
    }
    
    public void applySummonDamage(Character from, int damage, boolean stayAlive) {
        lockMonster();
        try {
            applyDamage(from, damage, stayAlive, false, true);
        } finally {
            unlockMonster();
        }
    }

    private void recordBossDamage(Character from, int actualDamage, int hpDamage, boolean isFromSummon) {
        if (!isBoss() || actualDamage <= 0 || hpDamage <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        if (bossDamageEncounterStartTime <= 0) {
            bossDamageEncounterStartTime = now;
            bossDamageEncounterMobId = getId();
        }
        bossDamageLastTime = now;

        BossDamageRecord record = bossDamageRecords.computeIfAbsent(from.getId(), id -> new BossDamageRecord(from));
        record.addDamage(from, actualDamage, isFromSummon, now, lastSkillId.get(), lastSkillTargetCount.get());
        if (isAlive()) {
            ensureBossDamageTimeoutTask();
        } else {
            cancelBossDamageTimeout();
        }
    }

    private void ensureBossDamageTimeoutTask() {
        if (bossDamageTimeoutTask != null) {
            return;
        }

        long token = ++bossDamageTimeoutToken;
        bossDamageTimeoutTask = TimerManager.getInstance().register(
                () -> resetBossDamageIfExpired(token),
                BOSS_DAMAGE_TIMEOUT_CHECK_INTERVAL_MILLIS,
                BOSS_DAMAGE_TIMEOUT_CHECK_INTERVAL_MILLIS);
    }

    private long getBossDamageTimeoutMillis() {
        return BossKillNoticeConfig.load().getDamageTimeoutSeconds() * 1000L;
    }

    private void resetBossDamageIfExpired(long token) {
        lockMonster();
        try {
            if (token != bossDamageTimeoutToken || bossDamageTimeoutTask == null) {
                return;
            }

            if (!isAlive() || bossDamageLastTime <= 0 || bossDamageRecords.isEmpty()) {
                cancelBossDamageTimeout();
                return;
            }

            long timeoutMillis = getBossDamageTimeoutMillis();
            long elapsedMillis = System.currentTimeMillis() - bossDamageLastTime;
            if (elapsedMillis < timeoutMillis) {
                return;
            }

            notifyBossDamageTimeoutDebug(getMap(), timeoutMillis);
            clearBossDamageTracking(true);
        } finally {
            unlockMonster();
        }
    }

    private void clearBossDamageTracking(boolean restoreHp) {
        cancelBossDamageTimeout();
        bossDamageRecords.clear();
        bossDamageEncounterStartTime = 0L;
        bossDamageLastTime = 0L;
        bossDamageEncounterMobId = 0;
        takenDamage.clear();
        takenDamageTime.clear();
        summonDamageFlag.clear();
        maxHpPlusHeal.set(getMaxHp());

        MapleMap currentMap = getMap();
        resetBossCombatState(currentMap);

        if (restoreHp && isAlive()) {
            int beforeHp = hp.get();
            int maxHp = getMaxHp();
            int maxMp = getMaxMp();
            hp.set(maxHp);
            setMp(maxMp);

            if (currentMap != null) {
                if (beforeHp < maxHp) {
                    currentMap.broadcastMessage(PacketCreator.healMonster(getObjectId(), maxHp - beforeHp, maxHp, maxHp));
                }
            }
        }
    }

    private void notifyBossDamageTimeoutDebug(MapleMap currentMap, long timeoutMillis) {
        if (currentMap == null || !GameConfig.getServerBoolean("use_debug") || bossDamageRecords.isEmpty()) {
            return;
        }

        long totalDamage = bossDamageRecords.values().stream().mapToLong(record -> record.totalDamage).sum();
        String message = String.format(
                "[野外BOSS调试] %s(%d) %d秒未受到伤害，已清空参与记录并恢复HP/MP、状态和仇恨。参与人数=%d，总伤害=%d",
                getName(), getId(), timeoutMillis / 1000L, bossDamageRecords.size(), totalDamage);

        for (BossDamageRecord record : bossDamageRecords.values()) {
            Character chr = currentMap.getCharacterById(record.playerId);
            if (chr != null && chr.isGM()) {
                chr.dropMessage(5, message);
            }
        }
    }

    private void resetBossCombatState(MapleMap currentMap) {
        clearMonsterStatuses(currentMap);
        aggroClearDamages();
        aggroRemoveController();

        if (currentMap != null && hasBossHPBar()) {
            for (Character chr : currentMap.getAllPlayers()) {
                if (chr.getTargetHpBarHash() == hashCode()) {
                    chr.resetPlayerAggro();
                }
            }
        }
    }

    private void clearMonsterStatuses(MapleMap currentMap) {
        List<MonsterStatusEffect> activeEffects;
        statiLock.lock();
        try {
            activeEffects = new ArrayList<>(new HashSet<>(stati.values()));
        } finally {
            statiLock.unlock();
        }

        if (currentMap != null) {
            MobStatusService service = (MobStatusService) currentMap.getChannelServer().getServiceAccess(ChannelServices.MOB_STATUS);
            for (MonsterStatusEffect effect : activeEffects) {
                service.interruptMobStatus(currentMap.getId(), effect);
            }
        }

        Map<MonsterStatus, Integer> remainingStatuses = new EnumMap<>(MonsterStatus.class);
        statiLock.lock();
        try {
            for (Entry<MonsterStatus, MonsterStatusEffect> entry : stati.entrySet()) {
                Integer value = entry.getValue().getStati().get(entry.getKey());
                remainingStatuses.put(entry.getKey(), value != null ? value : 0);
            }
            stati.clear();
            alreadyBuffed.clear();
        } finally {
            statiLock.unlock();
        }

        if (currentMap != null && !remainingStatuses.isEmpty()) {
            broadcastMonsterStatusMessage(PacketCreator.cancelMonsterStatus(getObjectId(), remainingStatuses));
        }
    }

    private void cancelBossDamageTimeout() {
        if (bossDamageTimeoutTask != null) {
            bossDamageTimeoutTask.cancel(false);
            bossDamageTimeoutTask = null;
        }
        bossDamageTimeoutToken++;
    }

    public void heal(int hp, int mp) {
        Integer hpHealed = applyAndGetHpDamage(-hp, false);
        if (hpHealed == null) {
            return;
        }

        int mp2Heal = getMp() + mp;
        int maxMp = getMaxMp();
        if (mp2Heal >= maxMp) {
            mp2Heal = maxMp;
        }
        setMp(mp2Heal);

        if (hp > 0) {
            getMap().broadcastMessage(PacketCreator.healMonster(getObjectId(), hp, getHp(), getMaxHp()));
        }

        maxHpPlusHeal.addAndGet(hpHealed);
        dispatchMonsterHealed(hpHealed);
    }

    public boolean isAttackedBy(Character chr) {
        return takenDamage.containsKey(chr.getId());
    }

    private static boolean isWhiteExpGain(Character chr, Map<Integer, Float> personalRatio, double sdevRatio) {
        Float pr = personalRatio.get(chr.getId());
        if (pr == null) {
            return false;
        }

        return pr >= sdevRatio;
    }

    private static double calcExperienceStandDevThreshold(List<Float> entryExpRatio, int totalEntries) {
        float avgExpReward = 0.0f;
        for (Float exp : entryExpRatio) {
            avgExpReward += exp;
        }

        // thanks Simon (HarborMS) for finding an issue with solo party player gaining yellow EXP when soloing mobs
        avgExpReward /= totalEntries;

        float varExpReward = 0.0f;
        for (Float exp : entryExpRatio) {
            varExpReward += (float) Math.pow(exp - avgExpReward, 2);
        }
        varExpReward /= entryExpRatio.size();

        return avgExpReward + Math.sqrt(varExpReward);
    }

    private void distributePlayerExperience(Character chr, float exp, float partyBonusMod, int totalPartyLevel, boolean highestPartyDamager, boolean whiteExpGain, boolean hasPartySharers, float activeExp, boolean isActiveMember) {
        float playerExp = (GameConfig.getServerFloat("exp_split_common_mod") * chr.getLevel()) / totalPartyLevel;
        if (highestPartyDamager) {
            playerExp += GameConfig.getServerFloat("exp_split_mvp_mod");
        }

        playerExp *= exp;
        float bonusExp = partyBonusMod * playerExp;
        
        // 活跃成员额外经验（活跃成员获得所有被队员打死的怪物的活跃加成）
        if (isActiveMember && activeExp > 0) {
            bonusExp += activeExp;
        }

        this.giveExpToCharacter(chr, playerExp, bonusExp, whiteExpGain, hasPartySharers);
        giveFamilyRep(chr.getFamilyEntry());
    }

    private void distributePartyExperience(Map<Character, Long> partyParticipation, float expPerDmg, Set<Character> underleveled, Map<Integer, Float> personalRatio, double sdevRatio) {
        IntervalBuilder leechInterval = new IntervalBuilder();
        leechInterval.addInterval(this.getLevel() - GameConfig.getServerInt("exp_split_level_interval"), this.getLevel() + GameConfig.getServerInt("exp_split_level_interval"));

        long maxDamage = 0, partyDamage = 0;
        Character participationMvp = null;
        // 计算组队活跃成员数量（需要检查时效）
        long currentTime = System.currentTimeMillis();
        int activeMembers = 0;
        int timeoutSeconds = GameConfig.getServerInt("party_active_member_timeout_seconds");
        long timeoutMillis = timeoutSeconds * 1000L;
        
        // 获取组队成员列表（用于后续多次使用）
        Character anyPartyMember = partyParticipation.keySet().iterator().next();
        List<Character> allPartyMembers = anyPartyMember.getPartyMembersOnSameMap();
        
        // 计算活跃成员数（从组队成员出发，检查每个成员的Character级别活跃时间戳）
        // 这确保了不管玩家是否伤害了本只怪物，只要在活跃时间窗口内伤害过其他怪物，就计入活跃
        for (Character member : allPartyMembers) {
            long lastDamageTime = member.getLastMonsterDamageTime();
            if (lastDamageTime > 0 && currentTime - lastDamageTime <= timeoutMillis) {
                activeMembers++;
            }
        }
        for (Entry<Character, Long> e : partyParticipation.entrySet()) {
            long entryDamage = e.getValue();
            partyDamage += entryDamage;

            if (maxDamage < entryDamage) {
                maxDamage = entryDamage;
                participationMvp = e.getKey();
            }

            // thanks Thora for pointing out leech level limitation
            int chrLevel = e.getKey().getLevel();
            leechInterval.addInterval(chrLevel - GameConfig.getServerInt("exp_split_leech_interval"), chrLevel + GameConfig.getServerInt("exp_split_leech_interval"));
        }

        List<Character> expMembers = new LinkedList<>();
        int totalPartyLevel = 0;

        // thanks G h o s t, Alfred, Vcoc, BHB for poiting out a bug in detecting party members after membership transactions in a party took place
        if (GameConfig.getServerBoolean("use_enforce_mob_level_range")) {
            for (Character member : allPartyMembers) {
                if (!leechInterval.inInterval(member.getLevel())) {
                    underleveled.add(member);
                    continue;
                }

                totalPartyLevel += member.getLevel();
                expMembers.add(member);
            }
        } else {    // thanks Ari for noticing unused server flag after EXP system overhaul
            for (Character member : allPartyMembers) {
                totalPartyLevel += member.getLevel();
                expMembers.add(member);
            }
        }

        int membersSize = expMembers.size();
        float participationExp = partyDamage * expPerDmg;

        // thanks Crypter for reporting an insufficiency on party exp bonuses
        boolean hasPartySharers = membersSize > 1;
        float partyBonusMod = hasPartySharers ? 0.05f * membersSize : 0.0f;
        
        // 计算活跃成员加成倍率，仅当activeMemberBonus > 0且activeMembers >= 2时才有加成
        float activeMemberBonus = GameConfig.getServerFloat("party_active_member_exp_bonus");
        // 计算活跃成员数量加成：只有至2个活跃成员才能触发加成
        int activeMembersBonus = Math.max(0, activeMembers - 1);
        // 当activeMemberBonus <= 0或activeMembersBonus == 0时，activeExp应为0
        float activeExp = (activeMemberBonus > 0 && activeMembersBonus > 0) ? participationExp * activeMemberBonus * activeMembersBonus : 0.0f;
        
        // 检查是否启用活跃成员强制模式和动态加成模式
        boolean enforcementEnabled = GameConfig.getServerBoolean("party_active_member_enforcement_enabled");
        boolean dynamicBonusEnabled = GameConfig.getServerBoolean("party_active_member_dynamic_bonus_enabled");
        
        // 分离活跃成员和不活跃成员
        List<Character> activeMembersList = new LinkedList<>();
        List<Character> inactiveMembersList = new LinkedList<>();
        
        for (Character mc : expMembers) {
            long lastDamageTime = mc.getLastMonsterDamageTime();
            boolean isActiveMember = lastDamageTime > 0 && (currentTime - lastDamageTime <= timeoutMillis);
            
            if (isActiveMember) {
                activeMembersList.add(mc);
            } else {
                inactiveMembersList.add(mc);
            }
        }
        
        // 处理enforcementEnabled模式：不活跃成员不获得经验，但经验补给活跃成员
        if (enforcementEnabled) {
            if (!inactiveMembersList.isEmpty() && !activeMembersList.isEmpty()) {
                // 计算不活跃成员本应获得的经验总和
                float inactiveMembersExp = 0.0f;
                for (Character inactiveMember : inactiveMembersList) {
                    float memberExp = (GameConfig.getServerFloat("exp_split_common_mod") * inactiveMember.getLevel()) / totalPartyLevel;
                    if (inactiveMember == participationMvp) {
                        memberExp += GameConfig.getServerFloat("exp_split_mvp_mod");
                    }
                    memberExp *= participationExp;
                    float bonusExp = partyBonusMod * memberExp;
                    inactiveMembersExp += (memberExp + bonusExp);
                }
                
                // 不活跃成员的经验补给活跃成员，简化为直接一次性添加
                activeExp += inactiveMembersExp;
            }
            
            // 需要重新计算partyBonusMod基于活跃成员数量和活跃加成倍率
            boolean hasActiveMemberSharers = activeMembersList.size() > 1;
            float activePartyBonusMod = hasActiveMemberSharers ? activeMemberBonus * activeMembersList.size() : 0.0f;
            
            // 仅分配给活跃成员
            for (Character mc : activeMembersList) {
                distributePlayerExperience(mc, participationExp, activePartyBonusMod, totalPartyLevel, mc == participationMvp, isWhiteExpGain(mc, personalRatio, sdevRatio), hasActiveMemberSharers, activeExp, true);
                giveFamilyRep(mc.getFamilyEntry());
            }
        } else if (dynamicBonusEnabled && !activeMembersList.isEmpty() && !inactiveMembersList.isEmpty() && activeMemberBonus > 0) {
            // 动态加成模式：所有成员都获得基础经验
            // 活跃成员获得activeMemberBonus的奖励经验
            // 挿機成员应扣除activeMemberBonus比例的经验
            float totalInactiveExp = 0.0f;
            for (Character inactiveMember : inactiveMembersList) {
                float memberExp = (GameConfig.getServerFloat("exp_split_common_mod") * inactiveMember.getLevel()) / totalPartyLevel;
                if (inactiveMember == participationMvp) {
                    memberExp += GameConfig.getServerFloat("exp_split_mvp_mod");
                }
                memberExp *= participationExp;
                totalInactiveExp += memberExp;  // 只计基础经验，不计组队奖励
            }
            
                        
            // 不活跃成员处理逻辑：基础经验扣除activeMemberBonus * totalInactiveExp / inactiveMemberCount后分离
            float penaltyPerInactiveMember = activeMemberBonus * totalInactiveExp / inactiveMembersList.size();
            for (Character inactiveMember : inactiveMembersList) {
                float memberExp = (GameConfig.getServerFloat("exp_split_common_mod") * inactiveMember.getLevel()) / totalPartyLevel;
                if (inactiveMember == participationMvp) {
                    memberExp += GameConfig.getServerFloat("exp_split_mvp_mod");
                }
                memberExp *= participationExp;
                float bonusExp = partyBonusMod * memberExp;
                // 扣除惩罚经验
                bonusExp -= penaltyPerInactiveMember;
                distributePlayerExperience(inactiveMember, participationExp, partyBonusMod, totalPartyLevel, inactiveMember == participationMvp, isWhiteExpGain(inactiveMember, personalRatio, sdevRatio), hasPartySharers, 0.0f, false);
                giveFamilyRep(inactiveMember.getFamilyEntry());
            }
            
            // 活跃成员获得基础经验 * activeMemberBonus的奖励经验
            float bonusPerActiveMember = activeMemberBonus * totalInactiveExp / activeMembersList.size();
            for (Character activeMember : activeMembersList) {
                distributePlayerExperience(activeMember, participationExp, partyBonusMod, totalPartyLevel, activeMember == participationMvp, isWhiteExpGain(activeMember, personalRatio, sdevRatio), hasPartySharers, activeExp + bonusPerActiveMember, true);
                giveFamilyRep(activeMember.getFamilyEntry());
            }
        } else {
            // 正常模式：所有成员正常获得经验，活跃成员额外获得activeExp
            for (Character mc : expMembers) {
                long lastDamageTime = mc.getLastMonsterDamageTime();
                boolean isActiveMember = lastDamageTime > 0 && (currentTime - lastDamageTime <= timeoutMillis);
                
                distributePlayerExperience(mc, participationExp, partyBonusMod, totalPartyLevel, mc == participationMvp, isWhiteExpGain(mc, personalRatio, sdevRatio), hasPartySharers, activeExp, isActiveMember);
                giveFamilyRep(mc.getFamilyEntry());
            }
        }
    }

    private void distributeExperience(int killerId) {
        if (isAlive()) {
            return;
        }

        Map<Party, Map<Character, Long>> partyExpDist = new HashMap<>();
        Map<Character, Long> soloExpDist = new HashMap<>();

        Map<Integer, Character> mapPlayers = map.getMapAllPlayers();

        int totalEntries = 0;   // counts "participant parties", players who no longer are available in the map is an "independent party"
        for (Entry<Integer, AtomicLong> e : takenDamage.entrySet()) {
            Character chr = mapPlayers.get(e.getKey());
            if (chr != null) {
                long damage = e.getValue().longValue();

                Party p = chr.getParty();
                if (p != null) {
                    Map<Character, Long> partyParticipation = partyExpDist.get(p);
                    if (partyParticipation == null) {
                        partyParticipation = new HashMap<>(6);
                        partyExpDist.put(p, partyParticipation);

                        totalEntries += 1;
                    }

                    partyParticipation.put(chr, damage);
                } else {
                    soloExpDist.put(chr, damage);
                    totalEntries += 1;
                }
            } else {
                totalEntries += 1;
            }
        }

        long totalDamage = maxHpPlusHeal.get();
        int mobExp = getExp();
        float expPerDmg = ((float) mobExp) / totalDamage;

        Map<Integer, Float> personalRatio = new HashMap<>();
        List<Float> entryExpRatio = new LinkedList<>();
        for (Entry<Character, Long> e : soloExpDist.entrySet()) {
            float ratio = ((float) e.getValue()) / totalDamage;

            personalRatio.put(e.getKey().getId(), ratio);
            entryExpRatio.add(ratio);
        }

        for (Map<Character, Long> m : partyExpDist.values()) {
            float ratio = 0.0f;
            for (Entry<Character, Long> e : m.entrySet()) {
                float chrRatio = ((float) e.getValue()) / totalDamage;

                personalRatio.put(e.getKey().getId(), chrRatio);
                ratio += chrRatio;
            }

            entryExpRatio.add(ratio);
        }

        double sdevRatio = calcExperienceStandDevThreshold(entryExpRatio, totalEntries);

        // GMS-like player and party split calculations found thanks to Russt, KaidaTan, Dusk, AyumiLove - src: https://ayumilovemaple.wordpress.com/maplestory_calculator_formula/
        Set<Character> underleveled = new HashSet<>();
        for (Entry<Character, Long> chrParticipation : soloExpDist.entrySet()) {
            float exp = chrParticipation.getValue() * expPerDmg;
            Character chr = chrParticipation.getKey();

            distributePlayerExperience(chr, exp, 0.0f, chr.getLevel(), true, isWhiteExpGain(chr, personalRatio, sdevRatio), false, 0.0f, false);
        }

        for (Map<Character, Long> partyParticipation : partyExpDist.values()) {
            distributePartyExperience(partyParticipation, expPerDmg, underleveled, personalRatio, sdevRatio);
        }

        EventInstanceManager eim = getMap().getEventInstance();
        if (eim != null) {
            Character chr = mapPlayers.get(killerId);
            if (chr != null) {
                eim.monsterKilled(chr, this);
            }
        }

        for (Character mc : underleveled) {
            mc.showUnderLeveledInfo(this);
        }

    }

    private float getStatusExpMultiplier(Character attacker, boolean hasPartySharers) {
        float multiplier = 1.0f;

        // thanks Prophecy & Aika for finding out Holy Symbol not being applied on party bonuses
        Integer holySymbol = attacker.getBuffedValue(BuffStat.HOLY_SYMBOL);
        if (holySymbol != null) {
            if (GameConfig.getServerBoolean("use_full_holy_symbol")) { // thanks Mordred, xinyifly, AyumiLove, andy33 for noticing HS hands out 20% of its potential on less than 3 players
                multiplier *= (float) (1.0 + (holySymbol.doubleValue() / 100.0));
            } else {
                multiplier *= (float) (1.0 + (holySymbol.doubleValue() / (hasPartySharers ? 100.0 : 500.0)));
            }
        }

        statiLock.lock();
        try {
            MonsterStatusEffect mse = stati.get(MonsterStatus.SHOWDOWN);
            if (mse != null) {
                multiplier *= (float) (1.0 + (mse.getStati().get(MonsterStatus.SHOWDOWN).doubleValue() / 100.0));
            }
        } finally {
            statiLock.unlock();
        }

        return multiplier;
    }

    private static int expValueToInteger(double exp) {
        if (exp > Integer.MAX_VALUE) {
            exp = Integer.MAX_VALUE;
        } else if (exp < Integer.MIN_VALUE) {
            exp = Integer.MIN_VALUE;
        }

        return (int) Math.round(exp);    // operations on float point are not point-precise... thanks IxianMace for noticing -1 EXP gains
    }

    private void giveExpToCharacter(Character attacker, Float personalExp, Float partyExp, boolean white, boolean hasPartySharers) {
        if (attacker.isAlive()) {
            if (personalExp != null) {
                personalExp *= getStatusExpMultiplier(attacker, hasPartySharers);
                personalExp *= (attacker.getExpRate() * attacker.getMobExpRate());
            } else {
                personalExp = 0.0f;
            }

            Integer expBonus = attacker.getBuffedValue(BuffStat.EXP_INCREASE);
            if (expBonus != null) {     // exp increase player buff found thanks to HighKey21
                personalExp += expBonus;
            }

            Integer expBuff = attacker.getBuffedValue(BuffStat.EXP_BUFF);
            if (expBuff != null) {
                personalExp *= 2;
            }

            if(attacker.isFamilyBuff()){
                personalExp *= attacker.getFamilyExp();
            }

            int _personalExp = expValueToInteger(personalExp); // assuming no negative xp here

            if (partyExp != null) {
                partyExp *= getStatusExpMultiplier(attacker, hasPartySharers);
                partyExp *= (attacker.getExpRate() * attacker.getMobExpRate());
                partyExp *= GameConfig.getServerFloat("party_bonus_exp_rate");
            } else {
                partyExp = 0.0f;
            }

            int _partyExp = expValueToInteger(partyExp);

            attacker.gainExp(_personalExp, _partyExp, true, false, white);
            attacker.increaseEquipExp(_personalExp);
            attacker.raiseQuestMobCount(getId());
        }
    }

    public List<MonsterDropEntry> retrieveRelevantDrops() {
        if (this.getStats().isFriendly()) {     // thanks Conrad for noticing friendly mobs not spawning loots after a recent update
            return MonsterInformationProvider.getInstance().retrieveEffectiveDrop(this.getId());
        }

        Map<Integer, Character> pchars = map.getMapAllPlayers();

        List<Character> lootChars = new LinkedList<>();
        for (Integer cid : takenDamage.keySet()) {
            Character chr = pchars.get(cid);
            if (chr != null && chr.isLoggedInWorld()) {
                lootChars.add(chr);
            }
        }

        return LootManager.retrieveRelevantDrops(this.getId(), lootChars);
    }

    public Character killBy(final Character killer) {
        distributeExperience(killer != null ? killer.getId() : 0);

        final Pair<Character, Boolean> lastController = aggroRemoveController();
        final List<Integer> toSpawn = this.getRevives();
        if (toSpawn != null) {
            final MapleMap reviveMap = map;
            if (toSpawn.contains(MobId.TRANSPARENT_ITEM) && reviveMap.getId() > 925000000 && reviveMap.getId() < 926000000) {
                reviveMap.broadcastMessage(PacketCreator.playSound("Dojang/clear"));
                reviveMap.broadcastMessage(PacketCreator.showEffect("dojang/end/clear"));
            }
            Pair<Integer, String> timeMob = reviveMap.getTimeMob();
            if (timeMob != null) {
                if (toSpawn.contains(timeMob.getLeft())) {
                    reviveMap.broadcastMessage(PacketCreator.serverNotice(6, timeMob.getRight()));
                }
            }

            if (!toSpawn.isEmpty()) {
                final EventInstanceManager eim = this.getMap().getEventInstance();
                final BossDamageSummary bossDamageSummary = isBoss() ? getBossDamageSummary(System.currentTimeMillis()) : null;

                TimerManager.getInstance().schedule(() -> {
                    Character controller = lastController.getLeft();
                    boolean aggro = lastController.getRight();

                    for (Integer mid : toSpawn) {
                        final Monster mob = LifeFactory.getMonster(mid);
                        if (mob != null) {
                            mob.setPosition(getPosition());
                        }
                        mob.setFh(getFh());
                        mob.setParentMobOid(getObjectId());

                        if (isDropsDisabled()) {
                            mob.disableDrops();
                        }
                        if (bossDamageSummary != null && mob.isBoss()) {
                            mob.inheritBossDamageSummary(bossDamageSummary);
                        }
                        reviveMap.spawnMonster(mob);

                        if (MobId.isDeadHorntailPart(mob.getId()) && reviveMap.isHorntailDefeated()) {
                            boolean htKilled = false;
                            Monster ht = reviveMap.getMonsterById(MobId.HORNTAIL);

                            if (ht != null) {
                                ht.lockMonster();
                                try {
                                    htKilled = ht.isAlive();
                                    ht.setHpZero();
                                } finally {
                                    ht.unlockMonster();
                                }

                                if (htKilled) {
                                    reviveMap.killMonster(ht, killer, true);
                                }
                            }

                            for (int i = MobId.DEAD_HORNTAIL_MAX; i >= MobId.DEAD_HORNTAIL_MIN; i--) {
                                reviveMap.killMonster(reviveMap.getMonsterById(i), killer, true);
                            }
                        } else if (controller != null) {
                            mob.aggroSwitchController(controller, aggro);
                        }

                        if (eim != null) {
                            eim.reviveMonster(mob);
                        }
                    }
                }, getAnimationTime("die1"));
            }
        } else {  // is this even necessary?
            log.warn("[严重丢失] {} 的 toSpawn 为 null", getName());
        }

        Character looter = map.getCharacterById(getHighestDamagerId());
        return looter != null ? looter : killer;
    }

    public void dropFromFriendlyMonster(long delay) {
        final Monster m = this;
        monsterItemDrop = TimerManager.getInstance().register(() -> {
            if (!m.isAlive()) {
                if (monsterItemDrop != null) {
                    monsterItemDrop.cancel(false);
                }

                return;
            }

            MapleMap map = m.getMap();
            List<Character> chrList = map.getAllPlayers();
            if (!chrList.isEmpty()) {
                Character chr = chrList.getFirst();

                EventInstanceManager eim = map.getEventInstance();
                if (eim != null) {
                    eim.friendlyItemDrop(m);
                }

                map.dropFromFriendlyMonster(chr, m);
            }
        }, delay, delay);
    }

    private void dispatchRaiseQuestMobCount() {
        Set<Integer> attackerChrids = takenDamage.keySet();
        if (!attackerChrids.isEmpty()) {
            Map<Integer, Character> mapChars = map.getMapPlayers();
            if (!mapChars.isEmpty()) {
                int mobid = getId();

                for (Integer chrid : attackerChrids) {
                    Character chr = mapChars.get(chrid);

                    if (chr != null && chr.isLoggedInWorld()) {
                        chr.raiseQuestMobCount(mobid);
                    }
                }
            }
        }
    }

    public void dispatchMonsterKilled(boolean hasKiller, int world, int channel, int mapid) {
        processMonsterKilled(hasKiller, world, channel, mapid);

        EventInstanceManager eim = getMap().getEventInstance();
        if (eim != null) {
            if (!this.getStats().isFriendly()) {
                eim.monsterKilled(this, hasKiller);
            } else {
                eim.friendlyKilled(this, hasKiller);
            }
        }
    }

    private synchronized void processMonsterKilled(boolean hasKiller, int world, int channel, int mapid) {
        if (!hasKiller) {    // 如果没有击杀者，玩家不会获得经验值，但应该增加任务计数
            dispatchRaiseQuestMobCount();
        }

        this.aggroClearDamages();
        this.dispatchClearSummons();
        cancelBossDamageTimeout();
        bossDamageRecords.clear();
        bossDamageEncounterStartTime = 0L;
        bossDamageLastTime = 0L;
        bossDamageEncounterMobId = 0;

        MonsterListener[] listenersList;
        statiLock.lock();
        try {
            listenersList = listeners.toArray(new MonsterListener[listeners.size()]);
        } finally {
            statiLock.unlock();
        }

        for (MonsterListener listener : listenersList) {
            listener.monsterKilled(getAnimationTime("die1"),hasKiller, world, channel, mapid);
        }

        statiLock.lock();
        try {
            stati.clear();
            alreadyBuffed.clear();
            listeners.clear();
        } finally {
            statiLock.unlock();
        }
    }

    private void dispatchMonsterDamaged(Character from, int trueDmg) {
        MonsterListener[] listenersList;
        statiLock.lock();
        try {
            listenersList = listeners.toArray(new MonsterListener[listeners.size()]);
        } finally {
            statiLock.unlock();
        }

        for (MonsterListener listener : listenersList) {
            listener.monsterDamaged(from, trueDmg);
        }
    }

    private void dispatchMonsterHealed(int trueHeal) {
        MonsterListener[] listenersList;
        statiLock.lock();
        try {
            listenersList = listeners.toArray(new MonsterListener[listeners.size()]);
        } finally {
            statiLock.unlock();
        }

        for (MonsterListener listener : listenersList) {
            listener.monsterHealed(trueHeal);
        }
    }

    private void giveFamilyRep(FamilyEntry entry) {
        if (entry != null) {
            int repGain = isBoss() ? GameConfig.getServerInt("family_rep_per_boss_kill") : GameConfig.getServerInt("family_rep_per_kill");
            if (getMaxHp() <= 1) {
                repGain = 0; //don't count trash mobs
            }
            entry.giveReputationToSenior(repGain, true);
        }
    }

    public int getHighestDamagerId() {
        int curId = 0;
        long curDmg = 0;

        for (Entry<Integer, AtomicLong> damage : takenDamage.entrySet()) {
            curId = damage.getValue().get() >= curDmg ? damage.getKey() : curId;
            curDmg = damage.getKey() == curId ? damage.getValue().get() : curDmg;
        }

        return curId;
    }

    public int getBossEncounterMobId() {
        return bossDamageEncounterMobId > 0 ? bossDamageEncounterMobId : getId();
    }

    public boolean willReviveBoss() {
        List<Integer> revives = getRevives();
        if (revives == null || revives.isEmpty()) {
            return false;
        }

        for (Integer mobId : revives) {
            Monster mob = LifeFactory.getMonster(mobId);
            if (mob != null && mob.isBoss()) {
                return true;
            }
        }
        return false;
    }

    public BossDamageSummary getBossDamageSummary(long endTime) {
        long startTime = bossDamageEncounterStartTime > 0 ? bossDamageEncounterStartTime : endTime;
        int encounterMobId = getBossEncounterMobId();
        List<BossDamageSnapshot> participants = bossDamageRecords.values().stream()
                .map(record -> record.snapshot(startTime, endTime))
                .sorted((left, right) -> Long.compare(right.getTotalDamage(), left.getTotalDamage()))
                .toList();
        long totalDamage = participants.stream().mapToLong(BossDamageSnapshot::getTotalDamage).sum();
        return new BossDamageSummary(encounterMobId, startTime, endTime, totalDamage, participants);
    }

    public void inheritBossDamageSummary(BossDamageSummary summary) {
        if (!isBoss() || summary == null) {
            return;
        }

        bossDamageRecords.clear();
        for (BossDamageSnapshot snapshot : summary.getParticipants()) {
            bossDamageRecords.put(snapshot.getPlayerId(), BossDamageRecord.fromSnapshot(snapshot));
        }
        bossDamageEncounterStartTime = summary.getStartTime();
        bossDamageLastTime = summary.getEndTime();
        bossDamageEncounterMobId = summary.getEncounterMobId();

        if (bossDamageLastTime > 0) {
            ensureBossDamageTimeoutTask();
        }
    }

    public boolean isAlive() {
        return this.hp.get() > 0;
    }

    public void addListener(MonsterListener listener) {
        statiLock.lock();
        try {
            listeners.add(listener);
        } finally {
            statiLock.unlock();
        }
    }

    public Character getController() {
        return controller.get();
    }

    private void setController(Character controller) {
        this.controller = new WeakReference<>(controller);
    }

    public boolean isControllerHasAggro() {
        return !fake && controllerHasAggro;
    }

    private void setControllerHasAggro(boolean controllerHasAggro) {
        if (!fake) {
            this.controllerHasAggro = controllerHasAggro;
        }
    }

    public boolean isControllerKnowsAboutAggro() {
        return !fake && controllerKnowsAboutAggro;
    }

    private void setControllerKnowsAboutAggro(boolean controllerKnowsAboutAggro) {
        if (!fake) {
            this.controllerKnowsAboutAggro = controllerKnowsAboutAggro;
        }
    }

    private void setControllerHasPuppet(boolean controllerHasPuppet) {
        this.controllerHasPuppet = controllerHasPuppet;
    }

    public Packet makeBossHPBarPacket() {
        return PacketCreator.showBossHP(getId(), getHp(), getMaxHp(), getTagColor(), getTagBgColor());
    }

    public boolean hasBossHPBar() {
        return isBoss() && getTagColor() > 0;
    }

    @Override
    public void sendSpawnData(Client client) {
        if (hp.get() <= 0) { // mustn't monsterLock this function
            return;
        }
        if (fake) {
            client.sendPacket(PacketCreator.spawnFakeMonster(this, 0));
        } else {
            client.sendPacket(PacketCreator.spawnMonster(this, false));
        }

        if (hasBossHPBar()) {
            client.announceBossHpBar(this, this.hashCode(), makeBossHPBarPacket());
        }
    }

    @Override
    public void sendDestroyData(Client client) {
        client.sendPacket(PacketCreator.killMonster(getObjectId(), false));
        client.sendPacket(PacketCreator.killMonster(getObjectId(), true));
    }

    @Override
    public MapObjectType getType() {
        return MapObjectType.MONSTER;
    }

    public boolean isMobile() {
        return stats.isMobile();
    }

    @Override
    public boolean isFacingLeft() {
        int fixedStance = stats.getFixedStance();    // thanks DimDiDima for noticing inconsistency on some AOE mobskills
        if (fixedStance != 0) {
            return Math.abs(fixedStance) % 2 == 1;
        }

        return super.isFacingLeft();
    }

    public ElementalEffectiveness getElementalEffectiveness(Element e) {
        statiLock.lock();
        try {
            if (stati.get(MonsterStatus.DOOM) != null) {
                return ElementalEffectiveness.NORMAL; // like blue snails
            }
        } finally {
            statiLock.unlock();
        }

        return getMonsterEffectiveness(e);
    }

    private ElementalEffectiveness getMonsterEffectiveness(Element e) {
        monsterLock.lock();
        try {
            return stats.getEffectiveness(e);
        } finally {
            monsterLock.unlock();
        }
    }

    private Character getActiveController() {
        Character chr = getController();

        if (chr != null && chr.isLoggedInWorld() && chr.getMap() == this.getMap()) {
            return chr;
        } else {
            return null;
        }
    }

    private void broadcastMonsterStatusMessage(Packet packet) {
        map.broadcastMessage(packet, getPosition());

        Character chrController = getActiveController();
        if (chrController != null && !chrController.isMapObjectVisible(Monster.this)) {
            chrController.sendPacket(packet);
        }
    }

    private int broadcastStatusEffect(final MonsterStatusEffect status) {
        int animationTime = status.getSkill().getAnimationTime();
        Packet packet = PacketCreator.applyMonsterStatus(getObjectId(), status, null);
        broadcastMonsterStatusMessage(packet);

        return animationTime;
    }

    public boolean applyStatus(Character from, final MonsterStatusEffect status, boolean poison, long duration) {
        return applyStatus(from, status, poison, duration, false);
    }

    public boolean applyStatus(Character from, final MonsterStatusEffect status, boolean poison, long duration, boolean venom) {
        switch (getMonsterEffectiveness(status.getSkill().getElement())) {
            case IMMUNE:
            case STRONG:
            case NEUTRAL:
                return false;
            case NORMAL:
            case WEAK:
                break;
            default: {
                log.warn("未知的元素效果：{}", getMonsterEffectiveness(status.getSkill().getElement()));
                return false;
            }
        }

        if (status.getSkill().getId() == FPMage.ELEMENT_COMPOSITION) { // fp compo
            ElementalEffectiveness effectiveness = getMonsterEffectiveness(Element.POISON);
            if (effectiveness == ElementalEffectiveness.IMMUNE || effectiveness == ElementalEffectiveness.STRONG) {
                return false;
            }
        } else if (status.getSkill().getId() == ILMage.ELEMENT_COMPOSITION) { // il compo
            ElementalEffectiveness effectiveness = getMonsterEffectiveness(Element.ICE);
            if (effectiveness == ElementalEffectiveness.IMMUNE || effectiveness == ElementalEffectiveness.STRONG) {
                return false;
            }
        } else if (status.getSkill().getId() == NightLord.VENOMOUS_STAR || status.getSkill().getId() == Shadower.VENOMOUS_STAB || status.getSkill().getId() == NightWalker.VENOM) {// venom
            if (getMonsterEffectiveness(Element.POISON) == ElementalEffectiveness.WEAK) {
                return false;
            }
        }
        if (poison && hp.get() <= 1) {
            return false;
        }

        final Map<MonsterStatus, Integer> statis = status.getStati();
        if (stats.isBoss()) {
            if (!(statis.containsKey(MonsterStatus.SPEED)
                    && statis.containsKey(MonsterStatus.NINJA_AMBUSH)
                    && statis.containsKey(MonsterStatus.WATK))) {
                return false;
            }
        }

        final Channel ch = map.getChannelServer();
        final int mapid = map.getId();
        if (!statis.isEmpty()) {
            statiLock.lock();
            try {
                for (MonsterStatus stat : statis.keySet()) {
                    final MonsterStatusEffect oldEffect = stati.get(stat);
                    if (oldEffect != null) {
                        oldEffect.removeActiveStatus(stat);
                        if (oldEffect.getStati().isEmpty()) {
                            MobStatusService service = (MobStatusService) map.getChannelServer().getServiceAccess(ChannelServices.MOB_STATUS);
                            service.interruptMobStatus(mapid, oldEffect);
                        }
                    }
                }
            } finally {
                statiLock.unlock();
            }
        }

        final Runnable cancelTask = () -> {
            if (isAlive()) {
                Packet packet = PacketCreator.cancelMonsterStatus(getObjectId(), status.getStati());
                broadcastMonsterStatusMessage(packet);
            }

            statiLock.lock();
            try {
                for (MonsterStatus stat : status.getStati().keySet()) {
                    stati.remove(stat);
                }
            } finally {
                statiLock.unlock();
            }

            setVenomMulti(0);
        };

        Runnable overtimeAction = null;
        int overtimeDelay = -1;

        int animationTime;
        if (poison) {
            int poisonLevel = from.getSkillLevel(status.getSkill());
            int poisonDamage = Math.min(Short.MAX_VALUE, (int) (getMaxHp() / (70.0 - poisonLevel) + 0.999));
            status.setValue(MonsterStatus.POISON, poisonDamage);
            animationTime = broadcastStatusEffect(status);

            overtimeAction = new DamageTask(poisonDamage, from, status, 0);
            overtimeDelay = 1000;
        } else if (venom) {
            if (from.getJob() == Job.NIGHTLORD || from.getJob() == Job.SHADOWER || from.getJob().isA(Job.NIGHTWALKER3)) {
                int poisonLevel, matk, jobid = from.getJob().getId();
                int skillid = (jobid == 412 ? NightLord.VENOMOUS_STAR : (jobid == 422 ? Shadower.VENOMOUS_STAB : NightWalker.VENOM));
                poisonLevel = from.getSkillLevel(SkillFactory.getSkill(skillid));
                if (poisonLevel <= 0) {
                    return false;
                }
                matk = SkillFactory.getSkill(skillid).getEffect(poisonLevel).getMatk();
                int luk = from.getLuk();
                int maxDmg = (int) Math.ceil(Math.min(Short.MAX_VALUE, 0.2 * luk * matk));
                int minDmg = (int) Math.ceil(Math.min(Short.MAX_VALUE, 0.1 * luk * matk));
                int gap = maxDmg - minDmg;
                if (gap == 0) {
                    gap = 1;
                }
                int poisonDamage = 0;
                for (int i = 0; i < getVenomMulti(); i++) {
                    poisonDamage += (Randomizer.nextInt(gap) + minDmg);
                }
                poisonDamage = Math.min(Short.MAX_VALUE, poisonDamage);
                status.setValue(MonsterStatus.VENOMOUS_WEAPON, poisonDamage);
                status.setValue(MonsterStatus.POISON, poisonDamage);
                animationTime = broadcastStatusEffect(status);

                overtimeAction = new DamageTask(poisonDamage, from, status, 0);
                overtimeDelay = 1000;
            } else {
                return false;
            }
            /*
        } else if (status.getSkill().getId() == Hermit.SHADOW_WEB || status.getSkill().getId() == NightWalker.SHADOW_WEB) { //Shadow Web
            int webDamage = (int) (getMaxHp() / 50.0 + 0.999);
            status.setValue(MonsterStatus.SHADOW_WEB, Integer.valueOf(webDamage));
            animationTime = broadcastStatusEffect(status);
            
            overtimeAction = new DamageTask(webDamage, from, status, 1);
            overtimeDelay = 3500;
            */
        } else if (status.getSkill().getId() == 4121004 || status.getSkill().getId() == 4221004) { // Ninja Ambush
            final Skill skill = SkillFactory.getSkill(status.getSkill().getId());
            final byte level = from.getSkillLevel(skill);
            final int damage = (int) ((from.getStr() + from.getLuk()) * ((3.7 * skill.getEffect(level).getDamage()) / 100));

            status.setValue(MonsterStatus.NINJA_AMBUSH, damage);
            animationTime = broadcastStatusEffect(status);

            overtimeAction = new DamageTask(damage, from, status, 2);
            overtimeDelay = 1000;
        } else {
            animationTime = broadcastStatusEffect(status);
        }

        statiLock.lock();
        try {
            for (MonsterStatus stat : status.getStati().keySet()) {
                stati.put(stat, status);
                alreadyBuffed.add(stat);
            }
        } finally {
            statiLock.unlock();
        }

        MobStatusService service = (MobStatusService) map.getChannelServer().getServiceAccess(ChannelServices.MOB_STATUS);
        service.registerMobStatus(mapid, status, cancelTask, duration + animationTime - 100, overtimeAction, overtimeDelay);
        return true;
    }

    public final void dispelSkill(final MobSkill skill) {
        List<MonsterStatus> toCancel = new ArrayList<>();
        for (Entry<MonsterStatus, MonsterStatusEffect> effects : stati.entrySet()) {
            MonsterStatusEffect mse = effects.getValue();
            if (mse.getMobSkill() != null && mse.getMobSkill().getType() == skill.getType()) { //not checking for level.
                toCancel.add(effects.getKey());
            }
        }
        for (MonsterStatus stat : toCancel) {
            debuffMobStat(stat);
        }
    }

    public void applyMonsterBuff(final Map<MonsterStatus, Integer> stats, final int x, long duration, MobSkill skill, final List<Integer> reflection) {
        final Runnable cancelTask = () -> {
            if (isAlive()) {
                Packet packet = PacketCreator.cancelMonsterStatus(getObjectId(), stats);
                broadcastMonsterStatusMessage(packet);

                statiLock.lock();
                try {
                    for (final MonsterStatus stat : stats.keySet()) {
                        stati.remove(stat);
                    }
                } finally {
                    statiLock.unlock();
                }
            }
        };
        final MonsterStatusEffect effect = new MonsterStatusEffect(stats, null, skill, true);
        Packet packet = PacketCreator.applyMonsterStatus(getObjectId(), effect, reflection);
        broadcastMonsterStatusMessage(packet);

        statiLock.lock();
        try {
            for (MonsterStatus stat : stats.keySet()) {
                stati.put(stat, effect);
                alreadyBuffed.add(stat);
            }
        } finally {
            statiLock.unlock();
        }

        MobStatusService service = (MobStatusService) map.getChannelServer().getServiceAccess(ChannelServices.MOB_STATUS);
        service.registerMobStatus(map.getId(), effect, cancelTask, duration);
    }

    public void refreshMobPosition() {
        resetMobPosition(getPosition());
    }

    public void resetMobPosition(Point newPoint) {
        aggroRemoveController();

        setPosition(newPoint);
        map.broadcastMessage(PacketCreator.moveMonster(this.getObjectId(), false, -1, 0, 0, 0, this.getPosition(), this.getIdleMovement(), AbstractAnimatedMapObject.IDLE_MOVEMENT_PACKET_LENGTH));
        map.moveMonster(this, this.getPosition());

        aggroUpdateController();
    }

    private void debuffMobStat(MonsterStatus stat) {
        MonsterStatusEffect oldEffect;
        statiLock.lock();
        try {
            oldEffect = stati.remove(stat);
        } finally {
            statiLock.unlock();
        }

        if (oldEffect != null) {
            Packet packet = PacketCreator.cancelMonsterStatus(getObjectId(), oldEffect.getStati());
            broadcastMonsterStatusMessage(packet);
        }
    }

    public void debuffMob(int skillid) {
        MonsterStatus[] statups = {MonsterStatus.WEAPON_ATTACK_UP, MonsterStatus.WEAPON_DEFENSE_UP, MonsterStatus.MAGIC_ATTACK_UP, MonsterStatus.MAGIC_DEFENSE_UP};
        statiLock.lock();
        try {
            if (skillid == Hermit.SHADOW_MESO) {
                debuffMobStat(statups[1]);
                debuffMobStat(statups[3]);
            } else if (skillid == Priest.DISPEL) {
                for (MonsterStatus ms : statups) {
                    debuffMobStat(ms);
                }
            } else {    // is a crash skill
                int i = (skillid == Crusader.ARMOR_CRASH ? 1 : (skillid == WhiteKnight.MAGIC_CRASH ? 2 : 0));
                debuffMobStat(statups[i]);

                if (GameConfig.getServerBoolean("use_anti_immunity_crash")) {
                    if (skillid == Crusader.ARMOR_CRASH) {
                        if (!isBuffed(MonsterStatus.WEAPON_REFLECT)) {
                            debuffMobStat(MonsterStatus.WEAPON_IMMUNITY);
                        }
                        if (!isBuffed(MonsterStatus.MAGIC_REFLECT)) {
                            debuffMobStat(MonsterStatus.MAGIC_IMMUNITY);
                        }
                    } else if (skillid == WhiteKnight.MAGIC_CRASH) {
                        if (!isBuffed(MonsterStatus.MAGIC_REFLECT)) {
                            debuffMobStat(MonsterStatus.MAGIC_IMMUNITY);
                        }
                    } else {
                        if (!isBuffed(MonsterStatus.WEAPON_REFLECT)) {
                            debuffMobStat(MonsterStatus.WEAPON_IMMUNITY);
                        }
                    }
                }
            }
        } finally {
            statiLock.unlock();
        }
    }

    public boolean isBuffed(MonsterStatus status) {
        statiLock.lock();
        try {
            return stati.containsKey(status);
        } finally {
            statiLock.unlock();
        }
    }

    public void setFake(boolean fake) {
        monsterLock.lock();
        try {
            this.fake = fake;
        } finally {
            monsterLock.unlock();
        }
    }

    public boolean isFake() {
        monsterLock.lock();
        try {
            return fake;
        } finally {
            monsterLock.unlock();
        }
    }

    public MonsterAggroCoordinator getMapAggroCoordinator() {
        return map.getAggroCoordinator();
    }

    public Set<MobSkillId> getSkills() {
        return stats.getSkills();
    }

    public boolean hasSkill(int skillId, int level) {
        return stats.hasSkill(skillId, level);
    }

    public boolean canUseSkill(MobSkill toUse, boolean apply) {
        if (toUse == null || isBuffed(MonsterStatus.SEAL_SKILL)) {
            return false;
        }

        if (isReflectSkill(toUse)) {
            if (this.isBuffed(MonsterStatus.WEAPON_REFLECT) || this.isBuffed(MonsterStatus.MAGIC_REFLECT)) {
                return false;
            }
        }

        monsterLock.lock();
        try {
            if (usedSkills.contains(toUse.getId())) {
                return false;
            }

            int mpCon = toUse.getMpCon();
            if (mp < mpCon) {
                return false;
            }
            
            /*
            if (!this.applyAnimationIfRoaming(-1, toUse)) {
                return false;
            }
            */

            if (apply) {
                this.usedSkill(toUse);
            }
        } finally {
            monsterLock.unlock();
        }

        return true;
    }

    private boolean isReflectSkill(MobSkill mobSkill) {
        return switch (mobSkill.getType()) {
            case PHYSICAL_COUNTER, MAGIC_COUNTER, PHYSICAL_AND_MAGIC_COUNTER -> true;
            default -> false;
        };
    }

    private void usedSkill(MobSkill skill) {
        final MobSkillId msId = skill.getId();
        monsterLock.lock();
        try {
            mp -= skill.getMpCon();

            this.usedSkills.add(msId);
        } finally {
            monsterLock.unlock();
        }

        final Monster mons = this;
        MapleMap mmap = mons.getMap();
        Runnable r = () -> mons.clearSkill(skill.getId());

        MobClearSkillService service = (MobClearSkillService) map.getChannelServer().getServiceAccess(ChannelServices.MOB_CLEAR_SKILL);
        service.registerMobClearSkillAction(mmap.getId(), r, skill.getCoolTime());
    }

    private void clearSkill(MobSkillId msId) {
        monsterLock.lock();
        try {
            usedSkills.remove(msId);
        } finally {
            monsterLock.unlock();
        }
    }

    public int canUseAttack(int attackPos, boolean isSkill) {
        monsterLock.lock();
        try {
            /*
            if (usedAttacks.contains(attackPos)) {
                return -1;
            }
            */

            Pair<Integer, Integer> attackInfo = MonsterInformationProvider.getInstance().getMobAttackInfo(this.getId(), attackPos);
            if (attackInfo == null) {
                return -1;
            }

            int mpCon = attackInfo.getLeft();
            if (mp < mpCon) {
                return -1;
            }
            
            /*
            if (!this.applyAnimationIfRoaming(attackPos, null)) {
                return -1;
            }
            */

            usedAttack(attackPos, mpCon, attackInfo.getRight());
            return 1;
        } finally {
            monsterLock.unlock();
        }
    }

    private void usedAttack(final int attackPos, int mpCon, int cooltime) {
        monsterLock.lock();
        try {
            mp -= mpCon;
            usedAttacks.add(attackPos);

            final Monster mons = this;
            MapleMap mmap = mons.getMap();
            Runnable r = () -> mons.clearAttack(attackPos);

            MobClearSkillService service = (MobClearSkillService) map.getChannelServer().getServiceAccess(ChannelServices.MOB_CLEAR_SKILL);
            service.registerMobClearSkillAction(mmap.getId(), r, cooltime);
        } finally {
            monsterLock.unlock();
        }
    }

    private void clearAttack(int attackPos) {
        monsterLock.lock();
        try {
            usedAttacks.remove(attackPos);
        } finally {
            monsterLock.unlock();
        }
    }

    public boolean hasAnySkill() {
        return this.stats.getNoSkills() > 0;
    }

    public MobSkillId getRandomSkill() {
        Set<MobSkillId> skills = stats.getSkills();
        if (skills.isEmpty()) {
            return null;
        }
        // There is no simple way of getting a random element from a Set. Have to make do with this.
        return skills.stream()
                .skip(Randomizer.nextInt(skills.size()))
                .findAny()
                .orElse(null);
    }

    public boolean isFirstAttack() {
        return this.stats.isFirstAttack();
    }

    public int getBuffToGive() {
        return this.stats.getBuffToGive();
    }

    private final class DamageTask implements Runnable {

        private final int dealDamage;
        private final Character chr;
        private final MonsterStatusEffect status;
        private final int type;
        private final MapleMap map;

        private DamageTask(int dealDamage, Character chr, MonsterStatusEffect status, int type) {
            this.dealDamage = dealDamage;
            this.chr = chr;
            this.status = status;
            this.type = type;
            this.map = chr.getMap();
        }

        @Override
        public void run() {
            int curHp = hp.get();
            if (curHp <= 1) {
                MobStatusService service = (MobStatusService) map.getChannelServer().getServiceAccess(ChannelServices.MOB_STATUS);
                service.interruptMobStatus(map.getId(), status);
                return;
            }

            int damage = dealDamage;
            if (damage >= curHp) {
                damage = curHp - 1;
                if (type == 1 || type == 2) {
                    MobStatusService service = (MobStatusService) map.getChannelServer().getServiceAccess(ChannelServices.MOB_STATUS);
                    service.interruptMobStatus(map.getId(), status);
                }
            }
            if (damage > 0) {
                lockMonster();
                try {
                    applyDamage(chr, damage, true, false, false);
                } finally {
                    unlockMonster();
                }

                if (type == 1) {
                    map.broadcastMessage(PacketCreator.damageMonster(getObjectId(), damage), getPosition());
                } else if (type == 2) {
                    if (damage < dealDamage) {    // ninja ambush (type 2) is already displaying DOT to the caster
                        map.broadcastMessage(PacketCreator.damageMonster(getObjectId(), damage), getPosition());
                    }
                }
            }
        }
    }

    public String getName() {
        return stats.getName();
    }

    public void addStolen(int itemId) {
        stolenItems.add(itemId);
    }

    public List<Integer> getStolen() {
        return stolenItems;
    }

    public void setTempEffectiveness(Element e, ElementalEffectiveness ee, long milli) {
        monsterLock.lock();
        try {
            tempEffectiveness.put(e, ee);
        } finally {
            monsterLock.unlock();
        }
        TimerManager.getInstance().schedule(() -> {
            monsterLock.lock();
            try {
                tempEffectiveness.remove(e);
            } finally {
                monsterLock.unlock();
            }
        }, milli);
    }

    public ElementalEffectiveness getTempEffectiveness(Element e) {
        monsterLock.lock();
        try {
            return tempEffectiveness.get(e);
        } finally {
            monsterLock.unlock();
        }
    }

    public Collection<MonsterStatus> alreadyBuffedStats() {
        statiLock.lock();
        try {
            return Collections.unmodifiableCollection(alreadyBuffed);
        } finally {
            statiLock.unlock();
        }
    }

    public BanishInfo getBanish() {
        return stats.getBanishInfo();
    }

    public void setBoss(boolean boss) {
        this.stats.setBoss(boss);
    }

    public int getDropPeriodTime() {
        return stats.getDropPeriod();
    }

    public int getPADamage() {
        return stats.getPADamage();
    }

    public Map<MonsterStatus, MonsterStatusEffect> getStati() {
        statiLock.lock();
        try {
            return new HashMap<>(stati);
        } finally {
            statiLock.unlock();
        }
    }

    public MonsterStatusEffect getStati(MonsterStatus ms) {
        statiLock.lock();
        try {
            return stati.get(ms);
        } finally {
            statiLock.unlock();
        }
    }

    // ---- one can always have fun trying these pieces of codes below in-game rofl ----

    public final ChangeableStats getChangedStats() {
        return ostats;
    }

    public final int getMobMaxHp() {
        if (ostats != null) {
            return ostats.hp;
        }
        return stats.getHp();
    }

    public final void setOverrideStats(final OverrideMonsterStats ostats) {
        this.ostats = new ChangeableStats(stats, ostats);
        this.hp.set(ostats.getHp());
        this.mp = ostats.getMp();
    }

    public final void changeLevel(final int newLevel) {
        changeLevel(newLevel, true);
    }

    public final void changeLevel(final int newLevel, boolean pqMob) {
        if (!stats.isChangeable()) {
            return;
        }
        this.ostats = new ChangeableStats(stats, newLevel, pqMob);
        this.hp.set(ostats.getHp());
        this.mp = ostats.getMp();
    }

    private float getDifficultyRate(final int difficulty) {
        return switch (difficulty) {
            case 6 -> (7.7f);
            case 5 -> (5.6f);
            case 4 -> (3.2f);
            case 3 -> (2.1f);
            case 2 -> (1.4f);
            default -> (1.0f);
        };

    }

    private void changeLevelByDifficulty(final int difficulty, boolean pqMob) {
        changeLevel((int) (this.getLevel() * getDifficultyRate(difficulty)), pqMob);
    }

    public final void changeDifficulty(final int difficulty, boolean pqMob) {
        changeLevelByDifficulty(difficulty, pqMob);
    }

    // ---------------------------------------------------------------------------------

    private boolean isPuppetInVicinity(Summon summon) {
        return summon.getPosition().distanceSq(this.getPosition()) < 177777;
    }

    public boolean isCharacterPuppetInVicinity(Character chr) {
        StatEffect mse = chr.getBuffEffect(BuffStat.PUPPET);
        if (mse != null) {
            Summon summon = chr.getSummonByKey(mse.getSourceId());

            // check whether mob is currently under a puppet's field of action or not
            if (summon != null) {
                return isPuppetInVicinity(summon);
            } else {
                map.getAggroCoordinator().removePuppetAggro(chr.getId());
            }
        }

        return false;
    }

    public boolean isLeadingPuppetInVicinity() {
        Character chrController = this.getActiveController();

        if (chrController != null) {
            return this.isCharacterPuppetInVicinity(chrController);
        }

        return false;
    }

    private Character getNextControllerCandidate() {
        int mincontrolled = Integer.MAX_VALUE;
        Character newController = null;

        int mincontrolleddead = Integer.MAX_VALUE;
        Character newControllerDead = null;

        Character newControllerWithPuppet = null;

        for (Character chr : getMap().getAllPlayers()) {
            if (!chr.isHidden()) {
                int ctrlMonsSize = chr.getNumControlledMonsters();

                if (isCharacterPuppetInVicinity(chr)) {
                    newControllerWithPuppet = chr;
                    break;
                } else if (chr.isAlive()) {
                    if (ctrlMonsSize < mincontrolled) {
                        mincontrolled = ctrlMonsSize;
                        newController = chr;
                    }
                } else {
                    if (ctrlMonsSize < mincontrolleddead) {
                        mincontrolleddead = ctrlMonsSize;
                        newControllerDead = chr;
                    }
                }
            }
        }

        if (newControllerWithPuppet != null) {
            return newControllerWithPuppet;
        } else if (newController != null) {
            return newController;
        } else {
            return newControllerDead;
        }
    }

    /**
     * Removes controllability status from the current controller of this mob.
     */
    public Pair<Character, Boolean> aggroRemoveController() {
        Character chrController;
        boolean hadAggro;

        aggroUpdateLock.lock();
        try {
            chrController = getActiveController();
            hadAggro = isControllerHasAggro();

            this.setController(null);
            this.setControllerHasAggro(false);
            this.setControllerKnowsAboutAggro(false);
        } finally {
            aggroUpdateLock.unlock();
        }

        if (chrController != null) { // this can/should only happen when a hidden gm attacks the monster
            if (!this.isFake()) {
                chrController.sendPacket(PacketCreator.stopControllingMonster(this.getObjectId()));
            }
            chrController.stopControllingMonster(this);
        }

        return new Pair<>(chrController, hadAggro);
    }

    /**
     * Pass over the mob controllability and updates aggro status on the new
     * player controller.
     */
    public void aggroSwitchController(Character newController, boolean immediateAggro) {
        if (aggroUpdateLock.tryLock()) {
            try {
                Character prevController = getController();
                if (prevController == newController) {
                    return;
                }

                aggroRemoveController();
                if (!(newController != null && newController.isLoggedInWorld() && newController.getMap() == this.getMap())) {
                    return;
                }

                this.setController(newController);
                this.setControllerHasAggro(immediateAggro);
                this.setControllerKnowsAboutAggro(false);
                this.setControllerHasPuppet(false);
            } finally {
                aggroUpdateLock.unlock();
            }

            this.aggroUpdatePuppetVisibility();
            aggroMonsterControl(newController.getClient(), this, immediateAggro);
            newController.controlMonster(this);
        }
    }

    public void aggroAddPuppet(Character player) {
        MonsterAggroCoordinator mmac = map.getAggroCoordinator();
        mmac.addPuppetAggro(player);

        aggroUpdatePuppetController(player);

        if (this.isControllerHasAggro()) {
            this.aggroUpdatePuppetVisibility();
        }
    }

    public void aggroRemovePuppet(Character player) {
        MonsterAggroCoordinator mmac = map.getAggroCoordinator();
        mmac.removePuppetAggro(player.getId());

        aggroUpdatePuppetController(null);

        if (this.isControllerHasAggro()) {
            this.aggroUpdatePuppetVisibility();
        }
    }

    /**
     * Automagically finds a new controller for the given monster from the chars
     * on the map it is from...
     */
    public void aggroUpdateController() {
        Character chrController = this.getActiveController();
        if (chrController != null && chrController.isAlive()) {
            return;
        }

        Character newController = getNextControllerCandidate();
        if (newController == null) {    // was a new controller found? (if not no one is on the map)
            return;
        }

        this.aggroSwitchController(newController, false);
    }

    /**
     * Finds a new controller for the given monster from the chars with deployed
     * puppet nearby on the map it is from...
     */
    private void aggroUpdatePuppetController(Character newController) {
        Character chrController = this.getActiveController();
        boolean updateController = false;

        if (chrController != null && chrController.isAlive()) {
            if (isCharacterPuppetInVicinity(chrController)) {
                return;
            }
        } else {
            updateController = true;
        }

        if (newController == null || !isCharacterPuppetInVicinity(newController)) {
            MonsterAggroCoordinator mmac = map.getAggroCoordinator();

            List<Integer> puppetOwners = mmac.getPuppetAggroList();
            List<Integer> toRemovePuppets = new LinkedList<>();

            for (Integer cid : puppetOwners) {
                Character chr = map.getCharacterById(cid);

                if (chr != null) {
                    if (isCharacterPuppetInVicinity(chr)) {
                        newController = chr;
                        break;
                    }
                } else {
                    toRemovePuppets.add(cid);
                }
            }

            for (Integer cid : toRemovePuppets) {
                mmac.removePuppetAggro(cid);
            }

            if (newController == null) {    // was a new controller found? (if not there's no puppet nearby)
                if (updateController) {
                    aggroUpdateController();
                }

                return;
            }
        } else if (chrController == newController) {
            this.aggroUpdatePuppetVisibility();
        }

        this.aggroSwitchController(newController, this.isControllerHasAggro());
    }

    /**
     * Ensures controllability removal of the current player controller, and
     * fetches for any player on the map to start controlling in place.
     */
    public void aggroRedirectController() {
        this.aggroRemoveController();   // don't care if new controller not found, at least remove current controller
        this.aggroUpdateController();
    }

    /**
     * Returns the current aggro status on the specified player, or null if the
     * specified player is currently not this mob's controller.
     */
    public Boolean aggroMoveLifeUpdate(Character player) {
        Character chrController = getController();
        if (chrController != null && player.getId() == chrController.getId()) {
            boolean aggro = this.isControllerHasAggro();
            if (aggro) {
                this.setControllerKnowsAboutAggro(true);
            }

            return aggro;
        } else {
            return null;
        }
    }

    /**
     * Refreshes auto aggro for the player passed as parameter, does nothing if
     * there is already an active controller for this mob.
     */
    public void aggroAutoAggroUpdate(Character player) {
        Character chrController = this.getActiveController();

        if (chrController == null) {
            this.aggroSwitchController(player, true);
        } else if (chrController.getId() == player.getId()) {
            this.setControllerHasAggro(true);
            if (!GameConfig.getServerBoolean("use_auto_aggro_nearby")) {   // thanks Lichtmager for noticing autoaggro not updating the player properly
                aggroMonsterControl(player.getClient(), this, true);
            }
        }
    }

    /**
     * Applied damage input for this mob, enough damage taken implies an aggro
     * target update for the attacker shortly.
     */
    public void aggroMonsterDamage(Character attacker, int damage) {
        MonsterAggroCoordinator mmac = this.getMapAggroCoordinator();
        mmac.addAggroDamage(this, attacker.getId(), damage);

        Character chrController = this.getController();    // aggro based on DPS rather than first-come-first-served, now live after suggestions thanks to MedicOP, Thora, Vcoc
        if (chrController != attacker) {
            if (this.getMapAggroCoordinator().isLeadingCharacterAggro(this, attacker)) {
                this.aggroSwitchController(attacker, true);
            } else {
                this.setControllerHasAggro(true);
                this.aggroUpdatePuppetVisibility();
            }
            
            /*
            For some reason, some mobs loses aggro on controllers if other players also attacks them.
            Maybe Nexon intended to interchange controllers at every attack...
            
            else if (chrController != null) {
                chrController.sendPacket(PacketCreator.stopControllingMonster(this.getObjectId()));
                aggroMonsterControl(chrController.getClient(), this, true);
            }
            */
        } else {
            this.setControllerHasAggro(true);
            this.aggroUpdatePuppetVisibility();
        }
    }

    private static void aggroMonsterControl(Client c, Monster mob, boolean immediateAggro) {
        c.sendPacket(PacketCreator.controlMonster(mob, false, immediateAggro));
    }

    private void aggroRefreshPuppetVisibility(Character chrController, Summon puppet) {
        // lame patch for client to redirect all aggro to the puppet

        List<Monster> puppetControlled = new LinkedList<>();
        for (Monster mob : chrController.getControlledMonsters()) {
            if (mob.isPuppetInVicinity(puppet)) {
                puppetControlled.add(mob);
            }
        }

        for (Monster mob : puppetControlled) {
            chrController.sendPacket(PacketCreator.stopControllingMonster(mob.getObjectId()));
        }
        chrController.sendPacket(PacketCreator.removeSummon(puppet, false));

        Client c = chrController.getClient();
        for (Monster mob : puppetControlled) { // thanks BHB for noticing puppets disrupting mobstatuses for bowmans
            aggroMonsterControl(c, mob, mob.isControllerKnowsAboutAggro());
        }
        chrController.sendPacket(PacketCreator.spawnSummon(puppet, false));
    }

    public void aggroUpdatePuppetVisibility() {
        if (!availablePuppetUpdate) {
            return;
        }

        availablePuppetUpdate = false;
        Runnable r = () -> {
            try {
                Character chrController = Monster.this.getActiveController();
                if (chrController == null) {
                    return;
                }

                StatEffect puppetEffect = chrController.getBuffEffect(BuffStat.PUPPET);
                if (puppetEffect != null) {
                    Summon puppet = chrController.getSummonByKey(puppetEffect.getSourceId());

                    if (puppet != null && isPuppetInVicinity(puppet)) {
                        controllerHasPuppet = true;
                        aggroRefreshPuppetVisibility(chrController, puppet);
                        return;
                    }
                }

                if (controllerHasPuppet) {
                    controllerHasPuppet = false;

                    chrController.sendPacket(PacketCreator.stopControllingMonster(Monster.this.getObjectId()));
                    aggroMonsterControl(chrController.getClient(), Monster.this, Monster.this.isControllerHasAggro());
                }
            } finally {
                availablePuppetUpdate = true;
            }
        };

        // had to schedule this since mob wouldn't stick to puppet aggro who knows why
        OverallService service = (OverallService) this.getMap().getChannelServer().getServiceAccess(ChannelServices.OVERALL);
        service.registerOverallAction(this.getMap().getId(), r, GameConfig.getServerLong("update_interval"));
    }

    /**
     * Clears all applied damage input for this mob, doesn't refresh target
     * aggro.
     */
    public void aggroClearDamages() {
        this.getMapAggroCoordinator().removeAggroEntries(this);
    }

    /**
     * Clears this mob aggro on the current controller.
     */
    public void aggroResetAggro() {
        aggroUpdateLock.lock();
        try {
            this.setControllerHasAggro(false);
            this.setControllerKnowsAboutAggro(false);
        } finally {
            aggroUpdateLock.unlock();
        }
    }

    public final int getRemoveAfter() {
        return stats.removeAfter();
    }

    private static class BossDamageRecord {
        private final int playerId;
        private String playerName;
        private long totalDamage;
        private int hitCount;
        private int directHitCount;
        private int summonHitCount;
        private int maxSingleDamage;
        private int lastSkillId;
        private int lastSkillTargetCount;
        private long firstDamageTime;
        private long lastDamageTime;

        private BossDamageRecord(Character from) {
            this.playerId = from.getId();
            this.playerName = from.getName();
        }

        private static BossDamageRecord fromSnapshot(BossDamageSnapshot snapshot) {
            BossDamageRecord record = new BossDamageRecord(snapshot.getPlayerId(), snapshot.getPlayerName());
            record.totalDamage = snapshot.getTotalDamage();
            record.hitCount = snapshot.getHitCount();
            record.directHitCount = snapshot.getDirectHitCount();
            record.summonHitCount = snapshot.getSummonHitCount();
            record.maxSingleDamage = snapshot.getMaxSingleDamage();
            record.lastSkillId = snapshot.getLastSkillId();
            record.lastSkillTargetCount = snapshot.getLastSkillTargetCount();
            record.firstDamageTime = snapshot.getFirstDamageTime();
            record.lastDamageTime = snapshot.getLastDamageTime();
            return record;
        }

        private BossDamageRecord(int playerId, String playerName) {
            this.playerId = playerId;
            this.playerName = playerName;
        }

        private void addDamage(Character from, int damage, boolean isFromSummon, long time, int skillId, int skillTargetCount) {
            playerName = from.getName();
            totalDamage += damage;
            hitCount++;
            if (isFromSummon) {
                summonHitCount++;
            } else {
                directHitCount++;
            }
            maxSingleDamage = Math.max(maxSingleDamage, damage);
            lastSkillId = skillId;
            lastSkillTargetCount = skillTargetCount;
            if (firstDamageTime <= 0) {
                firstDamageTime = time;
            }
            lastDamageTime = time;
        }

        private BossDamageSnapshot snapshot(long encounterStartTime, long encounterEndTime) {
            long encounterDuration = Math.max(MIN_BOSS_DPS_DURATION_MILLIS, encounterEndTime - encounterStartTime);
            long activeDuration = Math.max(MIN_BOSS_DPS_DURATION_MILLIS, lastDamageTime - firstDamageTime);
            return new BossDamageSnapshot(
                    playerId,
                    playerName,
                    totalDamage,
                    hitCount,
                    directHitCount,
                    summonHitCount,
                    maxSingleDamage,
                    lastSkillId,
                    lastSkillTargetCount,
                    firstDamageTime,
                    lastDamageTime,
                    totalDamage * 1000D / encounterDuration,
                    totalDamage * 1000D / activeDuration);
        }
    }

    @Getter
    public static class BossDamageSnapshot {
        private final int playerId;
        private final String playerName;
        private final long totalDamage;
        private final int hitCount;
        private final int directHitCount;
        private final int summonHitCount;
        private final int maxSingleDamage;
        private final int lastSkillId;
        private final int lastSkillTargetCount;
        private final long firstDamageTime;
        private final long lastDamageTime;
        private final double encounterAvgDps;
        private final double activeAvgDps;

        private BossDamageSnapshot(int playerId, String playerName, long totalDamage, int hitCount,
                                   int directHitCount, int summonHitCount, int maxSingleDamage,
                                   int lastSkillId, int lastSkillTargetCount, long firstDamageTime,
                                   long lastDamageTime, double encounterAvgDps, double activeAvgDps) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.totalDamage = totalDamage;
            this.hitCount = hitCount;
            this.directHitCount = directHitCount;
            this.summonHitCount = summonHitCount;
            this.maxSingleDamage = maxSingleDamage;
            this.lastSkillId = lastSkillId;
            this.lastSkillTargetCount = lastSkillTargetCount;
            this.firstDamageTime = firstDamageTime;
            this.lastDamageTime = lastDamageTime;
            this.encounterAvgDps = encounterAvgDps;
            this.activeAvgDps = activeAvgDps;
        }
    }

    @Getter
    public static class BossDamageSummary {
        private final int encounterMobId;
        private final long startTime;
        private final long endTime;
        private final long totalDamage;
        private final List<BossDamageSnapshot> participants;

        private BossDamageSummary(int encounterMobId, long startTime, long endTime, long totalDamage,
                                  List<BossDamageSnapshot> participants) {
            this.encounterMobId = encounterMobId;
            this.startTime = startTime;
            this.endTime = endTime;
            this.totalDamage = totalDamage;
            this.participants = participants;
        }

        public long getDurationMillis() {
            return Math.max(1L, endTime - startTime);
        }

        public double getAverageDps() {
            long dpsDuration = Math.max(MIN_BOSS_DPS_DURATION_MILLIS, getDurationMillis());
            return totalDamage * 1000D / dpsDuration;
        }
    }

    public void dispose() {
        if (monsterItemDrop != null) {
            monsterItemDrop.cancel(false);
        }
        cancelBossDamageTimeout();

        this.getMap().dismissRemoveAfter(this);
    }

}
