"""Read pinned Git objects and reuse existing audited evidence; no tests/network."""
from pathlib import Path
from collections import Counter
import hashlib,json,subprocess
ROOT=Path('/workspace/InkWeft'); OUT=Path(__file__).resolve().parent
def git(*a): return subprocess.check_output(['git',*a],cwd=ROOT)
def sha(b): return hashlib.sha256(b).hexdigest()
def commit(s): return git('rev-parse',s).decode().strip()
base,head,native,cb=map(commit,['607c891','28104e1','9437919','cb7e142'])
def obj(c,p):
    x=subprocess.run(['git','show',f'{c}:{p}'],cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
    return None if x.returncode else {'sha256':sha(x.stdout),'bytes':len(x.stdout),'git_blob':git('rev-parse',f'{c}:{p}').decode().strip()}
def cat(p):
    if '/src/main/' in p:return 'production_source'
    if '/src/androidTest/' in p:return 'android_test'
    if '/src/test/' in p:return 'jvm_test'
    if p.startswith('android/verification/'):return 'evidence_and_frozen_plans'
    if p.startswith('ci/'):return 'controller_and_tools'
    return 'other'
paths=git('diff','--name-only',base,head).decode().splitlines()
sources=[p for p in paths if p.startswith('android/') and '/src/' in p]
entries=[]
for p in sources:
    versions={k:obj(c,p) for k,c in [('pr_head',base),('cb7',cb),('native_source',native),('head',head)]}
    entries.append({'path':p,'category':cat(p),'versions':versions,'head_equals_native':versions['head']==versions['native_source'],'head_equals_cb7':versions['head']==versions['cb7']})
native_diff=[p for p in git('diff','--name-only',native,head).decode().splitlines() if p.startswith('android/') and '/src/' in p]
assert native_diff==['android/data-local/src/androidTest/kotlin/org/inkweft/data/HistoryDeletionBoundaryDiagnosticsTest.kt']
cbpaths=[p for p in git('diff-tree','--no-commit-id','--name-only','-r',cb).decode().splitlines() if p.startswith('android/') and '/src/' in p]
assert len(cbpaths)==4 and all(obj(cb,p)==obj(head,p) for p in cbpaths)
assert not [p for p in paths if p.startswith(('.github/','backup-server/'))]
assert subprocess.run(['git','merge-base','--is-ancestor',base,head],cwd=ROOT).returncode==0
mainpaths=[p for p in git('ls-tree','-r','--name-only',head,'android').decode().splitlines() if '/src/main/' in p]
rp=Path('/workspace/scratch/pr20-match-execution-20261014/rule-audit/review.json')
rule=json.loads(rp.read_text())
for x in rule['history_sources']: assert sha(git('show',f"{x['git']}:{x['path']}"))==x['sha256']
ar='android/verification/pr20-match-candidate-20261014/analyze.py'
assert obj(head,ar)['sha256']=='fdbf7b76ebbf587bccaede00910209ba2586c0982bbd0da80cb26cb94ce07740'
fullrows=[x for x in rule['comparisons'] if x['metric']=='raster.8.forcedFullCompleteMs']
coverage=[
 ['cb7 host','pr20-match-host-20261014','17 actual JVM tests: matcher5/observation11/terms1; app/data AndroidTest compile; host only'],
 ['cb7 run38520196523','pr20-match-execution-20261014','62 native calls, four seeds,24 public calls/720 saves plus Unicode/oracle/continuous/dense;45/5/4 gates remain open'],
 ['78a5bab run38514139119','pr20-causal-results-20261014','13 actual native diagnostic calls, before matcher/lazy pencil; not evidence of their gains'],
 ['4f41a50 host','pr20-save-boundary-host-20261014','4 wrapper JVM tests executed and compile; not Room/SQLite/native execution'],
 ['e31063c raster cleanup','pr20-raster-followup-20261014','322.761s app AndroidTest compile; no new native/JVM/APK build; new exact harness not natively executed'],
 ['9437919 run38543698114','pr20-save-boundary-uuid-execution-20261014','10 data-only native calls:7 existing guards/2 corrected guards/90 instrumented saves; no app or GC boundary test'],
 ['4c4a2d6 GC test','pr20-gc-boundary-host-20261014','107.791s final data AndroidTest compile bound9 inputs; no native execution; old0/30,1/30 natural exposure insufficient']]
data={'status':'VERIFIED_PINNED_COMMIT_SCOPE','pr_head':base,'head':head,'native_source':native,'cb7':cb,
 'scope':'Pinned Git objects, not any subsequent uncommitted worktree edit',
 'worktree_status_observed':git('status','--short').decode().splitlines(),
 'remote_scope':{'cached_origin_integration':commit('origin/codex/android-perf-integration-20261012'),'cached_remote_branches_containing_head':git('branch','-r','--contains',head).decode().splitlines(),'no_network':True,'note':'Unpushed range relative to parent-supplied PR head and local cached refs, not a fresh GitHub check'},
 'commits':git('log','--format=%H %s',f'{base}..{head}').decode().splitlines(),
 'file_counts':dict(Counter(cat(p) for p in paths)),'changed_total':len(paths),'sources':entries,
 'native_source_comparison':{'all_android_src_differences':native_diff,'all_android_main_files_identical':True,'main_file_count':len(mainpaths),'not_blanket_test_coverage':True},
 'cb7_four_source_files_still_exact':cbpaths,
 'real_default_behavior_changes':[
 '78a5bab: remove empty-draft CTE and restore unconditional TEMP draft overlay. Actual default SQL path change; simplification after mixed screen, not a claimed speedup.',
 'cb7: precompute ASCII first query character and reject provably different starts; retain original ignoreCase verification/non-ASCII fallback; both ordinary and profiled UDFs use helper.',
 'cb7: instantiate PencilTileRenderer on first actual PENCIL drawing and retain batch/finally cleanup.'],
 'default_off_diagnostics':['UDF/SQL and all-index-batch callbacks','Save stages/executor/Room/SQLite boundaries','GC guard/delete batch callbacks from existing reads','Raster/native-build/publication/notification boundaries'],
 'default_off_limit':'Null skips diagnostic clocks, ThreadLocal and event allocations; branches remain. No measured zero total overhead claim.',
 'coverage':[{'source_scope':a,'evidence':f'android/verification/{b}/README.md','result_and_limit':c} for a,b,c in coverage],
 'no_active_workflow_or_backup_server_delta':True,
 'classification':{'current_counts':rule['original_counts'],'current_rule':rule['original_rule'],'B_to_C1_eight_full':'REGRESSION_SIGNAL','C0_to_C1_eight_full':'INCONCLUSIVE','parent_withdrew_three_individual_over_band_interpretation':True,'historical_sensitivity_only':rule['alternative_counts'],'original_sources':rule['history_sources'],'execution_analyzer':{'path':ar,'lines':[43,46],**obj(head,ar)},'original_audit':{'path':str(rp),'sha256':sha(rp.read_bytes())},'eight_full_pairs_reused':fullrows},
 'remaining_limits':['Observe p50/direct p95 and eight-layer full signals remain open','GC natural competition quantiles and new boundary test native run unverified','Changed strict raster cleanup has compile only','Single-seed enabled-hook timings cannot close public four-pair gates'],
 'actions':'No source edit, test, native, CI, build, network or push; only scratch output and read-only local evidence reuse'}
(OUT/'source-state.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
md=f'''# 28104e1 源码状态与测试来源

结论：cb7 有效 matcher 候选完整保留；本轮原生 source943 与 head281 的生产源码逐字节一致。当前分类继续 **45带内/5不确定/4回退信号**，B→C1八层full仍为 REGRESSION_SIGNAL。

固定范围为 PR head `{base}` → `{head}`，共{len(data['commits'])}提交、{len(paths)}文件，其中10生产源、10 AndroidTest、2 JVM test，其余为证据/冻结计划/控制器。607为281祖先，本地缓存origin也为607；未联网查询最新远端。无 `.github/` 激活工作流或 backup-server 差异。

本审查绑定提交281。读取开始时工作树干净，定稿时唯一另行未提交文件为 `HistoryDeletionBoundaryDiagnosticsTest.kt`；该后续编辑不纳入本报告，不声称工作树最终clean，不触碰该文件。

## 生产与诊断的区别

| 类别 | 已保留的内容 |
|---|---|
| 实际默认SQL路径变化 | 78a5bab撤回empty-draft CTE，恢复统一TEMP overlay，LibrarySearchRepository:128。筛查无稳定收益后做简化，不称提速；不能归为纯诊断。 |
| 实际默认优化 | cb7 ASCII首字符过滤matcher，正常/诊断UDF共用；PENCIL首次实际绘制才创建renderer。保持原忽略大小写、非ASCII回退与清理。 |
| 默认关闭诊断 | UDF/SQL、index batch、save阶段/executor/Room/SQLite、GC guard/DELETE、raster/native/publication。null路径跳过诊断clock/ThreadLocal/event，但仍有wrapper分支，不能称已测零总开销。 |
| 测试/证据/控制器 | UUID fixture修正、诊断/guard、严格owned-job清理和GC边界测试；历史日志/失败/统计/冻结模板。它们不算生产提速。 |

`cb7e14218ac83aa8cf6b9d7906051a10da1a6547` 的四个改动文件——LibrarySearchMatcher、LibrarySearchRepository、LibrarySearchMatcherTest、StreamedInkRaster——在281均与cb7 **byte-exact**。最终交付不能遗漏该提交。代码入口为 `LibrarySearchMatcher.kt:5`、`LibrarySearchRepository.kt:62,77,81`、`StreamedInkRaster.kt:152,170`。

与native source `{native}` 相比，281全部{len(mainpaths)}个Android `src/main`文件相同；全部 `android/**/src/**` 唯一差异是新增GC boundary test（SHA256 `98b69ea1d09e0bce21646d79f50f9b7dd9396c9e73bb1a9baea647de7cdf6b72`）。其余测试字节也相同。来源一致不表示data-only run执行了app或GC所有路径。JSON保留22个变更源的四版本Git blob/SHA256。

## 测试覆盖

| 来源 | 实际范围及边界 |
|---|---|
'''
for a,b,c in coverage: md+=f'| {a} | {c}；证据：`android/verification/{b}/README.md`。 |\n'
md+='''
cb7同机四seed原生实测冷读B/C0/C1中位为4773.525/2168.929/700.518ms，暖读531.339/606.972/150.039ms（match执行README:27–41）。这是真实固定合成夹具收益，不泛化到所有文本/查询，且候选包含诊断等累积差异，不能只归因helper。后来的943运行只验证保存诊断完整性；不替代cb7比较，不关闭保存p50/p95或full信号。strict raster cleanup和GC boundary仍仅编译；不要以943包含相同源推定它们已运行。

## 原冻结规则与四对值

父侧已明确撤回“3/4个体越带”的新解释。当前继续原规则/原输出45/5/4；45/6/3仅保留为历史敏感性口径，不作当前结论、不覆盖旧输出。

最早预运行提交 `c37ba03232b1a0985e85cbaec4d349dbab945919` 的 `ci/pr20-kvm-paired/PLAN.md:114` 原文：“同指标至少3/4对同向变慢，配对中位时间增幅>相应relative，且中位绝对增量>absolute”。同提交 `analyze.py:63–65` 使用 `sum(delta_ms > 0) >= 3` 加两个中位门槛；执行 `android/verification/pr20-match-candidate-20261014/analyze.py:43–46` 相同。原protocol仅列数值带，未另定3/4个体条件。

- 原PLAN SHA256 `028e1b7051626235636c08d7f6627a0d1f9298d3ce9009d0cae3803caad38ee4`；仓库归档 `android/verification/pr20-kvm-paired-20261013/evidence/frozen-PLAN.md:114`。
- 原analyzer SHA256 `d3bb05d493691be7924ecef0569d747645628c7abf0005287a8b045a69e36adf`；原protocol `1065177d7809426d949c4b4b67bd639a47c3fc1943f706a77c2dfabb985e9aba`。
- 执行analyzer SHA256 `fdbf7b76ebbf587bccaede00910209ba2586c0982bbd0da80cb26cb94ce07740`。

八层forced-full原值（ms），复用已审证据，不重做54项：

| seed | B | C0 | C1 | B→C1 delta | 个体越带 | C0→C1 delta | 个体越带 |
|---|---:|---:|---:|---:|---|---:|---|
'''
bc=next(x for x in fullrows if x['comparison']=='B->C1')
cc=next(x for x in fullrows if x['comparison']=='C0->C1')
for b,c in zip(bc['pairs'],cc['pairs']):
    md+=f"|{b['seed']}|{b['left_ms']:.6f}|{c['left_ms']:.6f}|{b['right_ms']:.6f}|{b['delta_ms']:+.6f}|{'是' if b['over_band'] else '否'}|{c['delta_ms']:+.6f}|{'是' if c['over_band'] else '否'}|\n"
md+='''
B→C1：3/4变慢、2/4个体越带，中位+39.038078ms/+12.219478%，双超5ms/10%，故原规则为信号。C0→C1：4/4变慢、2/4越带，中位+29.610528ms/+8.926004%，相对中位未过10%，故不确定。个体越带公式为 `delta > max(left×10%,5ms)`。工程筛查信号并非已证明稳定的因果回退。

本次未编辑生产、运行测试/native/构建/CI、联网或推送。原历史输出均保持。未关闭项：保存p50/p95、八层full、GC自然竞争暴露、新strict raster cleanup原生验证。
'''
(OUT/'source-state.md').write_text(md)
names=['collect_source_state.py','source-state.json','source-state.md']
(OUT/'SHA256SUMS').write_text(''.join(f'{sha((OUT/n).read_bytes())}  {n}\n' for n in names))
print(json.dumps({'counts':data['file_counts'],'main_files':len(mainpaths),'commits':len(data['commits']),'hashes':{n:sha((OUT/n).read_bytes()) for n in names+['SHA256SUMS']}},indent=2))
