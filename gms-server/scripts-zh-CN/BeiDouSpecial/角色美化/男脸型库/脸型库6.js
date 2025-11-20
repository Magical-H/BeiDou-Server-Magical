/*
 ZEVMS冒险岛(079)游戏服务端
 */
var status = 0;
var beauty = 0;
var New_Face = Array(20777,20778,20780,20781,20782,20783,20784,20785,20786,20787,20788,20789,20790,20791,20792,20793,20794,20795,20796,20797,20798,20799,20800,20801,20802,20803,20804,20805,20806,20807,20808,20809,20810,20811,20812,20813,20814,20815,20816,20817,20818,20819,20820,20821,20822,20823,20824,20825,20826,20827,20828,20829,20830,20831,20832,20833,20835,20836,20837,20838,20840,20843,20844,20845,20846,20847,20848,20849,20850,20851,20852,20853,20854,20855,20856,20857,20860,20862,20863,20864,20868,20870,20873,20878,20880,20881,20882,20883,20884,20885,20886,20887,20888,20889,20890,20891,20892,20893,20895,20896,20897,20898);
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
			cm.openNpc(9900004,'角色美化/整容导航');
            return;
        }
        if (mode == 1)
            status++;
        else
            status--;
        if (status == 0) {
			
			
                  facenew = Array();
                for(var i = 0; i < New_Face.length; i++){
					facenew.push(New_Face[i]);
				}
				cm.sendStyle("选择一个想要的(总共：#r"+New_Face.length+"#k 款脸型)#l", facenew); 

			
			
        } else if (status == 1){			
			
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
