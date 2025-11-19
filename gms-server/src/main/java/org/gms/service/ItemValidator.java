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
package org.gms.service;

import org.gms.server.ItemInformationProvider;

/**
 * 物品验证工具类，用于检测客户端不存在的物品ID
 *
 * @author Assistant
 */
public class ItemValidator {

    private static final ItemInformationProvider itemInfo = ItemInformationProvider.getInstance();

    /**
     * 检查指定的物品ID在客户端是否存在
     *
     * @param itemId 物品ID
     * @return 如果物品存在返回true，否则返回false
     */
    public static boolean isItemExists(int itemId) {
        // 使用ItemInformationProvider的getItemData方法来检查物品是否存在
        // 如果getItemData返回null，则表示该物品在客户端数据中不存在
        return itemInfo.getItemData(itemId) != null;
    }

    /**
     * 检查指定的物品ID在客户端是否不存在
     *
     * @param itemId 物品ID
     * @return 如果物品不存在返回true，否则返回false
     */
    public static boolean isItemNotExists(int itemId) {
        return !isItemExists(itemId);
    }

    /**
     * 获取物品名称，如果物品不存在则返回null
     *
     * @param itemId 物品ID
     * @return 物品名称，如果物品不存在则返回null
     */
    public static String getItemName(int itemId) {
        if (isItemExists(itemId)) {
            return itemInfo.getName(itemId);
        }
        return null;
    }
}