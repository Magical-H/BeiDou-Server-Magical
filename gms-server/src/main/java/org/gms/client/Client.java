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
package org.gms.client;

import lombok.Getter;
import org.gms.client.inventory.InventoryType;
import org.gms.config.GameConfig;
import org.gms.constants.game.GameConstants;
import org.gms.constants.id.MapId;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import org.gms.net.PacketHandler;
import org.gms.net.PacketProcessor;
import org.gms.net.netty.InvalidPacketHeaderException;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.Packet;
import org.gms.net.packet.logging.LoggingUtil;
import org.gms.net.packet.logging.MonitoredChrLogger;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.coordinator.login.LoginBypassCoordinator;
import org.gms.net.server.coordinator.session.Hwid;
import org.gms.net.server.coordinator.session.SessionCoordinator;
import org.gms.net.server.coordinator.session.SessionCoordinator.AntiMulticlientResult;
import org.gms.net.server.guild.Guild;
import org.gms.net.server.guild.GuildCharacter;
import org.gms.net.server.guild.GuildPackets;
import org.gms.net.server.world.MessengerCharacter;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import org.gms.net.server.world.PartyOperation;
import org.gms.net.server.world.World;
import org.gms.server.SystemRescue;
import org.gms.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.scripting.AbstractPlayerInteraction;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.scripting.event.EventManager;
import org.gms.scripting.npc.NPCConversationManager;
import org.gms.scripting.npc.NPCScriptManager;
import org.gms.scripting.quest.QuestActionManager;
import org.gms.scripting.quest.QuestScriptManager;
import org.gms.server.MapleLeafLogger;
import org.gms.server.ThreadManager;
import org.gms.server.TimerManager;
import org.gms.server.life.Monster;
import org.gms.server.maps.FieldLimit;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.MiniDungeonInfo;

import javax.script.ScriptEngine;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.SECONDS;

public class Client extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(Client.class);

    public static final int LOGIN_NOTLOGGEDIN = 0;
    public static final int LOGIN_SERVER_TRANSITION = 1;
    public static final int LOGIN_LOGGEDIN = 2;

    private final Type type;
    private final long sessionId;
    private final PacketProcessor packetProcessor;

    private Hwid hwid;
    private String remoteAddress;
    private volatile boolean inTransition;

    private io.netty.channel.Channel ioChannel;
    private Character player;
    private int channel = 1;
    private int accId = -4;
    private boolean loggedIn = false;
    private boolean serverTransition = false;
    private Calendar birthday = null;
    private String accountName = null;
    private int world;
    private volatile long lastPong;
    private int gmlevel;
    private Set<String> macs = new HashSet<>();
    private Set<String> ips = new HashSet<>();
    private Map<String, ScriptEngine> engines = new HashMap<>();
    private byte characterSlots = 3;
    private byte loginattempt = 0;
    private String pin = "";
    private int pinattempt = 0;
    private String pic = "";
    private int picattempt = 0;
    private byte csattempt = 0;
    private byte gender = -1;
    private boolean disconnecting = false;
    private final Semaphore actionsSemaphore = new Semaphore(7);
    private final Lock lock = new ReentrantLock(true);
    private final Lock encoderLock = new ReentrantLock(true);
    private final Lock announcerLock = new ReentrantLock(true);
    // thanks Masterrulax & try2hack for pointing out a bottleneck issue with shared locks, shavit for noticing an opportunity for improvement
    private Calendar tempBanCalendar;
    private int votePoints;
    private int voteTime = -1;
    private int visibleWorlds;
    private long lastNpcClick;
    private long lastPacket = System.currentTimeMillis();
    private int lang = 0;
    // 提供公共方法来获取 sysRescue
    @Getter
    private static SystemRescue sysRescue;

    public enum Type {
        LOGIN,
        CHANNEL
    }

    public Client(Type type, long sessionId, String remoteAddress, PacketProcessor packetProcessor, int world, int channel) {
        this.type = type;
        this.sessionId = sessionId;
        this.remoteAddress = remoteAddress;
        this.packetProcessor = packetProcessor;
        this.world = world;
        this.channel = channel;
    }

    public static Client createLoginClient(long sessionId, String remoteAddress, PacketProcessor packetProcessor,
                                           int world, int channel) {
        return new Client(Type.LOGIN, sessionId, remoteAddress, packetProcessor, world, channel);
    }

    public static Client createChannelClient(long sessionId, String remoteAddress, PacketProcessor packetProcessor,
                                             int world, int channel) {
        return new Client(Type.CHANNEL, sessionId, remoteAddress, packetProcessor, world, channel);
    }

    public static Client createMock() {
        return new Client(null, -1, null, null, -123, -123);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        final io.netty.channel.Channel channel = ctx.channel();
        if (!Server.getInstance().isOnline()) {
            channel.close();
            return;
        }

        this.remoteAddress = getRemoteAddress(channel);
        this.ioChannel = channel;
    }

    private static String getRemoteAddress(io.netty.channel.Channel channel) {
        String remoteAddress = "null";
        try {
            remoteAddress = ((InetSocketAddress) channel.remoteAddress()).getAddress().getHostAddress();
        } catch (NullPointerException npe) {
            log.warn("无法获取客户端的远程地址", npe);
        }

        return remoteAddress;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof InPacket packet)) {
            log.warn("收到无效封包: {}", msg);
            return;
        }

        short opcode = packet.readShort();
        final PacketHandler handler = packetProcessor.getHandler(opcode);

        if (GameConfig.getServerBoolean("use_debug_show_rcvd_packet") && !LoggingUtil.isIgnoredRecvPacket(opcode)) {
            log.info("收到封包 包头ID [{}] 包名 [{}] 内容： {}", String.format("0x%02X", opcode),handler.getClass().getSimpleName(),packet);
        }

        if (handler != null && handler.validateState(this)) {
            try {
                ThreadLocalUtil.setCurrentClient(this);
                MonitoredChrLogger.logPacketIfMonitored(this, opcode, packet.getBytes());
                handler.handlePacket(packet, this);
            } catch (final Throwable t) {
                final String chrInfo = player != null ? player.getName() + " 地图 [" + player.getMap().getMapName() + "] (" + player.getMapId() + ")" : "?";
                log.warn("封包处理器 {} 出错. 账号 {}, 玩家 {}. 封包: {}", handler.getClass().getSimpleName(),
                        getAccountName(), chrInfo, packet, t);
                enableActions();//解除客户端假死
            } finally {
                ThreadLocalUtil.removeCurrentClient();
            }
        }

        updateLastPacket();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (event instanceof IdleStateEvent idleEvent) {
            checkIfIdle(idleEvent);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (player != null && !player.isLoggedInWorld()) {  //判断玩家不为空且不在线才进行救援
            String MapName = player.getMap().getMapName().isEmpty() ? I18nUtil.getLogMessage("SystemRescue.info.map.message1") : player.getMap().getMapName();  //读取出错地图名称，这里是读取服务端String.wz地图名称，不存在则设为 未知地图
            log.warn(I18nUtil.getLogMessage("Client.warn.map.message1"), player, MapName , player.getMapId(), cause);
            sysRescue.setMapChange(player);   // 尝试解救那些卡地图的倒霉蛋。
        }

        if (cause instanceof InvalidPacketHeaderException) {
            SessionCoordinator.getInstance().closeSession(this, true);
        } else if (cause instanceof IOException) {
            closeMapleSession();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        closeMapleSession();
    }

    private void closeMapleSession() {
        switch (type) {
            case LOGIN -> SessionCoordinator.getInstance().closeLoginSession(this);
            case CHANNEL -> SessionCoordinator.getInstance().closeSession(this, null);
        }

        try {
            // client freeze issues on session transition states found thanks to yolinlin, Omo Oppa, Nozphex
            if (!inTransition) {
                disconnect(false, false);
            }
        } catch (Throwable t) {
            log.warn("账号卡住", t);
        } finally {
            closeSession();
        }
    }

    public void updateLastPacket() {
        lastPacket = System.currentTimeMillis();
    }

    public long getLastPacket() {
        return lastPacket;
    }

    public void closeSession() {
        ioChannel.close();
    }

    public void disconnectSession() {
        ioChannel.disconnect();
    }

    public Hwid getHwid() {
        return hwid;
    }

    public void setHwid(Hwid hwid) {
        this.hwid = hwid;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public boolean isInTransition() {
        return inTransition;
    }

    public EventManager getEventManager(String event) {
        return getChannelServer().getEventSM().getEventManager(event);
    }

    public Character getPlayer() {
        return player;
    }

    /**
     * 设置角色
     * @param player
     */
    public void setPlayer(Character player) {
        this.player = player;
        this.sysRescue = new SystemRescue();
    }

    public AbstractPlayerInteraction getAbstractPlayerInteraction() {
        return new AbstractPlayerInteraction(this);
    }

    public void sendCharList(int server) {
        this.sendPacket(PacketCreator.getCharList(this, server, 0));
    }

    public List<Character> loadCharacters(int serverId) {
        List<Character> chars = new ArrayList<>(15);
        try {
            for (CharNameAndId cni : loadCharactersInternal(serverId)) {
                chars.add(Character.loadCharFromDB(cni.id, this, false));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return chars;
    }

    public List<String> loadCharacterNames(int worldId) {
        List<String> chars = new ArrayList<>(15);
        for (CharNameAndId cni : loadCharactersInternal(worldId)) {
            chars.add(cni.name);
        }
        return chars;
    }

    private List<CharNameAndId> loadCharactersInternal(int worldId) {
        List<CharNameAndId> chars = new ArrayList<>(15);
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT id, name FROM characters WHERE accountid = ? AND world = ?")) {
            ps.setInt(1, this.getAccID());
            ps.setInt(2, worldId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    chars.add(new CharNameAndId(rs.getString("name"), rs.getInt("id")));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return chars;
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    public boolean hasBannedIP() {
        boolean ret = false;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM ipbans WHERE ? LIKE CONCAT(ip, '%')")) {
            ps.setString(1, remoteAddress);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    ret = true;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return ret;
    }

    public int getVoteTime() {
        if (voteTime != -1) {
            return voteTime;
        }

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT date FROM bit_votingrecords WHERE UPPER(account) = UPPER(?)")) {
            ps.setString(1, accountName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return -1;
                }
                voteTime = rs.getInt("date");
            }
        } catch (SQLException e) {
            log.error("获取投票时间时出错");
            return -1;
        }
        return voteTime;
    }

    public void resetVoteTime() {
        voteTime = -1;
    }

    public boolean hasVotedAlready() {
        Date currentDate = new Date();
        int timeNow = (int) (currentDate.getTime() / 1000);
        int difference = (timeNow - getVoteTime());
        return difference < 86400 && difference > 0;
    }

    public boolean hasBannedHWID() {
        if (hwid == null) {
            return false;
        }

        boolean ret = false;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM hwidbans WHERE hwid LIKE ?")) {
            ps.setString(1, hwid.hwid());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs != null && rs.next()) {
                    if (rs.getInt(1) > 0) {
                        ret = true;
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return ret;
    }

    public boolean hasBannedMac() {
        if (macs.isEmpty()) {
            return false;
        }
        boolean ret = false;
        int i;
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM macbans WHERE mac IN (");
        for (i = 0; i < macs.size(); i++) {
            sql.append("?");
            if (i != macs.size() - 1) {
                sql.append(", ");
            }
        }
        sql.append(")");

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql.toString())) {
            i = 0;
            for (String mac : macs) {
                ps.setString(++i, mac);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    ret = true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return ret;
    }

    private void loadHWIDIfNescessary() throws SQLException {
        if (hwid == null) {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("SELECT hwid FROM accounts WHERE id = ?")) {
                ps.setInt(1, accId);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        hwid = new Hwid(rs.getString("hwid"));
                    }
                }
            }
        }
    }

    // TODO: Recode to close statements...
    private void loadMacsIfNescessary() throws SQLException {
        if (macs.isEmpty()) {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("SELECT macs FROM accounts WHERE id = ?")) {
                ps.setInt(1, accId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        for (String mac : rs.getString("macs").split(", ")) {
                            if (!mac.equals("")) {
                                macs.add(mac);
                            }
                        }
                    }
                }
            }
        }
    }

    public void banHWID() {
        try {
            loadHWIDIfNescessary();

            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("INSERT INTO hwidbans (hwid) VALUES (?)")) {
                ps.setString(1, hwid.hwid());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 封禁客户端IP地址
     * @return true || false
     */
    public boolean banIP() {
        String ip = getRemoteAddress();
        try (Connection con = DatabaseConnection.getConnection()) {
            if (ip.matches("[0-9]{1,3}\\..*")) {
                try (PreparedStatement ps = con.prepareStatement("INSERT INTO ipbans VALUES (DEFAULT, ?, ?)")) {
                    ps.setString(1, ip);
                    ps.setInt(2, getAccID());

                    if (ps.executeUpdate() > 0) {
                        log.info("封禁IP地址：{}",ip);
                        return true;
                    }
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return false;
    }

    /**
     * 封禁客户端所有Mac地址
     * @return 返回被封禁的Mac地址字符串列表，如：00-50-56-C0-00-08, 00-4B-F3-D2-D5-7F, 00-50-56-C0-00-01
     */
    public String banMacs() {
        List<String> MacList = new ArrayList<>();
        try {
            loadMacsIfNescessary();

            // 设置阈值，出现次数超过此值的MAC将被视为虚拟机MAC而不被封禁
            final int VIRTUAL_MAC_THRESHOLD = Optional.of(GameConfig.getServerInt("ban_mac_ignore_majority")).filter(v -> (v > 2)).orElse(5);    //阈值不应该低于2，否则容易产生误判

            Map<String, Integer> macOccurrences = new HashMap<>(); // 存储MAC出现次数

            try (Connection con = DatabaseConnection.getConnection()) {
                // 使用工具类检查MAC地址，获取未匹配过滤规则的MAC地址
                List<String> macsToCheck = MacFilterHelper.checkMacs(new ArrayList<>(macs));

                // 如果存在需要检查的MAC地址，查询它们在系统中的出现次数（按HWID去重）
                if (!macsToCheck.isEmpty()) {
                    // 使用参数化查询构建SQL语句
                    String occurrenceQuery =
                            "SELECT " +
                                    "    mac_address, " +
                                    "    COUNT(*) AS count " +
                                    "FROM (" +
                                    "    SELECT DISTINCT " +
                                    "        hwid, " +
                                    "        TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(macs, ',', numbers.n), ',', -1)) AS mac_address " +
                                    "    FROM " +
                                    "        accounts " +
                                    "    JOIN " +
                                    "        (SELECT 1 n UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6) numbers " +
                                    "        ON CHAR_LENGTH(macs) - CHAR_LENGTH(REPLACE(macs, ',', '')) >= numbers.n - 1 " +
                                    "    WHERE " +
                                    "        macs IS NOT NULL " +
                                    "        AND macs != '' " +
                                    "        AND hwid IS NOT NULL " +
                                    ") AS distinct_macs " +
                                    "WHERE " +
                                    "    mac_address IN (";

                    // 添加占位符
                    String[] placeholders = new String[macsToCheck.size()];
                    Arrays.fill(placeholders, "?");
                    occurrenceQuery += String.join(",", placeholders) + ") " +
                            "GROUP BY " +
                            "    mac_address";

                    try (PreparedStatement ps = con.prepareStatement(occurrenceQuery)) {
                        // 设置查询参数 - 使用增强for循环
                        int index = 1;
                        for (String mac : macsToCheck) {
                            ps.setString(index++, mac);
                        }

                        ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            macOccurrences.put(rs.getString("mac_address"), rs.getInt("count"));
                        }
                    }
                }

                try (PreparedStatement ps = con.prepareStatement("INSERT INTO macbans (mac, aid) VALUES (?, ?)")) {
                    for (String mac : macsToCheck) {
                        // 检查MAC出现次数，超过阈值则跳过（视为虚拟机MAC）
                        Integer occurrence = macOccurrences.get(mac);
                        if (occurrence != null && occurrence > VIRTUAL_MAC_THRESHOLD) {
                            MacFilterHelper.addFilter(mac);// 自动添加到过滤规则
                            log.warn("封禁时跳过Mac地址：{}，出现次数：{}，已加入到过滤规则。",mac,occurrence);
                            continue;
                        }

                        // 封禁MAC地址
                        ps.setString(1, mac);
                        ps.setString(2, String.valueOf(getAccID()));
                        ps.executeUpdate();

                        // 添加到返回列表
                        MacList.add(mac);
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        log.info("封禁MAC地址：[{}]",String.join(",", MacList));
        return String.join(",", MacList);
    }

    /**
     * 获取包含指定字符串的活跃记录数量（同时对ip、macs、hwid三个字段进行模糊查询）
     * @param searchValue 要搜索的字符串
     * @return 匹配的记录数量
     */
    public int getActiveRecordCount(String searchValue) {
        int count = 0;

        // 如果搜索值为null或空，直接返回0
        if (searchValue == null || searchValue.isEmpty()) {
            return 0;
        }

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT COUNT(*) as record_count FROM accounts WHERE loggedin > 0 " +
                             "AND (ip LIKE ? OR macs LIKE ? OR hwid LIKE ?)")) {

            // 对三个字段都使用相同的模糊匹配模式
            String searchPattern = "%" + searchValue + "%";
            ps.setString(1, searchPattern);
            ps.setString(2, searchPattern);
            ps.setString(3, searchPattern);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    count = rs.getInt("record_count");
                }
            }
        } catch (SQLException e) {
            log.error("getActiveRecordCount 出错 ", e);
        }

        return count;
    }

    /**
     * 获取今天通过指定设备登录的账号数量
     * @param searchValue 要搜索的字符串
     * @return 今天登录的账号数量
     */
    public int getTodayLoginCount(String searchValue) {
        int count = 0;

        // 如果搜索值为null或空，直接返回0
        if (searchValue == null || searchValue.isEmpty()) {
            return 0;
        }

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT COUNT(*) as record_count FROM accounts WHERE loggedin > 0 " +
                             "AND (ip LIKE ? OR macs LIKE ? OR hwid LIKE ?) " +
                             "AND DATE(lastlogin) = CURDATE()")) {

            // 对三个字段都使用相同的模糊匹配模式
            String searchPattern = "%" + searchValue + "%";
            ps.setString(1, searchPattern);
            ps.setString(2, searchPattern);
            ps.setString(3, searchPattern);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    count = rs.getInt("record_count");
                }
            }
        } catch (SQLException e) {
            log.error("getTodayLoginCount 出错 ", e);
        }

        return count;
    }

    public int finishLogin() {
        encoderLock.lock();
        try {
            if (getLoginState() > LOGIN_NOTLOGGEDIN) { // 0 = LOGIN_NOTLOGGEDIN, 1= LOGIN_SERVER_TRANSITION, 2 = LOGIN_LOGGEDIN
                loggedIn = false;
                return 7;
            }
            updateLoginState(Client.LOGIN_LOGGEDIN);
        } finally {
            encoderLock.unlock();
        }

        return 0;
    }

    public void setPin(String pin) {
        this.pin = pin;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE accounts SET pin = ? WHERE id = ?")) {
            ps.setString(1, pin);
            ps.setInt(2, accId);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public String getPin() {
        return pin;
    }

    public boolean checkPin(String other) {
        if (!(GameConfig.getServerBoolean("enable_pin") && !canBypassPin())) {
            return true;
        }

        pinattempt++;
        if (pinattempt > 5) {
            SessionCoordinator.getInstance().closeSession(this, false);
        }
        if (pin.equals(other)) {
            pinattempt = 0;
            LoginBypassCoordinator.getInstance().registerLoginBypassEntry(hwid, accId, false);
            return true;
        }
        return false;
    }

    public void setPic(String pic) {
        this.pic = pic;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE accounts SET pic = ? WHERE id = ?")) {
            ps.setString(1, pic);
            ps.setInt(2, accId);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public String getPic() {
        return pic;
    }

    public boolean checkPic(String other) {
        if (!(GameConfig.getServerBoolean("enable_pic") && !canBypassPic())) {
            return true;
        }

        picattempt++;
        if (picattempt > 5) {
            SessionCoordinator.getInstance().closeSession(this, false);
        }
        if (pic.equals(other)) {    // thanks ryantpayton (HeavenClient) for noticing null pics being checked here
            picattempt = 0;
            LoginBypassCoordinator.getInstance().registerLoginBypassEntry(hwid, accId, true);
            return true;
        }
        return false;
    }

    public int login(String login, String pwd, Hwid hwid) {
        int loginok = 5;

        loginattempt++;
        if (loginattempt > 4) {
            loggedIn = false;
            SessionCoordinator.getInstance().closeSession(this, false);
            return 6;   // thanks Survival_Project for finding out an issue with AUTOMATIC_REGISTER here
        }

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT id, password, gender, banned, pin, pic, characterslots, tos, language FROM accounts WHERE name = ?")) {
            ps.setString(1, login);

            try (ResultSet rs = ps.executeQuery()) {
                accId = -2;
                if (rs.next()) {
                    accId = rs.getInt("id");
                    if (accId <= 0) {
                        log.warn("尝试使用accId登录 {}", accId);
                        return 15;
                    }

                    boolean banned = (rs.getByte("banned") == 1);
                    gmlevel = 0;
                    pin = rs.getString("pin");
                    pic = rs.getString("pic");
                    gender = rs.getByte("gender");
                    characterSlots = rs.getByte("characterslots");
                    lang = rs.getInt("language");
                    String passhash = rs.getString("password");
                    byte tos = rs.getByte("tos");

                    if (banned) {
                        return 3;
                    }

                    if (getLoginState() > LOGIN_NOTLOGGEDIN) { // already loggedin
                        loggedIn = false;
                        loginok = 7;
                    } else if (GameConfig.getServerBoolean("use_debug") && GameConfig.getServerBoolean("no_password")) {
                        return 0;
                    } else if (passhash.charAt(0) == '$' && passhash.charAt(1) == '2' && BCrypt.checkpw(pwd, passhash)) {
                        loginok = (tos == 0) ? 23 : 0;
                    } else if (pwd.equals(passhash) || checkHash(passhash, "SHA-1", pwd) || checkHash(passhash, "SHA-512", pwd)) {
                        // thanks GabrielSin for detecting some no-bcrypt inconsistencies here
                        loginok = (tos == 0) ? (!GameConfig.getServerBoolean("bcrypt_migration") ? 23 : -23) : (!GameConfig.getServerBoolean("bcrypt_migration") ? 0 : -10); // migrate to bcrypt
                    } else {
                        loggedIn = false;
                        loginok = 4;
                    }
                } else {
                    accId = -3;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        if (loginok == 0 || loginok == 4) {
            AntiMulticlientResult res = SessionCoordinator.getInstance().attemptLoginSession(this, hwid, accId, loginok == 4);  //loginok == 4，但是会导致限制多开参数 deterred_multi_client == true 时密码错误一次返回REMOTE_REACHED_LIMIT，需要重开客户端

            return switch (res) {
                case SUCCESS -> {
                    if (loginok == 0) {
                        loginattempt = 0;
                    }
                    yield loginok;
                }
                case REMOTE_LOGGEDIN -> 17;
                case REMOTE_REACHED_LIMIT -> 13;
                case REMOTE_PROCESSING -> 10;
                case MANY_ACCOUNT_ATTEMPTS -> 16;
                default -> 8;
            };
        } else {
            return loginok;
        }
    }

    /**
     * 从数据库中获取账号封禁原因
     * 查询accounts表中banreason字段的值，如不存在或为默认值则返回空字符串
     *
     * @return 账号封禁原因字符串，查询失败或为空时返回空字符串
     */
    public String getBanreasonFromDB() {
        String banReason = "";

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT `banreason` FROM accounts WHERE id = ?")) {

            ps.setInt(1, getAccID());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String reason = rs.getString("banreason");
                    // 检查是否为空或默认值（根据实际情况可能需要调整默认值的判断）
                    if (reason != null && !reason.trim().isEmpty()) {
                        banReason = reason;
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return banReason;
    }

    public Calendar getTempBanCalendarFromDB() {
        final Calendar lTempban = Calendar.getInstance();

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT `tempban` FROM accounts WHERE id = ?")) {
            ps.setInt(1, getAccID());

            final Timestamp tempban;
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }

                tempban = rs.getTimestamp("tempban");
                if (tempban.toLocalDateTime().equals(DefaultDates.getTempban())) {
                    return null;
                }
            }

            lTempban.setTimeInMillis(tempban.getTime());
            tempBanCalendar = lTempban;
            return lTempban;
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return null;//why oh why!?!
    }

    public Calendar getTempBanCalendar() {
        return tempBanCalendar;
    }

    public boolean hasBeenBanned() {
        return tempBanCalendar != null;
    }

    public static long dottedQuadToLong(String dottedQuad) throws RuntimeException {
        String[] quads = dottedQuad.split("\\.");
        if (quads.length != 4) {
            throw new RuntimeException("IP地址格式无效。");
        }
        long ipAddress = 0;
        for (int i = 0; i < 4; i++) {
            int quad = Integer.parseInt(quads[i]);
            ipAddress += (long) (quad % 256) * (long) Math.pow(256, 4 - i);
        }
        return ipAddress;
    }

    public void updateHwid(Hwid hwid) {
        this.hwid = hwid;

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE accounts SET hwid = ? WHERE id = ?")) {
            ps.setString(1, hwid.hwid());
            ps.setInt(2, accId);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void updateMacs(String macData) {
        macs.addAll(Arrays.asList(macData.split(", ")));
        StringBuilder newMacData = new StringBuilder();
        Iterator<String> iter = macs.iterator();
        while (iter.hasNext()) {
            String cur = iter.next();
            newMacData.append(cur);
            if (iter.hasNext()) {
                newMacData.append(", ");
            }
        }

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE accounts SET macs = ? WHERE id = ?")) {
            ps.setString(1, newMacData.toString());
            ps.setInt(2, accId);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 更新IP地址列表，自动去重并保存到数据库
     * @param ipData 新的IP地址数据（多个IP用逗号分隔）
     */
    public void updateIps(String ipData) {
        if (ipData == null || ipData.trim().isEmpty()) {
            return;
        }

        // 批量处理新IP
        List<String> newIps = Arrays.stream(ipData.split(","))
                .map(String::trim)
                .filter(ip -> !ip.isEmpty())
                .collect(Collectors.toList());

        if (newIps.isEmpty()) {
            return;
        }

        // 获取现有IP并使用Set去重
        Set<String> allIps = new LinkedHashSet<>(getIpsFromDB(accId));
        int originalSize = allIps.size();

        // 添加新IP
        allIps.addAll(newIps);

        // 如果没有新增IP，直接返回
        if (allIps.size() == originalSize) {
            return;
        }

        // 使用StringJoiner更高效地构建字符串
        StringJoiner sj = new StringJoiner(", ");
        allIps.forEach(sj::add);

        updateIpsToDatabase(accId, sj.toString());
    }
    /**
     * 将IP列表更新到数据库
     */
    private boolean updateIpsToDatabase(int accId, String ipData) {
        String sql = "UPDATE accounts SET ip = ? WHERE id = ?";

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, ipData);
            ps.setInt(2, accId);

            int affectedRows = ps.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }
    public void setAccID(int id) {
        this.accId = id;
    }

    public int getAccID() {
        return accId;
    }

    public void updateLoginState(int newState) {
        // rules out possibility of multiple account entries
        if (newState == LOGIN_LOGGEDIN) {
            SessionCoordinator.getInstance().updateOnlineClient(this);
        }

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE accounts SET loggedin = ?, lastlogin = ? WHERE id = ?")) {
            // using sql currenttime here could potentially break the login, thanks Arnah for pointing this out

            ps.setInt(1, newState);
            ps.setTimestamp(2, new java.sql.Timestamp(Server.getInstance().getCurrentTime()));
            ps.setInt(3, getAccID());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        if (newState == LOGIN_NOTLOGGEDIN) {
            loggedIn = false;
            serverTransition = false;
            setAccID(0);
        } else {
            serverTransition = (newState == LOGIN_SERVER_TRANSITION);
            loggedIn = !serverTransition;
        }
        updateIps(getRemoteAddress());
    }

    public int getLoginState() {  // 0 = LOGIN_NOTLOGGEDIN, 1= LOGIN_SERVER_TRANSITION, 2 = LOGIN_LOGGEDIN
        try (Connection con = DatabaseConnection.getConnection()) {
            int state;
            try (PreparedStatement ps = con.prepareStatement("SELECT loggedin, lastlogin, birthday FROM accounts WHERE id = ?")) {
                ps.setInt(1, getAccID());

                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new RuntimeException("获取登录状态-客户端账号：" + getAccID());
                    }

                    birthday = Calendar.getInstance();
                    try {
                        birthday.setTime(rs.getDate("birthday"));
                    } catch (SQLException e) {
                    }

                    state = rs.getInt("loggedin");
                    if (state == LOGIN_SERVER_TRANSITION) {
                        Timestamp lastlogin = rs.getTimestamp("lastlogin");
                        // 兼容历史已经创建的账号，和自动注册但未登录的账号
                        if (lastlogin == null || lastlogin.getTime() + 30000 < Server.getInstance().getCurrentTime()) {
                            int accountId = accId;
                            state = LOGIN_NOTLOGGEDIN;
                            updateLoginState(Client.LOGIN_NOTLOGGEDIN);   // ACCID = 0, issue found thanks to Tochi & K u ssss o & Thora & Omo Oppa
                            this.setAccID(accountId);
                        }
                    }
                }
            }
            if (state == LOGIN_LOGGEDIN) {
                loggedIn = true;
            } else if (state == LOGIN_SERVER_TRANSITION) {
                try (PreparedStatement ps2 = con.prepareStatement("UPDATE accounts SET loggedin = 0 WHERE id = ?")) {
                    ps2.setInt(1, getAccID());
                    ps2.executeUpdate();
                }
            } else {
                loggedIn = false;
            }
            return state;
        } catch (SQLException e) {
            loggedIn = false;
            e.printStackTrace();
            throw new RuntimeException("登录状态");
        }
    }

    public boolean checkBirthDate(Calendar date) {
        return date.get(Calendar.YEAR) == birthday.get(Calendar.YEAR) && date.get(Calendar.MONTH) == birthday.get(Calendar.MONTH) && date.get(Calendar.DAY_OF_MONTH) == birthday.get(Calendar.DAY_OF_MONTH);
    }

    private void removePartyPlayer(World wserv) {
        MapleMap map = player.getMap();
        final Party party = player.getParty();
        final int idz = player.getId();

        if (party != null) {
            final PartyCharacter chrp = new PartyCharacter(player);
            chrp.setOnline(false);
            wserv.updateParty(party.getId(), PartyOperation.LOG_ONOFF, chrp);
            if (party.getLeader().getId() == idz && map != null) {
                PartyCharacter lchr = null;
                for (PartyCharacter pchr : party.getMembers()) {
                    if (pchr != null && pchr.getId() != idz && (lchr == null || lchr.getLevel() <= pchr.getLevel()) && map.getCharacterById(pchr.getId()) != null) {
                        lchr = pchr;
                    }
                }
                if (lchr != null) {
                    wserv.updateParty(party.getId(), PartyOperation.CHANGE_LEADER, lchr);
                }
            }
        }
    }

    private void removePlayer(World wserv, boolean serverTransition) {
        try {
            Server.getInstance().getPlayerBuffStorage().addBuffsToStorage(player.getId(), player.getAllBuffs());
            player.setDisconnectedFromChannelWorld();
            player.notifyMapTransferToPartner(-1);
            player.removeIncomingInvites();
            player.cancelAllBuffs(true);

            player.closePlayerInteractions();
            player.closePartySearchInteractions();

            if (!serverTransition) {    // thanks MedicOP for detecting an issue with party leader change on changing channels
                removePartyPlayer(wserv);

                EventInstanceManager eim = player.getEventInstance();
                if (eim != null) {
                    eim.playerDisconnected(player);
                }

                if (player.getMonsterCarnival() != null) {
                    player.getMonsterCarnival().playerDisconnected(getPlayer().getId());
                }

                if (player.getAriantColiseum() != null) {
                    player.getAriantColiseum().playerDisconnected(getPlayer());
                }
            }

            if (player.getMap() != null) {
                int mapId = player.getMapId();
                player.getMap().removePlayer(player);
                if (MapId.isDojo(mapId)) {
                    this.getChannelServer().freeDojoSectionIfEmpty(mapId);
                }
                
                if (player.getMap().getHPDec() > 0) {
                    getWorldServer().removePlayerHpDecrease(player);
                }
            }

        } catch (final Throwable t) {
            log.error("账号卡住", t);
        }
    }

    public final void disconnect(final boolean shutdown, final boolean cashshop) {
        if (canDisconnect()) {
            ThreadManager.getInstance().newTask(() -> disconnectInternal(shutdown, cashshop));
        }
    }

    public final void forceDisconnect() {
        if (canDisconnect()) {
            disconnectInternal(true, false);
        }
    }

    public void timeoutDisconnect() {
        disconnectInternal(true, false);
    }

    private synchronized boolean canDisconnect() {
        if (disconnecting) {
            return false;
        }

        disconnecting = true;
        return true;
    }

    /**
     * 断开客户端连接的内部处理方法，每个客户端实例调用一次
     * @param {boolean} shutdown - 是否服务器关闭导致的断开
     * @param {boolean} cashshop - 是否在现金商店中断开连接
     */
    private void disconnectInternal(boolean shutdown, boolean cashshop) {
        // 检查玩家对象是否存在且处于登录状态
        if (player != null && player.isLoggedIn() && player.getClient() != null) {
            // 获取玩家的信使ID（如果存在）
            final int messengerid = player.getMessenger() == null ? 0 : player.getMessenger().getId();
            // 获取好友列表
            final BuddyList bl = player.getBuddylist();
            // 创建信使角色对象
            final MessengerCharacter chrm = new MessengerCharacter(player, 0);
            // 获取公会角色信息
            final GuildCharacter chrg = player.getMGC();
            // 获取公会信息
            final Guild guild = player.getGuild();

            player.cancelMagicDoor();// 取消玩家的魔法门效果

            // 获取世界服务器实例（此时肯定不为空）
            final World wserv = getWorldServer();
            try {
                // 更新玩家在线时间
                player.updateOnlineTime();
                removePlayer(wserv, this.serverTransition);// 从世界服务器移除玩家

                // 处理非频道切换的常规断开情况
                if (!(channel == -1 || shutdown)) {
                    if (!cashshop) { // 非现金商店断开
                        if (!this.serverTransition) { // 非服务器转移状态（非频道切换）
                            if (messengerid > 0) {// 退出信使聊天
                                wserv.leaveMessenger(messengerid, chrm);
                            }

                            player.forfeitExpirableQuests();// 放弃有时限的任务

                            // 处理公会相关操作
                            if (guild != null) {
                                final Server server = Server.getInstance();
                                // 设置公会成员离线状态
                                server.setGuildMemberOnline(player, false, player.getClient().getChannel());
                                // 发送公会信息包
                                player.sendPacket(GuildPackets.showGuildInfo(player));
                            }
                            if (bl != null) {// 更新好友列表的离线状态
                                wserv.loggedOff(player.getName(), player.getId(), channel, player.getBuddylist().getBuddyIds());
                            }
                        }
                    } else { // 现金商店断开
                        if (!this.serverTransition) { // if dc inside of cash shop.
                            if (bl != null) {// 更新好友列表的离线状态
                                wserv.loggedOff(player.getName(), player.getId(), channel, player.getBuddylist().getBuddyIds());
                            }
                        }
                    }
                }
            } catch (final Exception e) {
                log.error("账号卡住", e); // 记录异常信息
            } finally {
                if (!this.serverTransition) {// 非服务器转移状态的清理操作
                    if (chrg != null) {
                        chrg.setCharacter(null);// 清理公会角色引用
                    }
                    getChannelServer().removePlayer(player); //already being done
                    wserv.removePlayer(player);// 从世界服务器移除玩家

                    player.saveCooldowns();// 保存冷却时间
                    player.cancelAllDebuffs();// 取消所有debuff效果
                    player.saveCharToDB(true);// 保存角色数据到数据库（强制保存）

                    player.logOff();// 执行下线操作
                    if (GameConfig.getServerBoolean("instant_name_change")) {
                        player.doPendingNameChange();// 处理即时改名功能
                    }
                    clear();// 清理客户端数据
                } else {
                    // 服务器转移状
                    // 态下的清理操作
                    getChannelServer().removePlayer(player);

                    player.saveCooldowns();
                    player.cancelAllDebuffs();
                    // 保存角色数据到数据库（常规保存）
                    player.saveCharToDB();
                }
            }
        }
        // 关闭会话连接
        SessionCoordinator.getInstance().closeSession(this, true);  //第2个参数改为true才能让客户端退出到登录界面

        // 更新登录状态
        if (!serverTransition && isLoggedIn()) {
            updateLoginState(Client.LOGIN_NOTLOGGEDIN);
            clear();
        } else {
            // 检查服务器是否正在转移该角色
            if (!Server.getInstance().hasCharacteridInTransition(this)) {
                updateLoginState(Client.LOGIN_NOTLOGGEDIN);
            }
            // 清理引擎引用
            engines = null; // thanks Tochi for pointing out a NPE here
        }
    }

    private void clear() {
        // player hard reference removal thanks to Steve (kaito1410)
        if (this.player != null) {
            this.player.empty(true); // clears schedules and stuff
        }

        Server.getInstance().unregisterLoginState(this);

        this.accountName = null;
        this.macs = null;
        this.hwid = null;
        this.birthday = null;
        this.engines = null;
        this.player = null;
    }

    public void setCharacterOnSessionTransitionState(int cid) {
        this.updateLoginState(Client.LOGIN_SERVER_TRANSITION);
        this.inTransition = true;
        Server.getInstance().setCharacteridInTransition(this, cid);
    }

    public int getChannel() {
        return channel;
    }

    public Channel getChannelServer() {
        return Server.getInstance().getChannel(world, channel);
    }

    public World getWorldServer() {
        return Server.getInstance().getWorld(world);
    }

    public Channel getChannelServer(byte channel) {
        return Server.getInstance().getChannel(world, channel);
    }

    public boolean deleteCharacter(int cid, int senderAccId) {
        try {
            Character chr = Character.loadCharFromDB(cid, this, false);

            Integer partyid = chr.getWorldServer().getCharacterPartyid(cid);
            if (partyid != null) {
                this.setPlayer(chr);

                Party party = chr.getWorldServer().getParty(partyid);
                chr.setParty(party);
                chr.getMPC();
                chr.leaveParty();   // thanks Vcoc for pointing out deleted characters would still stay in a party

                this.setPlayer(null);
            }

            return Character.deleteCharFromDB(chr, senderAccId);
        } catch (Exception ex) {
            ex.printStackTrace();
            return false;
        }
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String a) {
        this.accountName = a;
    }

    public void setChannel(int channel) {
        this.channel = channel;
    }

    public int getWorld() {
        return world;
    }

    public void setWorld(int world) {
        this.world = world;
    }

    public void pongReceived() {
        lastPong = System.currentTimeMillis();
    }

    public void checkIfIdle(final IdleStateEvent event) {
        final long pingedAt = System.currentTimeMillis();
        sendPacket(PacketCreator.getPing());
        TimerManager.getInstance().schedule(() -> {
            try {
                if (lastPong < pingedAt) {
                    if (ioChannel.isActive()) {
                        log.info("由于空闲而断开连接 {}。原因：{}", remoteAddress, event.state());
//                        updateLoginState(Client.LOGIN_NOTLOGGEDIN);
//                        disconnectSession();
                        // 按正常的规则去移除这个客户端，避免client被close了，但是对象还在内存中引发后续报错
                        closeMapleSession();
                    }
                }
            } catch (NullPointerException e) {
                e.printStackTrace();
            }
        }, SECONDS.toMillis(15));
    }

    public Set<String> getMacs() {
        return Collections.unmodifiableSet(macs);
    }

    public Set<String> getIps() {
        return Collections.unmodifiableSet(ips);
    }

    /**
     * 从数据库读取指定账号的IP列表
     * @param accId 账号ID
     * @return IP地址列表，如果记录不存在返回空列表
     */
    public List<String> getIpsFromDB(int accId) {
        String sql = "SELECT ip FROM accounts WHERE id = ?";

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, accId);

            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String ipData = rs.getString("ip");
                if (ipData != null && !ipData.trim().isEmpty()) {
                    // 使用更高效的分割方式
                    return Arrays.stream(ipData.split(","))
                            .map(String::trim)
                            .filter(ip -> !ip.isEmpty())
                            .collect(Collectors.toList());
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return new ArrayList<>();
    }
    public int getGMLevel() {
        return gmlevel;
    }

    public void setGMLevel(int level) {
        gmlevel = level;
    }

    public void setScriptEngine(String name, ScriptEngine e) {
        engines.put(name, e);
    }

    public ScriptEngine getScriptEngine(String name) {
        return engines.get(name);
    }

    public void removeScriptEngine(String name) {
        engines.remove(name);
    }

    public NPCConversationManager getCM() {
        return NPCScriptManager.getInstance().getCM(this);
    }

    public QuestActionManager getQM() {
        return QuestScriptManager.getInstance().getQM(this);
    }

    public boolean acceptToS() {
        if (accountName == null) {
            return true;
        }

        boolean disconnect = false;
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("SELECT `tos` FROM accounts WHERE id = ?")) {
                ps.setInt(1, accId);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        if (rs.getByte("tos") == 1) {
                            disconnect = true;
                        }
                    }
                }
            }

            try (PreparedStatement ps = con.prepareStatement("UPDATE accounts SET tos = 1 WHERE id = ?")) {
                ps.setInt(1, accId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return disconnect;
    }

    public void checkChar(int accid) {  /// issue with multiple chars from same account login found by shavit, resinate
        if (!GameConfig.getServerBoolean("use_character_account_check")) {
            return;
        }

        for (World w : Server.getInstance().getWorlds()) {
            for (Character chr : w.getPlayerStorage().getAllCharacters()) {
                if (accid == chr.getAccountId()) {
                    log.warn("玩家 {} 已从世界 {} 中删除。可能存在重复尝试。", chr.getName(), GameConstants.WORLD_NAMES[w.getId()]);
                    chr.getClient().forceDisconnect();
                    w.getPlayerStorage().removePlayer(chr.getId());
                }
            }
        }
    }

    public int getVotePoints() {
        int points = 0;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT `votepoints` FROM accounts WHERE id = ?")) {
            ps.setInt(1, accId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    points = rs.getInt("votepoints");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        votePoints = points;
        return votePoints;
    }

    public void addVotePoints(int points) {
        votePoints += points;
        saveVotePoints();
    }

    public void useVotePoints(int points) {
        if (points > votePoints) {
            //Should not happen, should probably log this
            return;
        }
        votePoints -= points;
        saveVotePoints();
        MapleLeafLogger.log(player, false, Integer.toString(points));
    }

    private void saveVotePoints() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE accounts SET votepoints = ? WHERE id = ?")) {
            ps.setInt(1, votePoints);
            ps.setInt(2, accId);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void lockClient() {
        lock.lock();
    }

    public void unlockClient() {
        lock.unlock();
    }

    public boolean tryacquireClient() {
        if (actionsSemaphore.tryAcquire()) {
            lockClient();
            return true;
        } else {
            return false;
        }
    }

    public void releaseClient() {
        unlockClient();
        actionsSemaphore.release();
    }

    public boolean tryacquireEncoder() {
        if (actionsSemaphore.tryAcquire()) {
            encoderLock.lock();
            return true;
        } else {
            return false;
        }
    }

    public void unlockEncoder() {
        encoderLock.unlock();
        actionsSemaphore.release();
    }

    private static class CharNameAndId {

        public String name;
        public int id;

        public CharNameAndId(String name, int id) {
            super();
            this.name = name;
            this.id = id;
        }
    }

    private static boolean checkHash(String hash, String type, String password) {
        try {
            MessageDigest digester = MessageDigest.getInstance(type);
            digester.update(password.getBytes(StandardCharsets.UTF_8), 0, password.length());
            return HexTool.toHexString(digester.digest()).replace(" ", "").toLowerCase().equals(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("对字符串进行编码失败", e);
        }
    }

    public short getAvailableCharacterSlots() {
        return (short) Math.max(0, characterSlots - Server.getInstance().getAccountCharacterCount(accId));
    }

    public short getAvailableCharacterWorldSlots() {
        return (short) Math.max(0, characterSlots - Server.getInstance().getAccountWorldCharacterCount(accId, world));
    }

    public short getAvailableCharacterWorldSlots(int world) {
        return (short) Math.max(0, characterSlots - Server.getInstance().getAccountWorldCharacterCount(accId, world));
    }

    public short getCharacterSlots() {
        return characterSlots;
    }

    public void setCharacterSlots(byte slots) {
        characterSlots = slots;
    }

    public boolean canGainCharacterSlot() {
        return characterSlots < 15;
    }

    public synchronized boolean gainCharacterSlot() {
        if (canGainCharacterSlot()) {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("UPDATE accounts SET characterslots = ? WHERE id = ?")) {
                ps.setInt(1, this.characterSlots += 1);
                ps.setInt(2, accId);
                ps.executeUpdate();

            } catch (SQLException e) {
                e.printStackTrace();
            }
            return true;
        }
        return false;
    }

    public final byte getGReason() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT `greason` FROM `accounts` WHERE id = ?")) {
            ps.setInt(1, accId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getByte("greason");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public byte getGender() {
        return gender;
    }

    public void setGender(byte m) {
        this.gender = m;

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE accounts SET gender = ? WHERE id = ?")) {
            ps.setByte(1, gender);
            ps.setInt(2, accId);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void announceDisableServerMessage() {
        if (!this.getWorldServer().registerDisabledServerMessage(player.getId())) {
            sendPacket(PacketCreator.serverMessage(""));
        }
    }

    public void announceServerMessage() {
        sendPacket(PacketCreator.serverMessage(this.getChannelServer().getServerMessage()));
    }

    public synchronized void announceBossHpBar(Monster mm, final int mobHash, Packet packet) {
        long timeNow = System.currentTimeMillis();
        int targetHash = player.getTargetHpBarHash();

        if (mobHash != targetHash) {
            if (timeNow - player.getTargetHpBarTime() >= SECONDS.toMillis(5)) {
                // is there a way to INTERRUPT this annoying thread running on the client that drops the boss bar after some time at every attack?
                announceDisableServerMessage();
                sendPacket(packet);

                player.setTargetHpBarHash(mobHash);
                player.setTargetHpBarTime(timeNow);
            }
        } else {
            announceDisableServerMessage();
            sendPacket(packet);

            player.setTargetHpBarTime(timeNow);
        }
    }

    public void sendPacket(Packet packet) {
        announcerLock.lock();
        try {
            ioChannel.writeAndFlush(packet);
        } finally {
            announcerLock.unlock();
        }
    }

    public void announceHint(String msg, int length) {
        sendPacket(PacketCreator.sendHint(msg, length, 10));
        enableActions();
    }

    public void changeChannel(int channel) {
        Server server = Server.getInstance();
        if (player.isBanned()) {
            disconnect(false, false);
            return;
        }
        if (!player.isAlive() || FieldLimit.CANNOTMIGRATE.check(player.getMap().getFieldLimit())) {
            enableActions();
            return;
        } else if (MiniDungeonInfo.isDungeonMap(player.getMapId())) {
            sendPacket(PacketCreator.serverNotice(5, "在迷你地牢内时，更改频道或进入现金商店或拍卖行将被禁用。"));
            enableActions();
            return;
        }

        String[] socket = Server.getInstance().getInetSocket(this, getWorld(), channel);
        if (socket == null) {
            sendPacket(PacketCreator.serverNotice(1, "频道 " + channel + " 当前已禁用。请尝试其他频道。"));
            enableActions();
            return;
        }

        player.closePlayerInteractions();
        player.closePartySearchInteractions();

        player.unregisterChairBuff();
        server.getPlayerBuffStorage().addBuffsToStorage(player.getId(), player.getAllBuffs());
        server.getPlayerBuffStorage().addDiseasesToStorage(player.getId(), player.getAllDiseases());
        player.setDisconnectedFromChannelWorld();
        player.notifyMapTransferToPartner(-1);
        player.removeIncomingInvites();
        player.cancelAllBuffs(true);
        player.cancelAllDebuffs();
        player.cancelBuffExpireTask();
        player.cancelDiseaseExpireTask();
        player.cancelSkillCooldownTask();
        player.cancelQuestExpirationTask();
        //Cancelling magicdoor? Nope
        //Cancelling mounts? Noty

        player.getInventory(InventoryType.EQUIPPED).checked(false); //test
        player.getMap().removePlayer(player);
        player.clearBanishPlayerData();
        player.getClient().getChannelServer().removePlayer(player);

        player.saveCharToDB();

        /*
         saveCharToDB后，数据库中的地图已经保存为ForcedReturnId，如果在当前地图下线，再上线，就会传送到ForcedReturnId对应的地图
         因为玩家登录时会优先取内存中的数据，没有才加载数据库，所以玩家切换频道取的是内存中的数据，而导致没有切换到ForcedReturnId对应的地图
         玩家反馈切换频道不传送ForcedReturnId对应的地图反而比较友好，所以该参数默认为false，想贴近官方可以设置为true
         */
        if (GameConfig.getServerBoolean("change_channel_force_return")) {
            int returnedMapId;
            MapleMap map = player.getMap();
            if (map.getForcedReturnId() != MapId.NONE) {
                returnedMapId = player.getMap().getForcedReturnId();
            } else {
                returnedMapId = player.getHp() < 1 ? map.getReturnMapId() : map.getId();
            }
            player.setMap(getChannelServer((byte) channel).getMapFactory().getMap(returnedMapId));
        }

        player.setSessionTransitionState();
        try {
            sendPacket(PacketCreator.getChannelChange(InetAddress.getByName(socket[0]), Integer.parseInt(socket[1])));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public long getSessionId() {
        return this.sessionId;
    }

    public boolean canRequestCharlist() {
        return lastNpcClick + 877 < Server.getInstance().getCurrentTime();
    }

    public boolean canClickNPC() {
        return lastNpcClick + 500 < Server.getInstance().getCurrentTime();
    }

    public void setClickedNPC() {
        lastNpcClick = Server.getInstance().getCurrentTime();
    }

    public void removeClickedNPC() {
        lastNpcClick = 0;
    }

    public int getVisibleWorlds() {
        return visibleWorlds;
    }

    public void requestedServerlist(int worlds) {
        visibleWorlds = worlds;
        setClickedNPC();
    }

    public void closePlayerScriptInteractions() {
        this.removeClickedNPC();
        NPCScriptManager.getInstance().dispose(this);
        QuestScriptManager.getInstance().dispose(this);
    }

    public boolean attemptCsCoupon() {
        if (csattempt > 2) {
            resetCsCoupon();
            return false;
        }

        csattempt++;
        return true;
    }

    public void resetCsCoupon() {
        csattempt = 0;
    }

    public void enableCSActions() {
        sendPacket(PacketCreator.enableCSUse(player));
    }

    public boolean canBypassPin() {
        return LoginBypassCoordinator.getInstance().canLoginBypass(hwid, accId, false);
    }

    public boolean canBypassPic() {
        return LoginBypassCoordinator.getInstance().canLoginBypass(hwid, accId, true);
    }

    public int getLanguage() {
        return lang;
    }

    public void setLanguage(int lingua) {
        this.lang = lingua;
    }

    /**
     * 通知客户端启用操作，防止假死
     */
    public void enableActions() {
        sendPacket(PacketCreator.enableActions());
    }
}
