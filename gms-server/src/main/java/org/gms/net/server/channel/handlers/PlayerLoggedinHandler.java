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
package org.gms.net.server.channel.handlers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;
import org.gms.client.BuddyList;
import org.gms.client.BuddylistEntry;
import org.gms.client.Character;
import org.gms.client.CharacterNameAndId;
import org.gms.client.Client;
import org.gms.client.charhelper.TransitionSession;
import org.gms.client.charhelper.LoginCoordinator;
import org.gms.client.Disease;
import org.gms.client.Family;
import org.gms.client.FamilyEntry;
import org.gms.client.Mount;
import org.gms.client.SkillFactory;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.Pet;
import org.gms.client.keybind.KeyBinding;
import org.gms.config.GameConfig;
import org.gms.constants.game.GameConstants;
import org.gms.dao.entity.DueypackagesDO;
import org.gms.dao.mapper.DueypackagesMapper;
import org.gms.manager.ServerManager;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;
import org.gms.net.server.PlayerBuffValueHolder;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.channel.CharacterIdChannelPair;
import org.gms.net.server.coordinator.session.Hwid;
import org.gms.net.server.coordinator.session.SessionCoordinator;
import org.gms.net.server.coordinator.world.EventRecallCoordinator;
import org.gms.net.server.guild.Alliance;
import org.gms.net.server.guild.Guild;
import org.gms.net.server.guild.GuildPackets;
import org.gms.net.server.world.PartyCharacter;
import org.gms.net.server.world.PartyOperation;
import org.gms.net.server.world.World;
import org.gms.server.logging.AuditContext;
import org.gms.service.ConfigService;
import org.gms.service.HpMpAlertService;
import org.gms.util.I18nUtil;
import org.gms.util.SpringContextUtil;
import org.gms.util.packets.WeddingPackets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.life.MobSkill;
import org.gms.service.NoteService;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;

import java.util.*;
import java.util.Map.Entry;

public final class PlayerLoggedinHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(PlayerLoggedinHandler.class);
    private static final Set<Integer> attemptingLoginAccounts = new HashSet<>();

    private final NoteService noteService;

    private static final HpMpAlertService hpMpAlertService = ServerManager.getApplicationContext().getBean(HpMpAlertService.class);
    private static final ConfigService configService = ServerManager.getApplicationContext().getBean(ConfigService.class);

    public PlayerLoggedinHandler(NoteService noteService) {
        this.noteService = noteService;
    }

    private boolean tryAcquireAccount(int accId) {
        synchronized (attemptingLoginAccounts) {
            if (attemptingLoginAccounts.contains(accId)) {
                return false;
            }
            attemptingLoginAccounts.add(accId);
            return true;
        }
    }

    private void releaseAccount(int accId) {
        synchronized (attemptingLoginAccounts) {
            attemptingLoginAccounts.remove(accId);
        }
    }

    @Override
    public final boolean validateState(Client c) {
        return !c.isLoggedIn();
    }

    // ============================================================
    //  Orchestrator (瘦身后 ~55 行)
    // ============================================================

    @Override
    public final void handlePacket(InPacket p, Client c) {
        final int cid = p.readInt();
        final Server server = Server.getInstance();

        if (!c.tryacquireClient()) {
            c.sendPacket(PacketCreator.getAfterLoginError(10));
            return;
        }

        try {
            // ① World / Channel 解析
            Channel cserv = resolveWorldAndChannel(server, c);
            if (cserv == null) return;
            World wserv = server.getWorld(c.getWorld());

            // ② 角色加载 + Transition 消费
            boolean[] newcomerHolder = new boolean[1];
            Character player = resolvePlayer(wserv, c, cid, server, newcomerHolder);
            if (player == null) return;
            boolean newcomer = newcomerHolder[0];

            // ③ 登录状态校验
            if (!checkLoginState(c)) return;

            // ④ 非新角色时的状态回填
            if (!newcomer) {
                applyReturningState(c, player);
            }

            // ⑤ 频道 / 世界登记
            enterWorld(cserv, wserv, player);

            // ⑥ 发送初始包（buffs / charInfo / keymap 等）
            Map<Disease, Pair<Long, MobSkill>> diseases = sendInitialInfo(c, player, server, cid);

            // ⑦ 社交模块
            setupSocial(wserv, c, player, newcomer);

            // ⑧ 战斗 + 宠物 + 技能
            setupCombat(wserv, c, player, newcomer);

            // ⑨ 收尾：timers / rates / 通知 / 事件
            finalizeLogin(wserv, c, player, newcomer, cid, diseases);

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            c.releaseClient();
        }
    }

    // ============================================================
    //  Step 1: World / Channel 解析
    // ============================================================

    /**
     * 解析 World 和 Channel 对象。失败时自动断开客户端连接。
     *
     * @return Channel 对象；解析失败返回 {@code null}
     */
    private Channel resolveWorldAndChannel(Server server, Client c) {
        World wserv = server.getWorld(c.getWorld());
        if (wserv == null) {
            c.disconnect(true, false);
            return null;
        }

        Channel cserv = wserv.getChannel(c.getChannel());
        if (cserv == null) {
            c.setChannel(1);
            cserv = wserv.getChannel(c.getChannel());
            if (cserv == null) {
                c.disconnect(true, false);
                return null;
            }
        }
        return cserv;
    }

    // ============================================================
    //  Step 2: 角色加载 + Transition 消费
    // ============================================================

    /**
     * 加载角色数据，同时完成 HWID 获取和 TransitionSession 原子消费。
     *
     * @param wserv         World 对象
     * @param c             频道 Client
     * @param cid           角色 ID
     * @param server        Server 单例
     * @param newcomerHolder [out] newcomerHolder[0] = true 表示新角色
     * @return Character 对象；失败返回 {@code null}
     */
    private Character resolvePlayer(World wserv, Client c, int cid, Server server,
                                    boolean[] newcomerHolder) {
        Character player = wserv.getPlayerStorage().getCharacterById(cid);

        // HWID
        final Hwid hwid;
        if (player == null) {
            hwid = SessionCoordinator.getInstance().pickLoginSessionHwid(c);
            if (hwid == null) {
                c.disconnect(true, false);
                return null;
            }
        } else {
            hwid = player.getClient().getHwid();
        }
        c.setHwid(hwid);

        // TransitionSession 原子消费
        TransitionSession session = LoginCoordinator.getInstance().consume(c, cid);
        if (session == null) {
            c.disconnect(true, false);
            return null;
        }
        session.applyTo(c);

        // 从 DB 加载（新人）
        if (player == null) {
            try {
                player = Character.loadCharFromDB(cid, c, true);
                newcomerHolder[0] = true;
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (player == null) {
                c.disconnect(true, false);
                return null;
            }
        }
        c.setPlayer(player);
        c.setAccID(player.getAccountId());
        AuditContext.set(c);
        return player;
    }

    // ============================================================
    //  Step 3: 登录状态校验
    // ============================================================

    /**
     * 验证登录过渡状态（LOGIN_SERVER_TRANSITION）并提升为 LOGIN_LOGGEDIN。
     *
     * @param c 频道 Client
     * @return true 校验通过；false 已断开或发送错误包
     */
    private boolean checkLoginState(Client c) {
        int accId = c.getAccID();
        if (!tryAcquireAccount(accId)) {
            c.setPlayer(null);
            c.setAccID(0);
            c.sendPacket(PacketCreator.getAfterLoginError(10));
            return false;
        }

        try {
            int state = c.getLoginState();
            if (state != Client.LOGIN_SERVER_TRANSITION) {
                c.setPlayer(null);
                c.setAccID(0);
                if (state == Client.LOGIN_LOGGEDIN) {
                    c.disconnect(true, false);
                } else {
                    c.sendPacket(PacketCreator.getAfterLoginError(7));
                }
                return false;
            }
            c.updateLoginState(Client.LOGIN_LOGGEDIN);
        } finally {
            releaseAccount(accId);
        }
        return true;
    }

    // ============================================================
    //  Step 4: 非新角色状态回填
    // ============================================================

    /**
     * 非新角色（已有角色）登录时从旧 Client 回填语言 / 角色槽 / 新 Client 绑定。
     */
    private void applyReturningState(Client c, Character player) {
        c.setLanguage(player.getClient().getLanguage());
        c.setCharacterSlots((byte) player.getClient().getCharacterSlots());
        player.newClient(c);
    }

    // ============================================================
    //  Step 5: 频道 / 世界登记
    // ============================================================

    /**
     * 将玩家加入频道和世界存储，同时处理 HP/MP 提醒。
     */
    private void enterWorld(Channel cserv, World wserv, Character player) {
        // HP / MP 自动提醒
        if (GameConfig.getServerBoolean("use_server_auto_pot")) {
            player.broadcastAcquaintances(PacketCreator.updateHpMpAlert(
                    hpMpAlertService.getHpAlert(player.getId()),
                    hpMpAlertService.getMpAlert(player.getId())));
        }
        cserv.addPlayer(player);
        wserv.addPlayer(player);
        player.setEnteredChannelWorld();
    }

    // ============================================================
    //  Step 6: 初始封包（buffs / charInfo / keymap …）
    // ============================================================

    /**
     * 发送角色登录后的第一批封包：buffs、diseases、charInfo、keymap、macros 等。
     *
     * @return diseases map 供后续 finalizeLogin 使用（登录通知里的 debuff 发送）
     */
    private Map<Disease, Pair<Long, MobSkill>> sendInitialInfo(Client c, Character player,
                                                                Server server, int cid) {
        // Buffs
        List<PlayerBuffValueHolder> buffs = server.getPlayerBuffStorage().getBuffsFromStorage(cid);
        if (buffs != null) {
            player.silentGiveBuffs(getLocalStartTimes(buffs));
        }

        // Diseases
        Map<Disease, Pair<Long, MobSkill>> diseases = server.getPlayerBuffStorage().getDiseasesFromStorage(cid);
        if (diseases != null) {
            player.silentApplyDiseases(diseases);
        }

        // 核心角色信息包
        c.sendPacket(PacketCreator.getCharInfo(player));

        // GM 隐身
        if (player.isHidden()) {
            if (!GameConfig.getServerBoolean("use_auto_hide_gm")) {
                player.toggleHide(true);
            }
        } else {
            if (player.isGM() && GameConfig.getServerBoolean("use_auto_hide_gm")) {
                player.toggleHide(true);
            }
        }

        // 按键 / 快捷栏 / 宏
        player.sendKeymap();
        player.sendQuickmap();
        player.sendMacros();

        // 自动喝药键位
        KeyBinding autohpPot = player.getKeymap().get(91);
        player.sendPacket(PacketCreator.sendAutoHpPot(autohpPot != null ? autohpPot.getAction() : 0));
        KeyBinding autompPot = player.getKeymap().get(92);
        player.sendPacket(PacketCreator.sendAutoMpPot(autompPot != null ? autompPot.getAction() : 0));

        // 进入地图
        player.getMap().addPlayer(player);
        player.visitMap(player.getMap());

        // 监狱状态恢复
        player.resumeJailSentence();

        return diseases;
    }

    // ============================================================
    //  Step 7: 社交模块
    // ============================================================

    /**
     * 初始化社交模块：好友、家族、公会、信使、组队、服务通知。
     */
    private void setupSocial(World wserv, Client c, Character player, boolean newcomer) {
        // 好友
        BuddyList bl = player.getBuddylist();
        int[] buddyIds = bl.getBuddyIds();
        wserv.loggedOn(player.getName(), player.getId(), c.getChannel(), buddyIds);
        for (CharacterIdChannelPair onlineBuddy : wserv.multiBuddyFind(player.getId(), buddyIds)) {
            BuddylistEntry ble = bl.get(onlineBuddy.getCharacterId());
            ble.setChannel(onlineBuddy.getChannel());
            bl.put(ble);
        }
        c.sendPacket(PacketCreator.updateBuddylist(bl.getBuddies()));

        // 家族
        c.sendPacket(PacketCreator.loadFamily(player));
        if (player.getFamilyId() > 0) {
            Family f = wserv.getFamily(player.getFamilyId());
            if (f != null) {
                FamilyEntry familyEntry = f.getEntryByID(player.getId());
                if (familyEntry != null) {
                    familyEntry.setCharacter(player);
                    player.setFamilyEntry(familyEntry);
                    c.sendPacket(PacketCreator.getFamilyInfo(familyEntry));
                    familyEntry.announceToSenior(PacketCreator.sendFamilyLoginNotice(player.getName(), true), true);
                } else {
                    log.error(I18nUtil.getLogMessage("PlayerLoggedinHandler.error.message1"), player.getName(), f.getID());
                }
            } else {
                log.error(I18nUtil.getLogMessage("PlayerLoggedinHandler.error.message2"), player.getName(), player.getFamilyId());
                c.sendPacket(PacketCreator.getFamilyInfo(null));
            }
        } else {
            c.sendPacket(PacketCreator.getFamilyInfo(null));
        }

        // 公会
        Server server = Server.getInstance();
        setupGuild(server, wserv, c, player, newcomer);

        // 服务通知 / 异常地图提示
        noteService.show(player);
        c.getSysRescue().showMapChangeMessage(player);

        // 组队
        if (player.getParty() != null) {
            PartyCharacter pchar = player.getMPC();
            pchar.setChannel(c.getChannel());
            pchar.setMapId(player.getMapId());
            pchar.setOnline(true);
            wserv.updateParty(player.getParty().getId(), PartyOperation.LOG_ONOFF, pchar);
            player.updatePartyMemberHP();
        }
    }

    /**
     * 初始化公会 + 联盟。
     */
    private void setupGuild(Server server, World wserv, Client c, Character player, boolean newcomer) {
        if (player.getGuildId() <= 0) return;

        Guild playerGuild = server.getGuild(player.getGuildId(), player.getWorld(), player);
        if (playerGuild == null) {
            player.deleteGuild(player.getGuildId());
            player.getMGC().setGuildId(0);
            player.getMGC().setGuildRank(5);
            return;
        }

        playerGuild.getMGC(player.getId()).setCharacter(player);
        player.setMGC(playerGuild.getMGC(player.getId()));
        server.setGuildMemberOnline(player, true, c.getChannel());
        c.sendPacket(GuildPackets.showGuildInfo(player));

        int allianceId = player.getGuild().getAllianceId();
        if (allianceId <= 0) return;

        Alliance newAlliance = server.getAlliance(allianceId);
        if (newAlliance == null) {
            newAlliance = Alliance.loadAlliance(allianceId);
            if (newAlliance != null) {
                server.addAlliance(allianceId, newAlliance);
            } else {
                player.getGuild().setAllianceId(0);
            }
        }
        if (newAlliance != null) {
            c.sendPacket(GuildPackets.updateAllianceInfo(newAlliance, c.getWorld()));
            c.sendPacket(GuildPackets.allianceNotice(newAlliance.getId(), newAlliance.getNotice()));
            if (newcomer) {
                server.allianceMessage(allianceId, GuildPackets.allianceMemberOnline(player, true), player.getId(), -1);
            }
        }
    }

    // ============================================================
    //  Step 8: 战斗 + 宠物 + 技能
    // ============================================================

    /**
     * 初始化战斗相关：装备、技能、宠物、坐骑、任务过期。
     */
    private void setupCombat(World wserv, Client c, Character player, boolean newcomer) {
        // 装备
        Inventory eqpInv = player.getInventory(InventoryType.EQUIPPED);
        eqpInv.lockInventory();
        try {
            for (Item it : eqpInv.list()) {
                player.equippedItem((Equip) it);
            }
        } finally {
            eqpInv.unlockInventory();
        }

        // 好友列表（二次发送）
        c.sendPacket(PacketCreator.updateBuddylist(player.getBuddylist().getBuddies()));

        // 待处理好友申请
        CharacterNameAndId pendingBuddyRequest = c.getPlayer().getBuddylist().pollPendingRequest();
        if (pendingBuddyRequest != null) {
            c.sendPacket(PacketCreator.requestBuddylistAdd(
                    pendingBuddyRequest.getId(), c.getPlayer().getId(), pendingBuddyRequest.getName()));
        }

        // 性别 / 信使 / 举报
        c.sendPacket(PacketCreator.updateGender(player));
        player.checkMessenger();
        c.sendPacket(PacketCreator.enableReport());

        // 技能
        player.changeSkillLevel(SkillFactory.getSkill(10000000 * player.getJobType() + 12),
                (byte) (player.getLinkedLevel() / 10), 20, -1);
        player.checkBerserk(player.isHidden());

        // 宠物 / 坐骑 / 任务过期（仅新人）
        if (!newcomer) {
            if (player.isRidingBattleship()) {
                player.announceBattleshipHp();
            }
            return;
        }

        for (Pet pet : player.getPets()) {
            if (pet != null) {
                wserv.registerPetHunger(player, player.getPetIndex(pet));
            }
        }

        Mount mount = player.getMapleMount();
        if (mount.getItemId() != 0) {
            player.sendPacket(PacketCreator.updateMount(player.getId(), mount, false));
        }

        player.reloadQuestExpirations();
    }

    // ============================================================
    //  Step 9: 收尾 — timers / rates / 通知 / 事件
    // ============================================================

    /**
     * 登录收尾：timers、倍率、作弊检测、婚礼通知、事件召回、NPC 脚本化、登录播报。
     */
    private void finalizeLogin(World wserv, Client c, Character player, boolean newcomer, int cid,
                               Map<Disease, Pair<Long, MobSkill>> diseases) {
        // 登录通知
        if (newcomer) {
            log.info("客户端 {} 账号 {} 角色 {} 登录了游戏，在频道 {}。",
                    c.getRemoteAddress(), c.getAccountName(), player.getName(), c.getChannelServer().getId());
            if (player.isGM()) {
                Server.getInstance().broadcastGMMessage(c.getWorld(),
                        PacketCreator.earnTitleMessage((player.gmLevel() < 6 ? "GM " : "Admin ")
                                + player.getName() + " 登录了游戏"));
            } else if (GameConfig.getServerBoolean("use_login_notification")) {
                String msg = I18nUtil.getMessage("Character.login.globalNotice", player.getName());
                Server.getInstance().broadcastMessage(c.getWorld(),
                        PacketCreator.serverNotice(3, c.getChannel(), msg));
            }
            // Debuff 发送（仅在 diseases 和 newcomer 同时满足时）
            if (diseases != null) {
                for (Entry<Disease, Pair<Long, MobSkill>> e : diseases.entrySet()) {
                    final List<Pair<Disease, Integer>> debuff =
                            Collections.singletonList(new Pair<>(e.getKey(), e.getValue().getRight().getX()));
                    c.sendPacket(PacketCreator.giveDebuff(debuff, e.getValue().getRight()));
                }
            }
        } else {
            log.info("客户端 {} 账号 {} 角色 {} 进入频道 {}。",
                    c.getRemoteAddress(), c.getAccountName(), player.getName(), c.getChannelServer().getId());
        }

        // Timers
        player.buffExpireTask();
        player.diseaseExpireTask();
        player.skillCooldownTask();
        player.expirationTask();
        player.questExpirationTask();
        if (GameConstants.hasSPTable(player.getJob()) && player.getJob().getId() != 2001) {
            player.createDragon();
        }

        // 待排除物品 / 快递通知
        player.commitExcludedItems();
        showDueyNotification(c, player);

        // 倍率
        player.resetPlayerRates();
        if (GameConfig.getServerBoolean("use_add_rates_by_level")) {
            player.setPlayerRates();
        }
        player.setWorldRates();
        player.updateCouponRates();
        player.receivePartyMemberHP();

        // 初始化内置辅助插件
        player.initCheatManager();
        player.startCheatItemVac(); // 启动内置宠吸

        // 婚礼搭档通知
        if (player.getPartnerId() > 0) {
            int partnerId = player.getPartnerId();
            final Character partner = wserv.getPlayerStorage().getCharacterById(partnerId);
            if (partner != null && !partner.isAwayFromWorld()) {
                player.sendPacket(WeddingPackets.OnNotifyWeddingPartnerTransfer(partnerId, partner.getMapId()));
                partner.sendPacket(WeddingPackets.OnNotifyWeddingPartnerTransfer(player.getId(), player.getMapId()));
            }
        }

        // 事件召回（仅新人）
        if (newcomer) {
            EventInstanceManager eim = EventRecallCoordinator.getInstance().recallEventInstance(cid);
            if (eim != null) {
                eim.registerPlayer(player);
            }
        }

        // NPC 脚本化
        if (GameConfig.getServerBoolean("use_npcs_scriptable")) {
            Map<Integer, String> npcsIds = configService.readJson(
                    "npcs_scriptable",
                    new TypeReference<Map<Integer, String>>() {
                    },
                    new HashMap<>());
            if (GameConfig.getServerBoolean("use_rebirth_system")) {
                npcsIds.put(GameConfig.getServerInt("rebirth_npc_id"), "Rebirth");
            }
            c.sendPacket(PacketCreator.setNPCScriptable(npcsIds));
        }

        if (newcomer) {
            player.setLoginTime(System.currentTimeMillis());
        }
    }

    // ============================================================
    //  辅助方法
    // ============================================================

    private static void showDueyNotification(Client c, Character player) {
        DueypackagesMapper mapper = SpringContextUtil.getBean(DueypackagesMapper.class);
        QueryWrapper query = QueryWrapper.create()
                .select(DueypackagesDO::getType)
                .where(DueypackagesDO::getReceiverid).eq(player.getId())
                .and(DueypackagesDO::getChecked).eq(1)
                .orderBy(DueypackagesDO::getType, false);

        DueypackagesDO result = mapper.selectOneByQuery(query);

        if (result != null) {
            UpdateChain.of(DueypackagesDO.class)
                    .set(DueypackagesDO::getChecked, 0)
                    .where(DueypackagesDO::getReceiverid).eq(player.getId())
                    .and(DueypackagesDO::getChecked).eq(1)
                    .update();
            c.sendPacket(PacketCreator.sendDueyParcelNotification(result.getType() == 1));
        }
    }

    private static List<Pair<Long, PlayerBuffValueHolder>> getLocalStartTimes(List<PlayerBuffValueHolder> lpbvl) {
        List<Pair<Long, PlayerBuffValueHolder>> timedBuffs = new ArrayList<>();
        long curtime = currentServerTime();

        for (PlayerBuffValueHolder pb : lpbvl) {
            timedBuffs.add(new Pair<>(curtime - pb.usedTime, pb));
        }

        timedBuffs.sort((p1, p2) -> p1.getLeft().compareTo(p2.getLeft()));
        return timedBuffs;
    }
}
