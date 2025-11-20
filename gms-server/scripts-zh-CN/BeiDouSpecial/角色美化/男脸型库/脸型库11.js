/*
 ZEVMS冒险岛(079)游戏服务端
 */
var status = 0;
var beauty = 0;
var New_Face = Array(23663,23664,23665,23666,23667,23668,23669,23670,23671,23672,23673,23674,23675,23676,23677,23678,23679,23680,23681,23682,23683,23684,23686,23692,23694,23699,23700,23701,23702,23703,23704,23705,23706,23708,23710,23711,23712,23713,23714,23715,23716,23717,23718,23719,23720,23721,23723,23724,23725,23726,23727,23728,23729,23730,23731,23732,23733,23734,23735,23738,23739,23740,23741,23742,23743,23744,23753,23754,23755,23756);

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
