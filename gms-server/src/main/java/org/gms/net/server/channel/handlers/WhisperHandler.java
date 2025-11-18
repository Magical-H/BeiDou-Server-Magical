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

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.autoban.AutobanFactory;
import org.gms.client.command.CommandsExecutor;
import org.gms.config.GameConfig;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.server.ChatLogger;
import org.gms.util.PacketCreator;
import org.gms.util.PacketCreator.WhisperFlag;

/**
 * @author Chronos
 */
public final class WhisperHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(WhisperHandler.class);

    // 结果类型，不确定是否有正确的名称
    public static final byte RT_ITC = 0x00;
    public static final byte RT_SAME_CHANNEL = 0x01;
    public static final byte RT_CASH_SHOP = 0x02;
    public static final byte RT_DIFFERENT_CHANNEL = 0x03;

    @Override
    public void handlePacket(InPacket p, Client c) {
        byte request = p.readByte(); // 读取请求类型
        String name = p.readString(); // 读取目标玩家名称
        Character target = c.getWorldServer().getPlayerStorage().getCharacterByName(name); // 根据名称获取目标玩家对象
        // 如果目标玩家不存在
        if (target == null) {
            c.sendPacket(PacketCreator.getWhisperResult(name, false)); // 向客户端发送查找失败的结果
            return; // 结束方法执行
        }

        switch (request) { // 根据请求类型进行不同的处理
            case WhisperFlag.LOCATION | WhisperFlag.REQUEST -> {// 处理查找玩家位置请求
                if (CommandsExecutor.getInstance().getCommand("online") != null) { // 检查服务器配置是否允许查找
                    handleFind(c.getPlayer(), target, WhisperFlag.LOCATION); // 执行查找玩家位置逻辑
                } else {
                    c.getPlayer().dropMessage(5,"找人指令已被禁用");
                }
            }
            case WhisperFlag.WHISPER | WhisperFlag.REQUEST -> {// 处理发送悄悄话请求
                String message = p.readString(); // 读取悄悄话内容
                handleWhisper(message, c.getPlayer(), target); // 执行发送悄悄话逻辑
            }
            case WhisperFlag.LOCATION_FRIEND | WhisperFlag.REQUEST -> handleFind(c.getPlayer(), target, WhisperFlag.LOCATION_FRIEND); // 执行查找好友位置逻辑
            // 处理未知请求类型
            default -> log.warn("未知请求 {} 由 {} 触发", request, c.getPlayer().getName()); // 记录警告日志
        }
    }

    private void handleFind(Character user, Character target, byte flag) {
        if (user.gmLevel() >= target.gmLevel()) {
            if (target.getCashShop().isOpened()) {
                user.sendPacket(PacketCreator.getFindResult(target, RT_CASH_SHOP, -1, flag));
            } else if (target.getClient().getChannel() == user.getClient().getChannel()) {
                user.sendPacket(PacketCreator.getFindResult(target, RT_SAME_CHANNEL, target.getMapId(), flag));
            } else {
                user.sendPacket(PacketCreator.getFindResult(target, RT_DIFFERENT_CHANNEL, target.getClient().getChannel() - 1, flag));
            }
        } else {
            // 对于悄悄话来说，未找到的结果是相同的消息
            user.sendPacket(PacketCreator.getWhisperResult(target.getName(), false));
        }
    }

    private void handleWhisper(String message, Character user, Character target) {
        if (user.getAutoBanManager().getLastSpam(7) + 200 > currentServerTime()) {
            return;
        }
        user.getAutoBanManager().spam(7);

        if (message.length() > Byte.MAX_VALUE) {
            AutobanFactory.PACKET_EDIT.alert(user, user.getName() + " 尝试通过悄悄话功能修改数据包。");
            log.warn("角色 {} 尝试发送长度为 {} 的文本", user.getName(), message.length());
            user.getClient().disconnect(true, false);
            return;
        }

        ChatLogger.log(user.getClient(), "悄悄话发送至 " + target.getName(), message);

        target.sendPacket(PacketCreator.getWhisperReceive(user.getName(), user.getClient().getChannel() - 1, user.isGM(), message));

        boolean hidden = target.isHidden() && target.gmLevel() > user.gmLevel();
        user.sendPacket(PacketCreator.getWhisperResult(target.getName(), !hidden));
    }
}
