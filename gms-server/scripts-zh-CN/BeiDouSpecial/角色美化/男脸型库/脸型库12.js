/*
 ZEVMS冒险岛(079)游戏服务端
 */
var status = 0;
var beauty = 0;
var New_Face = Array(20033,25056,23878,23083,20061,25006,25689, 20035,23086,23028,25096,26096,25050,25089,25089,25032,25832,23757,23758,23759,23760,23761,23762,23763,23764,23765,23766,23767,23768,23769,23770,23771,23772,23773,23774,23775,23776,23777,23778,23779,23780,23781,23782,23783,23784,23786,23792,23794,23799,23802,23804,23805,23806,23808,23810,23813,23814,23815,23816,23817,23818,23819,23820,23821,23823,23824,23825,23828,23829,23830,23831,23832,23833,23834,23835,23838,23839,23840,23844,23853,23855,23856,23857,23858,23859,23860,23863,23864,23865,23866,23869,23870,23871,23872,23873,23876,23877,23878,23879,23881,23883,23884,23886,23892,23894,23899);


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
