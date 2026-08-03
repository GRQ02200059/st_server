# Debug Session: team-config-weapon-missing
- **Status**: [OPEN]
- **Issue**: 客户端点"显示队伍配置"（自己的出征队伍 ArmyInfoShareUI，armyID 本地分支）时，武将的"武器/宝物"不显示；"兵种"能显示。用户怀疑与战斗系统上传时未加宝物加成有关。
- **Debug Server**: pending
- **Log File**: .dbg/trae-debug-log-team-config-weapon-missing.ndjson

## 关键结论修正（对照客户端反编译源码）
- "自己出征队伍"的武器显示**不经过战斗系统**：走 ArmyInfoShareUI.InitNormalData 的 armyID 分支
  → GetHeroGearInfo(heroUid) → Tb_gear.X.Get(hero.gearid_u)，判定条件 gear_cfg_id != 0。
  （证据：ArmyInfoShareUI.cs:614、675、783-800）
- "战斗宝物加成"是 BattleFormationCalculator/BattleTeamBuilder 的另一条路，与 UI 武器显示不是同一链路。
  两者可能同根因（服务端未给武将 gear），但本例服务端已给 gear。

## 静态链路验证（全部通过，非静态缺失）
1. 当前存档 team=[10003127,10003305,10003193]，三武将 gearUid=800001015/800001021/800001027（均>0）。
   （证据：data/accounts/sdkuid-b84a58...json）
2. UserInitTableBuilder 下发 Tb_gear：仅对 hero.gearUid>0 的武将写 equippedHeroUid，
   Tb_hero[23]=gearid_u。（UserInitTableBuilder.kt:200-215, 590）
3. InventoryCatalog: uid=800_000_000+gearId；isGrantedGearUid(800001015/1021/1027)=true，
   baseWeapons=114，gearId 1015/1021/1027 均存在且 eligible。→ Tb_gear 会含这些行。

→ 静态：保存层有 gear ✓ 下发层 Tb_gear 行存在且 Tb_hero 指向 ✓ 客户端 gearId 有效 ✓
→ 结论：不是静态配置缺失，需运行时证据定位下发/时序/门控问题。

## Hypotheses & Verification
| ID | Hypothesis | Likelihood | Effort | Evidence |
|----|------------|------------|--------|----------|
| A | 登录 UserInitTable 里实际下发的 Tb_gear 行不含 800001015/1021/1027（运行进程用的是旧 build/旧存档，与源码不一致） | High | Low | Pending |
| B | Tb_gear 下发了，但 Tb_hero 行第 23 位实际不是 gearid_u（字段错位/被后续 90005 覆盖为 0） | Med | Med | Pending |
| C | 客户端 gear 面板被 SysParamData.IsGearOpen 等门控关闭（IsShowGear()=false），整块 panel_gear 隐藏 | Med | Low | Pending |
| D | Tb_gear 行虽在，但关键列（gear_id/phase/skill）导致 Tcfg_gear.Get 失败或图标 url 空 | Low | Med | Pending |
| E | 运行进程加载的存档非本文件（uid 不同），实际队伍武将 gearUid=0 | Med | Low | Pending |

## Log Evidence
Pending.

## 追加症状（用户澄清）：战报里没有体现武器加成
这是与"队伍配置 UI 不显示武器"不同的第二条链路，指向战斗系统。

### 根因（证据确认）
玩家攻击方构建 BattleHeroSpec 时完全没有传装备字段：
- PlayerMarchHero (PlayerState.kt) 无 gear 字段 → 出征即丢失武将 gearUid
- PlayerBattleService 两处 BattleHeroSpec 构建 (:120-135, :159-171) 未填 equipmentIds 等
- 对比 LandDefenderFactory.toBattleHeroSpecs 填了完整装备 → 守军有加成、玩家没有
- BattleFormationCalculator 只要拿到 equipmentIds 即自动补全基础技能并计算加成

### 修复（TDD，完整含鸿级特性词条）
1. InventoryCatalog: 保留 feature 技能，新增 battleLoadoutForGearUid(gearUid) → BattleGearLoadout
   (equipmentIds=[gearId] + equipmentFeatureSkillIds/Levels)
2. PlayerMarchHero: 新增 equipmentIds/equipmentFeatureSkillIds/equipmentFeatureSkillLevels，出征时由 gearUid 解析
3. PlayerBattleService: launch + settle 两处 PlayerMarchHero 与两处 BattleHeroSpec 全部接入装备字段

### 验证
- 新增失败测试 `settled report reflects the equipped gear as battle equipment and its skills`
  修复前: expected [1021] but was []（attacker.equipmentIds 为空）
  修复后: 通过（attacker.equipmentIds=[1021]，attacker.equipment 含 400019）
- PlayerBattleServiceTest 全 22 项通过
- 回归核对: OfficialFullBattleReportDiffTest / BattleEngineSkillTest / BattleFormationCalculatorTest /
  BattleTeamBuilderTest / BattleEffectRegistryTest / SkillConditionInterpreterTest / WorldServiceTest
  的 14 项失败在改动前后完全一致（同测试名同行号）→ 均为既有失败，非本次引入。

## 第三条链路：战报页点武将阵容，我方阵容不显示武器
独立于上面两条。战报详情 UI（ReportDetailView → ArmyInfoShareUI.GenerateHeroVo）读武器用的是
profile 字段 `attacker_gear_info`。

### 根因（证据确认）
ClientBattleReportStore.toProfileNode 把 attacker_gear_info 写死为 emptyFourRows(3)（全 0）
→ 客户端 item[23]=gear_cfg_id 恒为 0 → GenerateHeroVo 不建 Gear → 我方不显示武器。
（客户端探查确认格式：4 行×3 列 gear_id,level,feature_id；行 0 占位，行 1..3=位置 0..2；
 列 0 gear_id 非 0 且须为有效 Tcfg_gear id 才显示武器。数据源 BattleHero.equipmentIds 已由上一修复带上 gearId。）

### 修复（TDD）
ClientBattleReportStore: 新增 List<BattleHero>.toGearInfo()，attacker_gear_info 改为由攻击方
heroes 的 equipmentIds 生成（行 0 占位 0,0,0；行 1..3 = position 0..2 的 "gearId,0,0"）。
defender_gear_info 保持空（守军 gear 由客户端本地配置解析，与地图守军链路一致）。

### 验证
- 新增失败测试 `profile encodes attacker equipped gear ids as gear info column zero`
  修复前: gearRows[1] col0 = "0"；修复后: 1000/1001/1002，行0 保持 "0,0,0"，每行 3 列。
- ClientBattleReportStoreTest 全 11 项通过。
- 回归核对: OfficialFullBattleReportDiffTest 7 项失败在改动前后完全一致（同名同行号）→ 既有失败，非本次引入。

## Verification Conclusion
- 队伍配置 UI 不显示武器：资源地 NPC 守军本无 gear（equip=[]），LandArmyInfoUI 不读 gear → 客户端原版行为，非 bug。
  自己出征队伍的 gear 下发链路（Tb_gear + Tb_hero[23]）静态验证通过。
- 战报没有武器加成：已定位根因并修复（玩家 gear 从未进入战斗构建链路），最小改动 + 完整特性词条，测试从红转绿，无回归。
- Status 待用户运行时确认后置 [FIXED]。
