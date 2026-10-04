#!/usr/bin/env bash
# 端到端验证脚本：覆盖登记→上报→审核→派单→称重→处置→计费→投诉→追责全链路，
# 以及满溢占车位投诉的证据链定责、赔付责任分摊（清运扣款/押金扣除补缴/物业承担）与四方说明
# 前置：应用已启动（默认 http://localhost:8080），数据库为全新状态（演示数据已初始化）
set -uo pipefail

BASE=${BASE:-http://localhost:8080}
PASS=0; FAIL=0

check() { # $1=描述 $2=实际内容 $3=期望包含的子串
  if [[ "$2" == *"$3"* ]]; then
    PASS=$((PASS+1)); echo "  ✔ $1"
  else
    FAIL=$((FAIL+1)); echo "  ✘ $1"; echo "    期望包含: $3"; echo "    实际: $(echo "$2" | head -c 400)"
  fi
}

check_absent() { # $1=描述 $2=实际内容 $3=不应包含的子串
  if [[ "$2" != *"$3"* ]]; then
    PASS=$((PASS+1)); echo "  ✔ $1"
  else
    FAIL=$((FAIL+1)); echo "  ✘ $1"; echo "    不应包含: $3"; echo "    实际: $(echo "$2" | head -c 400)"
  fi
}

login() { curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$2\"}" | jq -r '.data.token'; }

J() { jq -r "$1" 2>/dev/null; }

TODAY=$(date +%F)
YESTERDAY=$(date -d "yesterday" +%F)
TOMORROW=$(date -d "tomorrow" +%F)
NEXT_MONDAY=$(date -d "next monday" +%F)
NOW=$(date +%FT%T)
PLUS2H=$(date -d "+2 hours" +%FT%T)
PERIOD=$(date +%Y-%m)

echo "== 0. 认证与鉴权 =="
UNAUTH=$(curl -s "$BASE/api/registrations")
check "未登录访问返回 401" "$UNAUTH" "未登录"
OWNER_T=$(login owner1 123456);  OWNER2_T=$(login owner2 123456)
WORKER_T=$(login worker1 123456); PROP_T=$(login property1 123456)
COLL_T=$(login collector1 123456); FIN_T=$(login finance1 123456)
check "六个角色登录获取令牌" "$([ -n "$OWNER_T" ] && [ -n "$FIN_T" ] && echo ok)" "ok"
ME=$(curl -s "$BASE/api/auth/me" -H "Authorization: Bearer $OWNER_T")
check "当前用户为张三(OWNER)" "$ME" '"name":"张三"'

AUTH_PROP="Authorization: Bearer $PROP_T"; AUTH_COLL="Authorization: Bearer $COLL_T"
AUTH_FIN="Authorization: Bearer $FIN_T"; AUTH_OWNER="Authorization: Bearer $OWNER_T"
AUTH_WORKER="Authorization: Bearer $WORKER_T"; CT="Content-Type: application/json"

echo "== 1. 主流程：上报→审核→派单→称重→处置→计费 =="
R1=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":1,\"reportDate\":\"$TODAY\",\"bagCount\":10,\"extraBagCount\":2,\"stackingLocation\":\"1号楼垃圾点旁\",\"wastePointId\":1,\"containsWood\":true,\"containsGlass\":true,\"containsTile\":true,\"containsMixedHousehold\":false}")
REPORT1=$(echo "$R1" | J '.data.id')
check "施工队每日上报(10袋含2加袋)" "$R1" '"status":"PENDING"'

A1=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORT1,\"approve\":true,\"apptDate\":\"$TODAY\",\"timeSlot\":\"AM\",\"comment\":\"同意清运\"}")
APPT1=$(echo "$A1" | J '.data.id')
check "物业审核通过生成预约" "$A1" '"status":"APPROVED"'

D1=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT1,\"vehicleId\":1,\"plannedArrival\":\"$PLUS2H\",\"routeInfo\":\"东门进→1号楼→西门出\"}")
DISP1=$(echo "$D1" | J '.data.id')
check "清运公司派单(含门禁放行单号)" "$D1" '"gatePassNo":"GP'

ARR=$(curl -s -X POST "$BASE/api/dispatches/$DISP1/arrive" -H "$AUTH_COLL" -H "$CT" -d "{\"actualArrival\":\"$NOW\"}")
check "司机到场登记(准时)" "$ARR" '"lateFlag":false'

W1=$(curl -s -X POST "$BASE/api/weigh-records" -H "$AUTH_COLL" -H "$CT" -d "{\"dispatchId\":$DISP1,\"emptyWeightKg\":5000,\"loadedWeightKg\":5250,\"entryTime\":\"$NOW\",\"exitTime\":\"$(date +%FT%T)\",\"photos\":\"http://img/1.jpg\",\"manifestNo\":\"MF-TEST-001\"}")
WEIGH1=$(echo "$W1" | J '.data.id')
check "进出场称重(净重250kg无异常)" "$W1" '"anomalyFlag":false'

RC=$(curl -s -X POST "$BASE/api/disposal-receipts" -H "$AUTH_COLL" -H "$CT" -d "{\"weighRecordId\":$WEIGH1,\"disposalSite\":\"城东建筑垃圾处置场\",\"receivedWeightKg\":250}")
check "处置场回执" "$RC" '"receiptNo":"RC'

sleep 1  # 等待计费事件处理
BILLS=$(curl -s "$BASE/api/bills?type=OWNER" -H "$AUTH_FIN")
check "业主账单=88元(8袋×8+2加袋×8×1.5)" "$BILLS" '"amount":88.00'
check "业主账单押金抵扣已结清" "$BILLS" '"status":"PAID"'
OWNER_BILL=$(echo "$BILLS" | J '[.data[] | select(.appointmentId=='"$APPT1"')][0].id')
CBILLS=$(curl -s "$BASE/api/bills?type=COLLECTOR" -H "$AUTH_FIN")
check "清运公司结算单=30元(0.25吨×120)" "$CBILLS" '"amount":30.00'
COLL_BILL=$(echo "$CBILLS" | J '[.data[] | select(.appointmentId=='"$APPT1"')][0].id')
PAY=$(curl -s -X POST "$BASE/api/bills/$COLL_BILL/pay" -H "$AUTH_FIN")
check "财务结清清运公司账单" "$PAY" '"status":"PAID"'

TRACE=$(curl -s "$BASE/api/trace/appointments/$APPT1" -H "$AUTH_PROP")
for kw in SUBMIT_REPORT REVIEW_APPROVE DISPATCH_CREATE WEIGH_IN_OUT DISPOSAL_RECEIPT BILL_GENERATE '"status":"SETTLED"' 张三 李工 王经理 刘师傅; do
  check "全链路追溯包含 $kw" "$TRACE" "$kw"
done
TCODE=$(echo "$TRACE" | J '.data.bagTrace.traceCode')
check "按追溯码查询" "$(curl -s "$BASE/api/trace/code/$TCODE" -H "$AUTH_PROP")" '"apptNo"'

echo "== 2. 容量规则：接近上限拒绝→临时围挡→通过 =="
REG2=$(curl -s -X POST "$BASE/api/registrations" -H "Authorization: Bearer $OWNER2_T" -H "$CT" -d "{\"building\":\"2栋\",\"roomNumber\":\"502\",\"constructionTeam\":\"宏远装修队\",\"expectedStartDate\":\"$TODAY\",\"expectedEndDate\":\"$(date -d '+20 days' +%F)\",\"wasteTypes\":\"瓷砖,混凝土\",\"frequency\":\"DAILY\",\"depositAmount\":500}" | J '.data.id')
check "业主2登记(押金500)" "$([ -n "$REG2" ] && [ "$REG2" != "null" ] && echo ok)" "ok"

R2=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":$REG2,\"reportDate\":\"$TODAY\",\"bagCount\":10,\"extraBagCount\":0,\"stackingLocation\":\"2号楼垃圾点\",\"wastePointId\":2,\"containsWood\":false,\"containsGlass\":false,\"containsTile\":true,\"containsMixedHousehold\":false}")
REPORT2=$(echo "$R2" | J '.data.id')

A2=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORT2,\"approve\":true,\"apptDate\":\"$NEXT_MONDAY\",\"timeSlot\":\"AM\",\"comment\":\"\"}")
check "垃圾点接近上限→不简单通过" "$A2" '"status":"REJECTED_CAPACITY"'
check "提示错峰堆放" "$A2" "错峰"
check "提示临时围挡" "$A2" "围挡"

FENCE=$(curl -s -X POST "$BASE/api/waste-points/2/fence" -H "$AUTH_PROP" -H "$CT" -d '{"enable":true,"extraCapacity":20}')
check "物业安排临时围挡扩容+20" "$FENCE" '"tempFence":true'

A2B=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORT2,\"approve\":true,\"apptDate\":\"$NEXT_MONDAY\",\"timeSlot\":\"AM\",\"comment\":\"已围挡\"}")
APPT2=$(echo "$A2B" | J '.data.id')
check "围挡扩容后审核通过" "$A2B" '"status":"APPROVED"'

D2X=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT2,\"vehicleId\":3,\"plannedArrival\":\"${NEXT_MONDAY}T10:00:00\"}")
check "尾号6车辆周一限行被拒" "$D2X" "限行"

D2=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT2,\"vehicleId\":1,\"plannedArrival\":\"${NEXT_MONDAY}T10:00:00\"}")
DISP2=$(echo "$D2" | J '.data.id')
check "更换车辆派单成功" "$D2" '"status":"DISPATCHED"'

W2=$(curl -s -X POST "$BASE/api/weigh-records" -H "$AUTH_COLL" -H "$CT" -d "{\"dispatchId\":$DISP2,\"emptyWeightKg\":5000,\"loadedWeightKg\":5400,\"entryTime\":\"$NOW\",\"exitTime\":\"$(date +%FT%T)\",\"photos\":\"http://img/2.jpg\"}")
check "称重偏差60%→标记异常" "$W2" '"anomalyFlag":true'
sleep 1
CPLS=$(curl -s "$BASE/api/complaints" -H "$AUTH_PROP")
check "称重异常自动生成投诉" "$CPLS" '"type":"WEIGH_ANOMALY"'

echo "== 3. 违规：消防通道堆放+混入生活垃圾→押金扣款→施工队账单 =="
R3=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":$REG2,\"reportDate\":\"$TOMORROW\",\"bagCount\":5,\"extraBagCount\":0,\"stackingLocation\":\"2号楼消防通道口\",\"wastePointId\":2,\"containsWood\":false,\"containsGlass\":false,\"containsTile\":false,\"containsMixedHousehold\":true}")
REPORT3=$(echo "$R3" | J '.data.id')
sleep 1
VIO=$(curl -s "$BASE/api/violations" -H "$AUTH_PROP")
check "消防通道堆放违规" "$VIO" '"type":"FIRE_LANE_STACKING"'
check "混入生活垃圾违规" "$VIO" '"type":"MIXED_HOUSEHOLD_WASTE"'
DEP=$(curl -s "$BASE/api/registrations/$REG2/deposit-log" -H "$AUTH_PROP")
check "押金扣款200(消防通道)" "$DEP" '"amount":200.00'
check "押金扣款100(混入生活垃圾)" "$DEP" '"amount":100.00'
REG2INFO=$(curl -s "$BASE/api/registrations/$REG2" -H "$AUTH_PROP")
check "押金余额500-300-80(流程B清运费)=120" "$REG2INFO" '"depositBalance":120.00'
TEAM_BILLS=$(curl -s "$BASE/api/bills?type=CONSTRUCTION_TEAM" -H "$AUTH_FIN")
check "施工队罚款账单生成" "$TEAM_BILLS" '"billType":"CONSTRUCTION_TEAM"'
TEAM_BILL=$(echo "$TEAM_BILLS" | J '[.data[] | select(.amount==200)][0].id')
PAYT=$(curl -s -X POST "$BASE/api/bills/$TEAM_BILL/pay" -H "$AUTH_FIN")
check "施工队缴清罚款" "$PAYT" '"status":"PAID"'
REG2INFO2=$(curl -s "$BASE/api/registrations/$REG2" -H "$AUTH_PROP")
check "罚款返还业主押金→320" "$REG2INFO2" '"depositBalance":320.00'

A3=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORT3,\"approve\":true,\"apptDate\":\"$YESTERDAY\",\"timeSlot\":\"PM\",\"comment\":\"限期清理\"}")
check "违规上报仍可审核(昨日预约→超时未清)" "$A3" '"status":"APPROVED"'

echo "== 4. 费用争议：投诉→账单冻结→财务调整→押金回冲 =="
CD=$(curl -s -X POST "$BASE/api/complaints" -H "$AUTH_OWNER" -H "$CT" -d "{\"type\":\"FEE_DISPUTE\",\"billId\":$OWNER_BILL,\"description\":\"加袋费用有异议，要求复核\"}")
CPL1=$(echo "$CD" | J '.data.id')
check "业主发起费用争议投诉" "$CD" '"status":"OPEN"'
BILL_NOW=$(curl -s "$BASE/api/bills/$OWNER_BILL" -H "$AUTH_FIN")
check "账单自动转为争议状态" "$BILL_NOW" '"status":"DISPUTED"'
ADJ=$(curl -s -X POST "$BASE/api/bills/$OWNER_BILL/adjust" -H "$AUTH_FIN" -H "$CT" -d '{"newAmount":60,"reason":"复核后加袋按普通袋计费"}')
check "财务调整账单88→60" "$ADJ" '"amount":60.00'
REG1INFO=$(curl -s "$BASE/api/registrations/1" -H "$AUTH_PROP")
check "差额28元回冲押金(1912→1940)" "$REG1INFO" '"depositBalance":1940.00'
HDL=$(curl -s -X PUT "$BASE/api/complaints/$CPL1/handle" -H "$AUTH_PROP" -H "$CT" -d '{"action":"RESOLVED","resolution":"已复核调整账单并退还差额"}')
check "物业办结投诉" "$HDL" '"status":"RESOLVED"'

ODOR=$(curl -s -X POST "$BASE/api/complaints" -H "Authorization: Bearer $OWNER2_T" -H "$CT" -d "{\"type\":\"ODOR\",\"targetAppointmentId\":$APPT1,\"description\":\"垃圾点异味扰民\"}")
check "邻居投诉异味" "$ODOR" '"complaintNo":"CPL'

echo "== 5. 三端看板 =="
DP=$(curl -s "$BASE/api/dashboard/property" -H "$AUTH_PROP")
check "物业看板-楼栋统计" "$DP" '"building":"1栋"'
check "物业看板-超时未清" "$DP" '"overdueAppointments":[{'
check "物业看板-清运公司履约" "$DP" '"company":"绿洁清运"'
DC=$(curl -s "$BASE/api/dashboard/collector" -H "$AUTH_COLL")
check "清运看板-派单与车辆" "$DC" '"vehicles"'
DF=$(curl -s "$BASE/api/dashboard/finance?period=$PERIOD" -H "$AUTH_FIN")
check "财务看板-四类账单汇总" "$DF" '"PROPERTY_ADVANCE"'

echo "== 6. 投诉赔付与责任分摊：满溢占车位索赔 =="
PLUS2D=$(date -d "+2 days" +%F)
PLANDATE=$PLUS2D
# 沪B67890(车2) 尾号 0 周五限行、沪C11116(车3) 尾号 6 周一限行：周五用车3，其余用车2
if [ "$(date +%u)" = "5" ]; then VEH=3; else VEH=2; fi
if [ "$VEH" = "2" ] && [ "$(date -d "$PLANDATE" +%u)" = "5" ]; then PLANDATE=$(date -d "+3 days" +%F); fi
PAST3H=$(date -d "-3 hours" +%FT%T)

echo "-- 6a. 主因清运延误 → 赔付计入清运公司履约扣款 --"
R5=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":1,\"reportDate\":\"$TOMORROW\",\"bagCount\":6,\"extraBagCount\":0,\"stackingLocation\":\"1号楼垃圾点旁\",\"wastePointId\":1,\"containsWood\":true,\"containsGlass\":false,\"containsTile\":false,\"containsMixedHousehold\":false}")
REPORT5=$(echo "$R5" | J '.data.id')
check "业主1再次上报6袋(1号楼点)" "$R5" '"status":"PENDING"'
A5=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORT5,\"approve\":true,\"apptDate\":\"$TODAY\",\"timeSlot\":\"PM\",\"comment\":\"同意清运\"}")
APPT5=$(echo "$A5" | J '.data.id')
check "物业审核通过" "$A5" '"status":"APPROVED"'
D5=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT5,\"vehicleId\":$VEH,\"plannedArrival\":\"$PAST3H\"}")
DISP5=$(echo "$D5" | J '.data.id')
check "清运公司派单(计划3小时前)" "$D5" '"status":"DISPATCHED"'
ARR5=$(curl -s -X POST "$BASE/api/dispatches/$DISP5/arrive" -H "$AUTH_COLL" -H "$CT" -d "{\"actualArrival\":\"$NOW\"}")
check "司机迟到3小时到场" "$ARR5" '"lateFlag":true'

BAD1=$(curl -s -X POST "$BASE/api/complaints" -H "Authorization: Bearer $OWNER2_T" -H "$CT" -d '{"type":"OVERFLOW_PARKING","claimAmount":100,"description":"缺少垃圾点"}')
check "满溢占车位投诉缺垃圾点被拒" "$BAD1" "wastePointId"
BAD2=$(curl -s -X POST "$BASE/api/complaints" -H "Authorization: Bearer $OWNER2_T" -H "$CT" -d '{"type":"OVERFLOW_PARKING","wastePointId":1,"description":"缺少主张金额"}')
check "满溢占车位投诉缺主张金额被拒" "$BAD2" "claimAmount"
CP=$(curl -s -X POST "$BASE/api/complaints" -H "Authorization: Bearer $OWNER2_T" -H "$CT" -d '{"type":"OVERFLOW_PARKING","wastePointId":1,"claimAmount":200,"description":"垃圾点长期满溢，私家车车位被占，要求减免物业费"}')
CPL2=$(echo "$CP" | J '.data.id')
check "邻居发起满溢占车位索赔投诉" "$CP" '"type":"OVERFLOW_PARKING"'

AS=$(curl -s -X POST "$BASE/api/complaints/$CPL2/assess" -H "$AUTH_PROP" -H "$CT" -d '{}')
check "串联证据自动定责为清运公司" "$AS" '"liableParty":"COLLECTOR"'
check "生成赔付责任分摊单号" "$AS" '"caseNo":"CMP'
check "扣款来源为清运公司履约扣款" "$AS" '履约扣款'
check "证据链含预约记录" "$AS" 'appointments'
check "证据链含清运迟到" "$AS" 'lateDispatches'
check "证据链含施工队实际堆放" "$AS" 'reports'
CST=$(curl -s "$BASE/api/complaints" -H "$AUTH_PROP" | jq -r '.data[] | select(.id=='"$CPL2"').status')
check "定责后投诉联动办结" "$CST" "RESOLVED"

DBILLS=$(curl -s "$BASE/api/bills?type=COLLECTOR_DEDUCTION" -H "$AUTH_FIN")
check "清运公司履约扣款单200元" "$DBILLS" '"amount":200.00'
check "扣款单挂到绿洁清运" "$DBILLS" '绿洁清运'
DBILL=$(echo "$DBILLS" | J '[.data[] | select(.amount==200)][0].id')
PAYD=$(curl -s -X POST "$BASE/api/bills/$DBILL/pay" -H "$AUTH_FIN")
check "财务确认履约扣款" "$PAYD" '"status":"PAID"'
DP2=$(curl -s "$BASE/api/dashboard/property" -H "$AUTH_PROP")
check "清运履约看板计入赔付扣款" "$DP2" '"compensationDeductions":200.00'

V_NEIGH=$(curl -s "$BASE/api/complaints/$CPL2/compensation" -H "Authorization: Bearer $OWNER2_T")
check "投诉人看到处理结果说明" "$V_NEIGH" "neighborStatement"
check "投诉人看到物业费减免入账" "$V_NEIGH" "物业费减免"
check_absent "投诉人不可见财务说明" "$V_NEIGH" "financeStatement"
V_COLL=$(curl -s "$BASE/api/complaints/$CPL2/compensation" -H "$AUTH_COLL")
check "清运公司看到履约扣款说明" "$V_COLL" "collectorStatement"
V_FIN=$(curl -s "$BASE/api/complaints/$CPL2/compensation" -H "$AUTH_FIN")
check "财务看到扣款来源" "$V_FIN" "扣款来源"
check "财务看到入账去向" "$V_FIN" "入账去向"
V_NONE=$(curl -s "$BASE/api/complaints/$CPL2/compensation" -H "$AUTH_OWNER")
check "无关人员不可见赔付单" "$V_NONE" "无权查看"

echo "-- 6b. 主因业主超量未申报 → 押金扣除并通知补缴 --"
R6=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":$REG2,\"reportDate\":\"$PLUS2D\",\"bagCount\":5,\"extraBagCount\":0,\"stackingLocation\":\"3号楼临时堆放点\",\"wastePointId\":3,\"containsWood\":false,\"containsGlass\":false,\"containsTile\":true,\"containsMixedHousehold\":false}")
REPORT6=$(echo "$R6" | J '.data.id')
check "业主2施工队上报5袋(3号楼点)" "$R6" '"status":"PENDING"'
A6=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORT6,\"approve\":true,\"apptDate\":\"$PLANDATE\",\"timeSlot\":\"AM\",\"comment\":\"同意清运\"}")
APPT6=$(echo "$A6" | J '.data.id')
check "物业审核通过" "$A6" '"status":"APPROVED"'
D6=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT6,\"vehicleId\":$VEH,\"plannedArrival\":\"${PLANDATE}T09:00:00\"}")
DISP6=$(echo "$D6" | J '.data.id')
check "清运公司派单" "$D6" '"status":"DISPATCHED"'
ARR6=$(curl -s -X POST "$BASE/api/dispatches/$DISP6/arrive" -H "$AUTH_COLL" -H "$CT" -d "{\"actualArrival\":\"${PLANDATE}T09:05:00\"}")
check "司机准时到场" "$ARR6" '"lateFlag":false'
W6=$(curl -s -X POST "$BASE/api/weigh-records" -H "$AUTH_COLL" -H "$CT" -d "{\"dispatchId\":$DISP6,\"emptyWeightKg\":5000,\"loadedWeightKg\":5250,\"entryTime\":\"$NOW\",\"exitTime\":\"$(date +%FT%T)\",\"photos\":\"http://img/6.jpg\"}")
check "净重250kg远超申报125kg→异常" "$W6" '"anomalyFlag":true'
sleep 1  # 等待计费：业主账单40元，REG2押金 320-40=280

CP2=$(curl -s -X POST "$BASE/api/complaints" -H "$AUTH_OWNER" -H "$CT" -d '{"type":"OVERFLOW_PARKING","wastePointId":3,"claimAmount":150,"description":"3号楼堆放点满溢占车位，要求减免物业费"}')
CPL3=$(echo "$CP2" | J '.data.id')
check "邻居张三发起满溢占车位投诉" "$CP2" '"type":"OVERFLOW_PARKING"'
AS2=$(curl -s -X POST "$BASE/api/complaints/$CPL3/assess" -H "$AUTH_PROP" -H "$CT" -d '{}')
check "串联证据自动定责为业主" "$AS2" '"liableParty":"OWNER"'
check "认定超量堆放未申报" "$AS2" '超量'
REG2B=$(curl -s "$BASE/api/registrations/$REG2" -H "$AUTH_PROP")
check "赔付从押金扣除150(280→130)" "$REG2B" '"depositBalance":130.00'
DEP2=$(curl -s "$BASE/api/registrations/$REG2/deposit-log" -H "$AUTH_PROP")
check "押金流水含赔付扣款COMP" "$DEP2" '"type":"COMP"'
RBILLS=$(curl -s "$BASE/api/bills?type=OWNER_REPLENISH" -H "$AUTH_FIN")
check "生成业主押金补缴单150元" "$RBILLS" '"amount":150.00'
RBILL=$(echo "$RBILLS" | J '[.data[] | select(.amount==150)][0].id')
V_OWNER=$(curl -s "$BASE/api/complaints/$CPL3/compensation" -H "Authorization: Bearer $OWNER2_T")
check "责任业主看到补缴通知" "$V_OWNER" "补缴"
V_FIN2=$(curl -s "$BASE/api/complaints/$CPL3/compensation" -H "$AUTH_FIN")
check "财务看到押金扣款来源" "$V_FIN2" "装修押金"
check "财务看到称重超量证据" "$V_FIN2" "weighRecords"
V_NEIGH2=$(curl -s "$BASE/api/complaints/$CPL3/compensation" -H "$AUTH_OWNER")
check "投诉人张三看到处理结果" "$V_NEIGH2" "neighborStatement"
PAYR=$(curl -s -X POST "$BASE/api/bills/$RBILL/pay" -H "$AUTH_FIN")
check "业主补缴押金结清" "$PAYR" '"status":"PAID"'
REG2C=$(curl -s "$BASE/api/registrations/$REG2" -H "$AUTH_PROP")
check "补缴后押金回补(130→280)" "$REG2C" '"depositBalance":280.00'
AS3=$(curl -s -X POST "$BASE/api/complaints/$CPL3/assess" -H "$AUTH_PROP" -H "$CT" -d '{}')
check "重复定责被拒(幂等)" "$AS3" "重复定责"

echo ""
echo "==================================="
echo "验证结果: 通过 $PASS 项, 失败 $FAIL 项"
[ "$FAIL" -eq 0 ] && echo "ALL CHECKS PASSED" || exit 1
