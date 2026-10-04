#!/usr/bin/env bash
# 端到端验证脚本：覆盖登记→上报→审核→派单→称重→处置→计费→投诉→追责全链路
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
THIS_SAT=$(date -d "next saturday" +%F)   # 周末不限行，赔付场景派单固定在周六
THIS_SUN=$(date -d "next sunday" +%F)
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

echo "== 6. 满溢投诉赔付与责任分摊（清运延误 / 业主超量） =="
# 责任业主=owner1(张三)；投诉邻居=owner2(张四)，二者不同人以校验说明视角隔离

# ---- 场景 A：清运公司迟到导致满溢 → 清运公司履约扣款 ----
REG3=$(curl -s -X POST "$BASE/api/registrations" -H "$AUTH_OWNER" -H "$CT" -d "{\"building\":\"2栋\",\"roomNumber\":\"301\",\"constructionTeam\":\"宏远装修队\",\"expectedStartDate\":\"$TODAY\",\"expectedEndDate\":\"$(date -d '+30 days' +%F)\",\"wasteTypes\":\"瓷砖\",\"frequency\":\"DAILY\",\"depositAmount\":2000}" | J '.data.id')
# 前序流程后 2 号点已堆放较多；扩大围挡使审核投影不超容量（证明堆放合规、满溢纯由清运迟到造成）
curl -s -X POST "$BASE/api/waste-points/2/fence" -H "$AUTH_PROP" -H "$CT" -d '{"enable":true,"extraCapacity":120}' >/dev/null
RA=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":$REG3,\"reportDate\":\"$THIS_SAT\",\"bagCount\":20,\"extraBagCount\":0,\"stackingLocation\":\"2号楼垃圾点\",\"wastePointId\":2,\"containsWood\":false,\"containsGlass\":false,\"containsTile\":true,\"containsMixedHousehold\":false}")
REPORTA=$(echo "$RA" | J '.data.id')
AA=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORTA,\"approve\":true,\"apptDate\":\"$THIS_SAT\",\"timeSlot\":\"AM\",\"comment\":\"同意\"}")
APPT_A=$(echo "$AA" | J '.data.id')
check "场景A审核通过生成预约(准时堆放但清运迟到)" "$AA" '"status":"APPROVED"'
# 派单周六（不限行）10:00，实际 12:00 到场（迟到超 30 分钟）
DA=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT_A,\"vehicleId\":2,\"plannedArrival\":\"${THIS_SAT}T10:00:00\"}")
DISP_A=$(echo "$DA" | J '.data.id')
ARRA=$(curl -s -X POST "$BASE/api/dispatches/$DISP_A/arrive" -H "$AUTH_COLL" -H "$CT" -d "{\"actualArrival\":\"${THIS_SAT}T12:00:00\"}")
check "场景A清运车辆迟到" "$ARRA" '"lateFlag":true'

C_A=$(curl -s -X POST "$BASE/api/complaints" -H "Authorization: Bearer $OWNER2_T" -H "$CT" -d "{\"type\":\"OVERFLOW\",\"targetAppointmentId\":$APPT_A,\"targetRegistrationId\":$REG3,\"wastePointId\":2,\"feeWaiverAmount\":120,\"description\":\"垃圾点长期满溢导致车位被占用，要求减免物业费\"}")
CPL_A=$(echo "$C_A" | J '.data.id')
check "邻居发起满溢投诉(诉求减免120)" "$C_A" '"feeWaiverAmount":120'

INV_A=$(curl -s "$BASE/api/complaints/$CPL_A/investigation" -H "$AUTH_PROP")
check "证据链串联预约记录" "$INV_A" '"appointment":{'
check "证据链串联清运迟到" "$INV_A" '"lateFlag":true'
check "证据链串联物业容量审核" "$INV_A" '"capacityReview":{'
check "证据链串联施工队堆放" "$INV_A" '"stacking":{'
check "证据链串联投诉时间" "$INV_A" '"complainedAt"'
check "证据链建议定责清运公司" "$INV_A" '"suggestedLiableParty":"COLLECTOR"'

ADJ_A=$(curl -s -X POST "$BASE/api/complaints/$CPL_A/compensation" -H "$AUTH_PROP" -H "$CT" -d '{}')
check "定责清运公司并赔付120" "$ADJ_A" '"liableParty":"COLLECTOR"'
check "赔付计入清运公司履约扣款" "$ADJ_A" '"collectorPenaltyAmount":120.00'
check "赔付单已定责" "$ADJ_A" '"status":"DETERMINED"'
# 投诉列表（投诉人视角）已回写处理结果
CPLA_GET=$(curl -s "$BASE/api/complaints" -H "Authorization: Bearer $OWNER2_T")
check "投诉人列表看到处理结果" "$CPLA_GET" "清运延误"

# 财务账单：清运公司履约扣款(负数) 与 COMPENSATION 赔付入账
CBILL_A=$(curl -s "$BASE/api/bills?type=COLLECTOR" -H "$AUTH_FIN")
check "清运公司履约扣款-120冲减结算" "$CBILL_A" '"amount":-120.00'
COMPBILL_A=$(curl -s "$BASE/api/bills?type=COMPENSATION" -H "$AUTH_FIN")
check "赔付资金入账账单120" "$COMPBILL_A" '"amount":120.00'
check "财务可见扣款来源=清运公司" "$COMPBILL_A" "资金来源：清运公司"
check "财务可见入账去向=物业费减免" "$COMPBILL_A" "减免物业费"

# 四方说明：投诉人只看结果，看不到清运公司结算/押金等资金细节
N_A=$(curl -s "$BASE/api/complaints/$CPL_A/notices" -H "Authorization: Bearer $OWNER2_T")
check "邻居看到处理结果说明" "$N_A" "已向您赔付 120 元"
check_absent "邻居看不到清运公司履约扣款细节" "$N_A" "履约扣款"
COLLNOTE_A=$(curl -s "$BASE/api/complaints/$CPL_A/notices" -H "$AUTH_COLL")
check "清运公司收到履约扣款说明" "$COLLNOTE_A" "履约扣款"
FINNOTE_A=$(curl -s "$BASE/api/complaints/$CPL_A/notices" -H "$AUTH_FIN")
check "财务说明含扣款来源" "$FINNOTE_A" "【扣款来源】"
check "财务说明含入账去向" "$FINNOTE_A" "【入账去向】"
FINNOTE_CNT=$(echo "$FINNOTE_A" | J '[.data[]] | length')
check "财务/物业可查看四份说明" "$([ "$FINNOTE_CNT" = "4" ] && echo ok4)" "ok4"
# 清运公司只能看到本公司那一份
COLLNOTE_CNT=$(echo "$COLLNOTE_A" | J '[.data[]] | length')
check "清运公司只看到一份说明" "$([ "$COLLNOTE_CNT" = "1" ] && echo ok1)" "ok1"

# ---- 场景 B：业主超量未申报导致满溢 → 押金扣除 + 通知补缴 ----
REG4=$(curl -s -X POST "$BASE/api/registrations" -H "$AUTH_OWNER" -H "$CT" -d "{\"building\":\"3栋\",\"roomNumber\":\"402\",\"constructionTeam\":\"宏远装修队\",\"expectedStartDate\":\"$TODAY\",\"expectedEndDate\":\"$(date -d '+30 days' +%F)\",\"wasteTypes\":\"木板\",\"frequency\":\"DAILY\",\"depositAmount\":600}" | J '.data.id')
# 3 号楼点容量 30、当前 5；围挡扩容 +60（有效 90），审核投影 5+30=35 未超容量（非审核即超），
# 但本批 30 袋中含 25 袋业主临时加袋（超量未申报）→ 建议定责业主
FENCE3=$(curl -s -X POST "$BASE/api/waste-points/3/fence" -H "$AUTH_PROP" -H "$CT" -d '{"enable":true,"extraCapacity":60}')
check "3号楼围挡扩容+60" "$FENCE3" '"tempFence":true'
RB=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":$REG4,\"reportDate\":\"$THIS_SUN\",\"bagCount\":30,\"extraBagCount\":25,\"stackingLocation\":\"3号楼垃圾点\",\"wastePointId\":3,\"containsWood\":true,\"containsGlass\":false,\"containsTile\":false,\"containsMixedHousehold\":false}")
REPORTB=$(echo "$RB" | J '.data.id')
AB=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORTB,\"approve\":true,\"apptDate\":\"$THIS_SUN\",\"timeSlot\":\"PM\",\"comment\":\"同意\"}")
APPT_B=$(echo "$AB" | J '.data.id')
check "场景B审核通过(含25袋临时加袋)" "$AB" '"status":"APPROVED"'
# 清运准时到场（排除清运责任），垃圾仍曾满溢被投诉
DB=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT_B,\"vehicleId\":1,\"plannedArrival\":\"${THIS_SUN}T14:00:00\"}")
DISP_B=$(echo "$DB" | J '.data.id')
ARRB=$(curl -s -X POST "$BASE/api/dispatches/$DISP_B/arrive" -H "$AUTH_COLL" -H "$CT" -d "{\"actualArrival\":\"${THIS_SUN}T14:10:00\"}")
check "场景B清运准时(无迟到)" "$ARRB" '"lateFlag":false'

C_B=$(curl -s -X POST "$BASE/api/complaints" -H "Authorization: Bearer $OWNER2_T" -H "$CT" -d "{\"type\":\"OVERFLOW\",\"targetAppointmentId\":$APPT_B,\"targetRegistrationId\":$REG4,\"wastePointId\":3,\"feeWaiverAmount\":300,\"description\":\"3号楼垃圾点满溢占用车位，要求减免物业费\"}")
CPL_B=$(echo "$C_B" | J '.data.id')
INV_B=$(curl -s "$BASE/api/complaints/$CPL_B/investigation" -H "$AUTH_PROP")
check "证据链识别业主临时加袋超量" "$INV_B" '"ownerExtraUndeclared":true'
check "证据链建议定责业主" "$INV_B" '"suggestedLiableParty":"OWNER"'

ADJ_B=$(curl -s -X POST "$BASE/api/complaints/$CPL_B/compensation" -H "$AUTH_PROP" -H "$CT" -d '{}')
check "定责业主超量并赔付300" "$ADJ_B" '"liableParty":"OWNER"'
check "从装修押金扣除300" "$ADJ_B" '"depositDeductedAmount":300.00'
check "通知业主补缴300" "$ADJ_B" '"topUpDue":300.00'

REG4INFO=$(curl -s "$BASE/api/registrations/$REG4" -H "$AUTH_PROP")
check "业主押金余额600-300=300" "$REG4INFO" '"depositBalance":300.00'
DEP_B=$(curl -s "$BASE/api/registrations/$REG4/deposit-log" -H "$AUTH_OWNER")
check "押金流水含满溢赔付扣款" "$DEP_B" "满溢投诉赔付扣款"
TOPUP_B=$(curl -s "$BASE/api/bills?type=OWNER" -H "$AUTH_FIN")
check "生成业主补缴挂账账单300" "$TOPUP_B" '"amount":300.00'
COMPBILL_B=$(curl -s "$BASE/api/bills?type=COMPENSATION" -H "$AUTH_FIN")
check "财务可见扣款来源=装修押金" "$COMPBILL_B" "装修押金扣除"

# 说明视角：责任业主(owner1)看 OWNER 补缴说明；投诉邻居(owner2)看处理结果
OWNER1NOTE_B=$(curl -s "$BASE/api/complaints/$CPL_B/notices" -H "$AUTH_OWNER")
check "责任业主要到补缴说明" "$OWNER1NOTE_B" "请及时补缴装修押金"
NEIGHBORNOTE_B=$(curl -s "$BASE/api/complaints/$CPL_B/notices" -H "Authorization: Bearer $OWNER2_T")
check "投诉邻居看到处理结果" "$NEIGHBORNOTE_B" "已向您赔付 300 元"
check_absent "邻居看不到押金扣款细节" "$NEIGHBORNOTE_B" "装修押金扣除"
FINNOTE_B=$(curl -s "$BASE/api/complaints/$CPL_B/notices" -H "$AUTH_FIN")
check "财务说明含业主补缴挂账" "$FINNOTE_B" "业主补缴挂账单"

# 财务收到业主补缴 → 押金回补
TOPUP_BILL=$(echo "$TOPUP_B" | J '[.data[] | select(.detail != null and (.detail | contains("补缴装修押金")))][0].id')
PAY_TOPUP=$(curl -s -X POST "$BASE/api/bills/$TOPUP_BILL/pay" -H "$AUTH_FIN")
check "财务确认业主补缴" "$PAY_TOPUP" '"status":"PAID"'
REG4INFO2=$(curl -s "$BASE/api/registrations/$REG4" -H "$AUTH_PROP")
check "补缴后押金回补300→600" "$REG4INFO2" '"depositBalance":600.00'

# 赔付分摊单可查（清运公司只见本公司一单）
COMPS=$(curl -s "$BASE/api/complaints/compensations" -H "$AUTH_FIN")
check "赔付分摊单列表(财务看全部)" "$COMPS" '"compNo":"CMP'
COMPS_C=$(curl -s "$BASE/api/complaints/compensations" -H "$AUTH_COLL")
check "清运公司只见履约扣款那单" "$COMPS_C" '"liableParty":"COLLECTOR"'
check_absent "清运公司看不到业主责任单" "$COMPS_C" '"liableParty":"OWNER"'

echo "== 7. 三端看板 =="
DP=$(curl -s "$BASE/api/dashboard/property" -H "$AUTH_PROP")
check "物业看板-楼栋统计" "$DP" '"building":"1栋"'
check "物业看板-超时未清" "$DP" '"overdueAppointments":[{'
check "物业看板-清运公司履约" "$DP" '"company":"绿洁清运"'
DC=$(curl -s "$BASE/api/dashboard/collector" -H "$AUTH_COLL")
check "清运看板-派单与车辆" "$DC" '"vehicles"'
DF=$(curl -s "$BASE/api/dashboard/finance?period=$PERIOD" -H "$AUTH_FIN")
check "财务看板-五类账单汇总" "$DF" '"PROPERTY_ADVANCE"'
check "财务看板含赔付账单" "$DF" '"COMPENSATION"'

echo ""
echo "==================================="
echo "验证结果: 通过 $PASS 项, 失败 $FAIL 项"
[ "$FAIL" -eq 0 ] && echo "ALL CHECKS PASSED" || exit 1
