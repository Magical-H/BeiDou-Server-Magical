/*
 ZEVMS冒险岛(079)游戏服务端
 */
var status = 0;
var beauty = 0;
var New_Face = Array(21788,21789,21790,21791,21792,21793,21794,21795,21796,21797,21798,21799,21800,21801,21802,21803,21804,21805,21806,21807,21808,21809,21810,21811,21812,21813,21814,21815,21816,21817,21818,21819,21820,21821,21822,21823,21824,21825,21826,21827,21828,21829,21830,21831,21833,21834,21835,21836,21838,21841,21842,21843,21844,21845,21846,21847,21848,21849,21850,21852,21853,21854,21855,21858,21859,21860,21863,21865,21868,21877,21878,21879,21880,21881,21882,21883,21885,21886,21887,21888,21889,21891,21892,21893,21896,21899,24001,24002,24003,24004,24007,24008,24009,24010,24011,24012,24013,24014,24015,24016,24017,24018,24019,24020,24021,24022,24023,24024,24025,24026,24027,24028,24029,24030,24031,24032);


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
