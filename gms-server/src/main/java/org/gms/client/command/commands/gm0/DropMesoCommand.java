package org.gms.client.command.commands.gm0;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.config.GameConfig;
import org.gms.net.server.Server;

public class DropMesoCommand extends Command {
    {
        setDescription("快速丢出金币，方便使用金钱炸弹");
    }

    static long Sleep;

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();

        int maxCount = GameConfig.getServerInt("command_dropmeso_maxcount"); // 次数上限
        int CD = GameConfig.getServerInt("command_dropmeso_useinterval") * 1000; // 指令使用间隔
        int itemCount = player.getMap().getDroppedItemCount();              //当前地图掉落物品数
        int itemMaxCount = GameConfig.getServerInt("item_limit_on_map");    //地图允许掉落上限

        String text = "使用方法为 @丢金币 数量 次数 ，单次最低10金币，最高不能超过5万金币，总次数不能超过 " + maxCount + " 次" + (CD > 0 ? "，使用间隔 " + CD + " 秒" : "");

        if (maxCount == 0) {
            player.yellowMessage("该指令已被禁用");
            return;
        } else if (params.length < 1) {
            player.yellowMessage(text);
            return;
        } else if (Server.getInstance().getCurrentTime() - Sleep < CD) {
            double remainingSeconds = (CD - (Server.getInstance().getCurrentTime() - Sleep)) / 1000.0;
            player.dropMessage(5, "指令冷却中，剩余 " + String.format("%.2f", remainingSeconds) + " 秒");
            return;
        }
        Sleep = Server.getInstance().getCurrentTime();

        try {
            int meso = Math.min(Math.max(Integer.parseInt(params[0]),10),50000); //至少10金币，至多50000金币
            int count = params.length == 2 ? Math.min(Math.max(Integer.parseInt(params[1]),1),maxCount) : 1; //最少1次，最多100次
            int mesoSum = meso * count;
            String dropCountMsg = "";

            if (itemCount + count > itemMaxCount) {
                    player.dropMessage(5,"将要丢弃的数量已超过地图物品掉落数量上限，无法继续丢出！");
                    return;
            } else if (player.getMeso() < mesoSum) {
                player.dropMessage(5,"你需要至少 " + mesoSum + " 金币才能够丢出 " + count + " 次");
                return;
            }

//            if (c.tryacquireClient()) {
                for (int i = 0; i < count; i++) {
                    if (!player.isAlive()) {
                        player.dropMessage(5, "死人是不能丢金币的！" + dropCountMsg);
                        return;
                    } else if (player.getMeso() < meso) {
                        player.dropMessage(5, "穷鬼你钱呢？丢着丢着就没钱了？那你丢个锤子丢？" + dropCountMsg);
                        return;
                    } else if (player.isLoggedInWorld()) { //角色在线才可以丢
                        player.gainMeso(-meso, false, true, false);
                        player.getMap().spawnMesoDrop(meso, player.getPosition(), player, player, true, (byte) 2);
                        dropCountMsg = "已经丢了 " + i + " 次，共计 " + (i * meso) + " 金币";
                        Thread.sleep(1);
                    }
                }
                player.yellowMessage("成功丢出 " + count + " 次，共计 " + mesoSum + " 金币");
//            } else {
//                c.unlockClient();
//                c.enableActions();
//                return;
//            }
        } catch (Exception e) {
            player.dropMessage(5,"输入错误，"+text);
            throw new RuntimeException("@丢金币 指令运行出错",e);
        }
    }
}
