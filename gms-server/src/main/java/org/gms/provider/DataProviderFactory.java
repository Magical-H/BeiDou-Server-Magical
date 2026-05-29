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
package org.gms.provider;

import lombok.extern.slf4j.Slf4j;
import org.gms.provider.wz.WZFiles;
import org.gms.provider.wz.XMLWZFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class DataProviderFactory {
    // 注解：存储具体的 CachingDataProvider 实例，以便进行类型转换和方法调用
    private static final Map<String, CachingDataProvider> providers = new ConcurrentHashMap<>();

    private static CachingDataProvider getWZ(File fileIn) {
        String key = fileIn.toPath().normalize().toString().toLowerCase(Locale.ROOT);
        return providers.computeIfAbsent(key, k -> {
            DataProvider rawProvider = new XMLWZFile(fileIn.toPath());
            return new CachingDataProvider(rawProvider);
        });
    }

    /**
     * 注解：返回具体的 CachingDataProvider 类型，以便上层代码能调用其特有方法。
     */
    public static CachingDataProvider getDataProvider(WZFiles in) {
        Path targetPath = in.getFile();
        Path wzDir = targetPath.getParent();

        if (wzDir == null || !Files.isDirectory(wzDir)) {
            throw new IllegalStateException("WZ目录不存在或不是一个有效的目录，路径: " + targetPath.toAbsolutePath());
        }

        Path finalDir = resolveWzDirectory(wzDir, targetPath.getFileName().toString());

        if (finalDir == null) {
            throw new IllegalStateException(
                    "加载 " + targetPath.getFileName() + " 失败：在 " + wzDir.toAbsolutePath() + " 目录下找不到对应的WZ目录。"
            );
        }

        return getWZ(finalDir.toFile());
    }

    private static Path resolveWzDirectory(Path wzDir, String targetNameWithExt) {
        Path direct = wzDir.resolve(targetNameWithExt);
        if (Files.isDirectory(direct)) {
            return direct;
        }

        Path finalDir = null;
        String targetNameWithoutExt = targetNameWithExt.endsWith(".wz")
                ? targetNameWithExt.substring(0, targetNameWithExt.length() - 3)
                : targetNameWithExt;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(wzDir)) {
            for (Path file : files) {
                if (!Files.isDirectory(file)) {
                    continue;
                }
                String fileName = file.getFileName().toString();
                if (fileName.equalsIgnoreCase(targetNameWithExt)) {
                    return file;
                }
                if (fileName.equalsIgnoreCase(targetNameWithoutExt)) {
                    finalDir = file;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("加载 WZ 目录失败：" + wzDir.toAbsolutePath(), e);
        }
        return finalDir;
    }

    /**
     * 获取所有缓存提供者的Map。
     */
    public static Map<String, CachingDataProvider> getProviders() {
        return providers;
    }
}
