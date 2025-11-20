/*
 小N同学出资，江奈Mizuki整合【脸型脚本】
 */
var status = 0;
var beauty = 0;

var facenew = Array();

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
	if(mode<0){
	cm.dispose();	
	}
	if(selection<0){
        selection= Math.abs(selection);
     }		
    if (mode == -1)
        cm.dispose();
    else {
        if (mode == 0 && status == 0) {
            cm.dispose();
            return;
        }
        if (mode == 1)
            status++;
        else
            status--;
        if (status == 0) {
			var text ="";
            text +="  Hi~ 那边的冒险者！我是时间上手艺最好的整容高人哈. 如果你有 #b#i5152001##t5152001##k 我就可以为你整容哦。 \r\n\r\n"
			//text += " #L1000#购买 #b#i5152001##t5152001#(#r1000#k点卷)#l\r\n\r\n\r\n";
			if (cm.getPlayer().getGender() ==0) {//男 
				
 			text += "#L1# 指定脸型第 #r1#k 版(男用)#L2# 指定脸型第 #r2#k 版(男用)\r\n\r\n";
			text += "#L3# 指定脸型第 #r3#k 版(男用)#L4# 指定脸型第 #r4#k 版(男用)\r\n\r\n";
			text += "#L5# 指定脸型第 #r5#k 版(男用)#L6# 指定脸型第 #r6#k 版(男用)\r\n\r\n";
			text += "#L7# 指定脸型第 #r7#k 版(男用)#L8# 指定脸型第 #r8#k 版(男用)\r\n\r\n";
			text += "#L9# 指定脸型第 #r9#k 版(男用)#L10# 指定脸型第 #r10#k版(男用)\r\n\r\n";
			text += "#L11# 指定脸型第 #r11#k版(男用)#L12# 指定脸型第 #r12#k版(男用)\r\n\r\n"; 
			
			}else{//女
			text += "#L13# 指定脸型第 #r1#k 版(女用)#L14# 指定脸型第 #r2#k 版(女用)\r\n\r\n";
			text += "#L15# 指定脸型第 #r3#k 版(女用)#L16# 指定脸型第 #r4#k 版(女用)\r\n\r\n";
			text += "#L17# 指定脸型第 #r5#k 版(女用)#L18# 指定脸型第 #r6#k 版(女用)\r\n\r\n";
			text += "#L19# 指定脸型第 #r7#k 版(女用)#L20# 指定脸型第 #r8#k 版(女用)\r\n\r\n";
			text += "#L21# 指定脸型第 #r9#k 版(女用)#L22# 指定脸型第 #r10#k版(女用)\r\n\r\n";
			text += "#L23# 指定脸型第 #r11#k版(女用)#L24# 指定脸型第 #r12#k版(女用)\r\n\r\n";		
			}
 			

			 
		
			
			
            cm.sendSimple(text);
        } else if (status == 1) {
			
            if (selection == 1) {
				//cm.dispose();
				//cm.openNpc("角色美化/男脸型库/脸型库1");
				cm.dispose();
				cm.openNpc(1052005, "角色美化/男脸型库/脸型库1");
            } else if (selection == 2) {
				cm.dispose();
				cm.openNpc(1052005,'角色美化/男脸型库/脸型库2');
            } else if (selection == 3) {
				cm.dispose();
				cm.openNpc(1052005,'角色美化/男脸型库/脸型库3');
            } else if (selection == 4) {
				cm.dispose();
				cm.openNpc(1052005,'角色美化/男脸型库/脸型库4');
            } else if (selection == 5) {
				cm.dispose();
				cm.openNpc(1052005,'角色美化/男脸型库/脸型库5');
            } else if (selection == 6) {
				cm.dispose();
				cm.openNpc(1052005,'角色美化/男脸型库/脸型库6');
            } else if (selection == 7) {
				cm.dispose();
				cm.openNpc(1052005,'角色美化/男脸型库/脸型库7');
            } else if (selection == 8) {
				cm.dispose();
				cm.openNpc(1052005,'角色美化/男脸型库/脸型库8');
            } else if (selection == 9) {				
				cm.dispose();
				cm.openNpc(1052005,'角色美化/男脸型库/脸型库9');
            } else if (selection == 10) {				
				cm.dispose();
				cm.openNpc(1052005,'角色美化/男脸型库/脸型库10');
            } else if (selection == 11) {				
				cm.dispose();
				cm.openNpc(1052005,'角色美化/男脸型库/脸型库11');
            } else if (selection == 12) {				
				cm.dispose();
				cm.openNpc(1052005,'角色美化/男脸型库/脸型库12');
			


            } else if (selection == 13) {				
				cm.dispose();
				cm.openNpc(1052005,'角色美化/女脸型库/脸型库1');
            } else if (selection == 14) {				
				cm.dispose();
				cm.openNpc(1052005,'角色美化/女脸型库/脸型库2');
            } else if (selection == 15) {				
				cm.dispose();
				cm.openNpc(1052005,'角色美化/女脸型库/脸型库3');
            } else if (selection == 16) {				
				cm.dispose();
				cm.openNpc(1052005,'角色美化/女脸型库/脸型库4');
            } else if (selection == 17) {				
				cm.dispose();
				cm.openNpc(1052005,'角色美化/女脸型库/脸型库5');
            } else if (selection == 18) {				
				cm.dispose();
				cm.openNpc(1052005,'角色美化/女脸型库/脸型库6');
            } else if (selection == 19) {				
				cm.dispose();
				cm.openNpc(1052005,'角色美化/女脸型库/脸型库7');
            } else if (selection == 20) {				
				cm.dispose();
				cm.openNpc(1052005,'角色美化/女脸型库/脸型库8');
            } else if (selection == 21) {				
				cm.dispose();
				cm.openNpc(1052005,'角色美化/女脸型库/脸型库9');
            } else if (selection == 22) {				
				cm.dispose();
				cm.openNpc(1052005,'角色美化/女脸型库/脸型库10');
            } else if (selection == 23) {				
				cm.dispose();
				cm.openNpc(1052005,'角色美化/女脸型库/脸型库11');
            } else if (selection == 24) {				
				cm.dispose();
				cm.openNpc(1052005,'角色美化/女脸型库/脸型库12');
				
			
			
			
			
			
			
			} else if (selection == 1000) {
					
				
 				if (cm.getPlayer().getCSPoints(1) > 10000){
					cm.gainNX(-1000);
					cm.gainItem(5152001,1);
					cm.sendOk("购买成功！快去享受吧！");
					status=-1;
				} else {
					cm.sendOk("点卷不足哦！");
					status=-1;					
				} 
       
				
            } 
			
			
			
        } else if (status == 2){			
			
             cm.dispose();
            if (cm.haveItem(5152001) == true){
                cm.gainItem(5152001, -1);
                cm.setFace(facenew[selection]);
                cm.sendOk("你看看还满意吗？");
            }else{
				cm.sendOk("您貌似没有#b#z5152001##k..");
			} 
			
        }	 		

      
    }
}
