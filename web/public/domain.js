import { holidays } from './holidays.js';
import { modelSchema } from './model-schema.js';
import { canonicalValue, rowKey } from './canonical.js';
import { stateFields } from './merge.js';
export const tables = ['groups','accounts','snapshots','valuations','snapshotItems','flows','todoLists','todoSections','todos','checklistItems','diaryEntries','diaryDrafts','habitChecks','habits','bodyMeasurements','foodEntries','noteTopics','notes','noteSources','noteDrafts','weeklyReviews','weeklyDrafts','countdowns'];
export const STAMP_FIELDS = ['changedAt','changeId','stateAt','stateId'];
/** A deterministic, synchronous id for a row from before format 16 (no WebCrypto in a revive). */
export function legacyId(text) {
  const hash = (seed) => {
    let h = seed >>> 0;
    for (let i = 0; i < text.length; i += 1) { h ^= text.charCodeAt(i); h = Math.imul(h, 16777619); }
    return (h >>> 0).toString(16).padStart(8, '0');
  };
  return [0x811c9dc5, 0x01000193, 0x9e3779b9, 0x85ebca6b].map(hash).join('');
}
export const uid = () => crypto.randomUUID();
export const today = () => dateString(new Date());
export function dateString(d) { return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`; }
export const day = s => new Date(`${s}T12:00:00`);
export function addDays(s,n) { const d=day(s); d.setDate(d.getDate()+n); return dateString(d); }
export function monday(s) { return addDays(s,-((day(s).getDay()+6)%7)); }
export const live = rows => rows.filter(r => r.deletedAt == null);
export const sorted = rows => [...rows].sort((a,b)=>(a.sortOrder??a.position??0)-(b.sortOrder??b.position??0));
export function ensure(ok,message) { if (!ok) throw Error(message); }
export function integer(n) { ensure(Number.isSafeInteger(n),'数值超出网页可安全处理的整数范围'); return n; }
export function scaled(text,digits=2) {
  const s=String(text).trim(); ensure(new RegExp(digits ? `^-?\\d+(?:\\.\\d{1,${digits}})?$` : '^-?\\d+$').test(s),`请输入最多 ${digits} 位小数的数字`);
  const [a,b='']=s.replace('-','').split('.'); const n=BigInt(a)*10n**BigInt(digits)+BigInt(b.padEnd(digits,'0')||'0');
  return integer(Number(s.startsWith('-')?-n:n));
}
export const money = n => n == null ? '—' : (n/100).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
export const inputMoney = n => n == null ? '' : (n/100).toFixed(2);
export const defaultHabits = ['吃补剂','健身','控制饮食','喝够水','早睡早起'].map((label,i)=>({key:['supplements','workout','diet','water','sleep'][i],label,icon:['medication','fitness','restaurant','water','bedtime'][i],color:[0xFFD9822B,0xFFD0533F,0xFF5E9B3A,0xFF3D8FD1,0xFF7A62C2][i],position:i,addedOn:null,removedOn:null,rubric:''}));
export function empty() { return {formatVersion:17,schemaVersion:15,exportedAt:Date.now(),...Object.fromEntries(tables.map(k=>[k,[]])),healthProfile:null,netWorthGoal:null,netWorthGoalAt:0,netWorthGoalChangeId:'',tombstones:[]}; }
const deleted = {deletedAt:null,deleteBatch:null};
export function row(table,values={}) {
  const now=Date.now(),base={id:uid(),createdAt:now};
  const defs={
    groups:{name:'新分组',isLiability:false,colorIndex:0,sortOrder:0},
    accounts:{groupId:null,name:'新账户',purpose:null,kind:'NORMAL',institution:null,cardTail:null,code:null,lockedUntil:null,baselineAt:now,baselineCost:null,includeInTotal:true,isDisabled:false,icon:null,note:null,sortOrder:0},
    snapshots:{at:now,note:null},
    valuations:{accountId:null,at:now,value:0,shares:null,snapshotId:null,note:null},
    flows:{kind:'EXPENSE',fromAccountId:null,toAccountId:null,amount:0,fee:0,feeOn:'FROM',at:now,note:null,category:'other'},
    todoLists:{name:'新清单',notes:'',deadline:null,sortOrder:0,archivedAt:null,...deleted},
    todoSections:{listId:null,name:'新分区',sortOrder:0,...deleted},
    todos:{kind:'ORDINARY',title:'',notes:'',listId:null,sectionId:null,planDate:null,remindTime:null,strongRemind:false,remindBefore:0,someday:false,deadline:null,important:false,snoozeAt:null,remindedPeriod:null,repeatRule:null,repeatEndedAt:null,occurrenceCount:0,repeatSourceId:null,undoBefore:null,undoRevision:null,stateSeq:0,revision:0,sortOrder:0,completedAt:null,...deleted},
    checklistItems:{todoId:null,text:'',done:false,sortOrder:0},
    diaryEntries:{date:today(),body:'',bodyRev:0,weatherCode:null,tempMin:null,tempMax:null,city:null,lat:null,lon:null,weatherAt:null,weatherRev:0,updatedAt:now,...deleted,mood:null},
    notes:{topicId:null,body:'',bodyRev:0,pinned:false,position:0,updatedAt:now,reviewedAt:null,...deleted},
    noteTopics:{name:'新主题',sortOrder:0},
    weeklyReviews:{weekStart:monday(today()),body:'',bodyRev:0,updatedAt:now,...deleted},
    bodyMeasurements:{date:today(),measuredAt:now,weightG:60000,bodyFatBp:null,note:null,updatedAt:now},
    foodEntries:{date:today(),eatenAt:now,name:null,per:null,amount:null,unit:null,energyCal:0,inKj:false,proteinMg:null,fatMg:null,carbMg:null,sodiumMg:null,updatedAt:now},
    countdowns:{title:'',date:today(),yearly:false,pinned:false,updatedAt:now},
    healthProfile:{id:'self',sex:'MALE',birthDate:'2000-01-01',heightMm:1700,targetWeightG:null,updatedAt:now}
  };
  const result={...base,...defs[table],...values};
  if (['groups','snapshots','todoSections','checklistItems','healthProfile'].includes(table)) delete result.createdAt;
  // The merge stamps exist on every row from format 16 on; store.change overwrites them with
  // the operation's one stamp, this only keeps a freshly built row valid before it is saved.
  result.changedAt = result.changedAt ?? now;
  result.changeId = result.changeId ?? uid();
  if (stateFields[table]) {
    result.stateAt = result.stateAt ?? 0;
    result.stateId = result.stateId ?? '';
  }
  return result;
}
const withoutStamps = (row) => Object.fromEntries(Object.entries(row).filter(([k]) => !STAMP_FIELDS.includes(k)));
const habitMidnight = (day) => day ? new Date(`${day}T00:00:00`).getTime() : 0;

/**
 * A file from before format 16 has no stamps. Each row gets a deterministic `legacy:` id (its
 * content) and a time from the newest timestamp it records, the same idea as the Android
 * backfill; the two may pick different ids, which is harmless — both sides see both ids and the
 * compare order still agrees. Rows keep their content.
 */
export function backfillLegacy(file) {
  const data = { ...empty(), ...file };
  const snapshotAt = new Map(data.snapshots.map((s) => [s.id, s.at]));
  const todoAt = new Map(data.todos.map((t) => [t.id, Math.max(t.createdAt ?? 0, t.completedAt ?? 0, t.deletedAt ?? 0, t.snoozeAt ?? 0, t.repeatEndedAt ?? 0)]));
  const at = {
    groups: () => 0,
    accounts: (r) => r.createdAt ?? 0,
    snapshots: (r) => r.at ?? 0,
    valuations: (r) => Math.max(r.at ?? 0, r.createdAt ?? 0),
    snapshotItems: (r) => snapshotAt.get(r.snapshotId) ?? 0,
    flows: (r) => Math.max(r.at ?? 0, r.createdAt ?? 0),
    todoLists: (r) => Math.max(r.archivedAt ?? 0, r.createdAt ?? 0, r.deletedAt ?? 0),
    todoSections: (r) => r.deletedAt ?? 0,
    todos: (r) => todoAt.get(r.id) ?? 0,
    checklistItems: (r) => todoAt.get(r.todoId) ?? 0,
    diaryEntries: (r) => Math.max(r.createdAt ?? 0, r.updatedAt ?? 0, r.deletedAt ?? 0),
    diaryDrafts: (r) => r.createdAt ?? 0,
    habitChecks: (r) => r.checkedAt ?? 0,
    habits: (r) => habitMidnight(r.removedOn ?? r.addedOn),
    healthProfile: (r) => r.updatedAt ?? 0,
    bodyMeasurements: (r) => Math.max(r.measuredAt ?? 0, r.createdAt ?? 0, r.updatedAt ?? 0),
    foodEntries: (r) => Math.max(r.eatenAt ?? 0, r.createdAt ?? 0, r.updatedAt ?? 0),
    noteTopics: (r) => r.createdAt ?? 0,
    notes: (r) => Math.max(r.createdAt ?? 0, r.updatedAt ?? 0, r.deletedAt ?? 0),
    noteSources: (r) => r.createdAt ?? 0,
    noteDrafts: (r) => r.createdAt ?? 0,
    weeklyReviews: (r) => Math.max(r.createdAt ?? 0, r.updatedAt ?? 0, r.deletedAt ?? 0),
    weeklyDrafts: (r) => r.createdAt ?? 0,
    countdowns: (r) => Math.max(r.createdAt ?? 0, r.updatedAt ?? 0),
  };
  const stateAt = {
    diaryEntries: (r) => r.weatherAt ?? 0,
    notes: (r) => r.reviewedAt ?? 0,
    todos: (r) => r.snoozeAt ?? 0,
    countdowns: (r) => r.updatedAt ?? 0,
  };
  for (const table of tables) {
    data[table] = (data[table] ?? []).map((row) => {
      const content = canonicalValue(withoutStamps(row));
      const next = { ...row, changedAt: Math.max(0, at[table](row)), changeId: `legacy:${legacyId(content)}` };
      if (stateAt[table]) {
        next.stateAt = Math.max(0, stateAt[table](row));
        next.stateId = `legacy:${legacyId(`${content}|state`)}`;
      }
      return next;
    });
  }
  if (data.healthProfile) {
    const row = data.healthProfile;
    const content = canonicalValue(withoutStamps(row));
    data.healthProfile = { ...row, changedAt: Math.max(0, at.healthProfile(row)), changeId: `legacy:${legacyId(content)}` };
  }
  data.netWorthGoalAt = 0;
  data.netWorthGoalChangeId = data.netWorthGoal != null ? `legacy:${legacyId(`netWorthGoal\u0000${data.netWorthGoal}`)}` : '';
  data.formatVersion = 17;
  data.schemaVersion = 15;
  return data;
}

export function parseBackup(text) {
  const file = JSON.parse(text, (k, v, context) => {
    if (typeof v === 'number' && context?.source && /^-?\d+$/.test(context.source)) ensure(Number.isSafeInteger(v), '备份含超出网页安全范围的整数，已停止导入');
    return v;
  });
  ensure(file && typeof file === 'object' && !Array.isArray(file), '备份必须是对象');
  ensure(Number.isInteger(file.formatVersion) && file.formatVersion >= 1 && file.formatVersion <= 17, '备份版本不支持，请升级网页版');
  ensure(Number.isInteger(file.schemaVersion) && file.schemaVersion >= 1 && file.schemaVersion <= 15, '数据来自更新的手机版，请升级网页版');
  return validate(file.formatVersion >= 16 ? file : backfillLegacy(file));
}

/** The newest stamp anywhere in a file (rows, state stamps, tombstones, the goal). */
export function maxStamp(file) {
  let max = 0;
  const take = (value) => { if (Number.isSafeInteger(value) && value > max) max = value; };
  for (const table of tables) for (const row of file[table] ?? []) { take(row.changedAt); take(row.stateAt); }
  for (const tombstone of file.tombstones ?? []) take(tombstone.deletedAt);
  take(file.netWorthGoalAt);
  take(file.healthProfile?.changedAt);
  return max;
}

const fieldsDiffer = (table, before, after, stateOnly) => {
  const state = new Set(stateFields[table] ?? []);
  const keys = new Set([...Object.keys(before), ...Object.keys(after)]);
  for (const key of keys) {
    if (STAMP_FIELDS.includes(key)) continue;
    if (stateOnly ? !state.has(key) : state.has(key)) continue;
    if (canonicalValue(before[key] ?? null) !== canonicalValue(after[key] ?? null)) return true;
  }
  return false;
};

/**
 * Assigns one operation stamp to every row the mutation changed: content fields move the content
 * stamp, the four state-field groups move the state stamp, and a physically removed row leaves a
 * tombstone. This is the web equivalent of the Android write-path audit, applied centrally.
 */
export function stampChanges(before, after, at, id) {
  const live = new Map();
  for (const table of tables) {
    const old = new Map((before[table] ?? []).map((row) => [rowKey(table, row), row]));
    const keys = new Set();
    for (const row of after[table]) {
      const key = rowKey(table, row);
      keys.add(key);
      row.changedAt = row.changedAt ?? 0;
      row.changeId = row.changeId ?? '';
      if (stateFields[table]) {
        row.stateAt = row.stateAt ?? 0;
        row.stateId = row.stateId ?? '';
      }
      const previous = old.get(key);
      if (!previous) {
        row.changedAt = at;
        row.changeId = id;
        if (stateFields[table]) { row.stateAt = at; row.stateId = id; }
        continue;
      }
      if (fieldsDiffer(table, previous, row, false)) { row.changedAt = at; row.changeId = id; }
      if (stateFields[table] && fieldsDiffer(table, previous, row, true)) { row.stateAt = at; row.stateId = id; }
    }
    live.set(table, keys);
  }
  const tombstones = new Map();
  for (const tombstone of after.tombstones ?? []) {
    if (!live.get(tombstone.table)?.has(tombstone.rowKey)) tombstones.set(tombstone.rowKey, tombstone);
  }
  for (const table of tables) {
    for (const row of before[table] ?? []) {
      const key = rowKey(table, row);
      if (!live.get(table).has(key) && !tombstones.has(key)) {
        tombstones.set(key, { table, rowKey: key, deletedAt: at, changeId: `T:${id}` });
      }
    }
  }
  after.tombstones = [...tombstones.values()];
  if (canonicalValue(after.netWorthGoal ?? null) !== canonicalValue(before.netWorthGoal ?? null)) {
    after.netWorthGoalAt = at;
    after.netWorthGoalChangeId = id;
  }
  if (after.healthProfile && (!before.healthProfile || fieldsDiffer('healthProfile', before.healthProfile, after.healthProfile, false))) {
    after.healthProfile.changedAt = at;
    after.healthProfile.changeId = id;
  }
}

/**
 * Replace semantics (import, history restore): the target's rows take the operation stamp, rows
 * of [previous] the target no longer has become tombstones, and the goal is stamped either way.
 * The intent then travels through the normal merge.
 */
export function replaceData(previous, target, at, id) {
  const data = structuredClone(target);
  const live = new Map();
  for (const table of tables) {
    for (const row of data[table]) {
      row.changedAt = at;
      row.changeId = id;
      if (stateFields[table]) { row.stateAt = at; row.stateId = id; }
    }
    live.set(table, new Set(data[table].map((row) => rowKey(table, row))));
  }
  const tombstones = new Map();
  for (const tombstone of [...(data.tombstones ?? []), ...(previous.tombstones ?? [])]) {
    if (live.get(tombstone.table)?.has(tombstone.rowKey)) continue;
    // The file's own tombstones are part of what was restored: they win as of the replace too,
    // or a newer remote row would resurrect what the import left deleted.
    tombstones.set(tombstone.rowKey, { ...tombstone, deletedAt: at, changeId: `T:${id}` });
  }
  for (const table of tables) for (const row of previous[table] ?? []) {
    const key = rowKey(table, row);
    if (live.get(table).has(key) || tombstones.has(key)) continue;
    tombstones.set(key, { table, rowKey: key, deletedAt: at, changeId: `T:${id}` });
  }
  if (!data.healthProfile) {
    tombstones.set('healthProfile#self', { table: 'healthProfile', rowKey: 'healthProfile#self', deletedAt: at, changeId: `T:${id}` });
  } else tombstones.delete('healthProfile#self');
  data.tombstones = [...tombstones.values()];
  data.netWorthGoalAt = at;
  data.netWorthGoalChangeId = id;
  if (data.netWorthGoal == null) data.netWorthGoal = null;
  if (data.healthProfile) {
    data.healthProfile.changedAt = at;
    data.healthProfile.changeId = id;
  }
  data.formatVersion = 17;
  data.schemaVersion = 15;
  data.exportedAt = Date.now();
  return data;
}
export function validate(input) {
  ensure(input && typeof input==='object' && !Array.isArray(input),'备份必须是对象');
  ensure(Number.isInteger(input.formatVersion)&&input.formatVersion>=1&&input.formatVersion<=17,'备份版本不支持，请升级网页版');
  ensure(Number.isInteger(input.schemaVersion)&&input.schemaVersion>=1&&input.schemaVersion<=15,'数据来自更新的手机版，请升级网页版');
  const data={...empty(),...input};
  const allowed = new Set(Object.keys(empty()));
  ensure(Object.keys(input).every(k=>allowed.has(k)), '备份含当前版本无法识别的字段');
  for(const [table,fields] of Object.entries(modelSchema)) {
    const rows=table==='healthProfile'?(data.healthProfile?[data.healthProfile]:[]):data[table];
    ensure(Array.isArray(rows), `${table} 数据格式错误`);
    for(const r of rows) {
      ensure(r && typeof r==='object' && !Array.isArray(r), `${table} 记录无效`);
      ensure(Object.keys(r).every(k=>k in fields), `${table} 存在手机版不支持的字段`);
      for(const [key,spec] of Object.entries(fields)) {
        if(!(key in r)){ensure(!spec.required, `${table}.${key} 缺失`);continue;}
        const v=r[key]; if(v===null){ensure(spec.nullable, `${table}.${key} 不能为空`);continue;}
        if(['Long','Int'].includes(spec.type))ensure(Number.isSafeInteger(v)&&(spec.type!=='Int'||v>=-2147483648&&v<=2147483647), `${table}.${key} 必须是整数`);
        else if(spec.type==='Double')ensure(typeof v==='number'&&Number.isFinite(v),`${table}.${key} 数值无效`);
        else if(spec.type==='Boolean')ensure(typeof v==='boolean',`${table}.${key} 必须是布尔值`);
        else ensure(typeof v==='string',`${table}.${key} 必须是文本`);
      }
    }
  }
  const ids={};
  for(const k of tables){
    ensure(Array.isArray(data[k]),`${k} 数据格式错误`);
    ensure(data[k].every(r=>r&&typeof r==='object'&&!Array.isArray(r)),`${k} 记录格式错误`);
    if(!['snapshotItems','habitChecks','habits','noteSources'].includes(k)) {
      ids[k]=new Set();
      for(const r of data[k]) {ensure(typeof r.id==='string'&&r.id&&!ids[k].has(r.id),`${k} 标识缺失或重复`);ids[k].add(r.id);}
    }
  }
  function unique(rows,key,label){const keys=rows.map(key);ensure(new Set(keys).size===keys.length,`${label} 有重复记录`);}
  const ref=(value,table,optional=false)=>ensure(optional&&value==null||ids[table].has(value),`${table} 引用不存在`);
  const validDate=s=>typeof s==='string'&&/^\d{4}-\d{2}-\d{2}$/.test(s)&&Number.isFinite(day(s).getTime())&&dateString(day(s))===s;
  for(const k of tables) for(const r of data[k]) {
    for(const [field,v] of Object.entries(r)) {
      if(typeof v==='number') ensure(Number.isFinite(v)&&(!Number.isInteger(v)||Number.isSafeInteger(v)),`${k}.${field} 数值无效`);
      if(['date','planDate','deadline','weekStart','addedOn','removedOn'].includes(field)&&v!=null) ensure(validDate(v),`${k} 日期无效`);
    }
    if('deletedAt' in r) ensure((r.deletedAt==null)===(r.deleteBatch==null),'删除标记不完整');
    // 0/"" is a valid version (a row no user change has stamped yet); it simply always loses.
    ensure(Number.isSafeInteger(r.changedAt)&&r.changedAt>=0,`${k} 缺少变更时间戳`);
    ensure(typeof r.changeId==='string',`${k} 缺少变更标识`);
    if(stateFields[k]) {
      ensure(Number.isSafeInteger(r.stateAt)&&r.stateAt>=0,`${k} 缺少状态时间戳`);
      ensure(typeof r.stateId==='string',`${k} 状态标识无效`);
    }
  }
  for(const a of data.accounts) {ref(a.groupId,'groups'); ensure(['NORMAL','INVESTMENT'].includes(a.kind),'账户类型错误');integer(a.baselineAt);}
  unique(data.valuations,r=>`${r.accountId}/${r.at}`,'估值'); unique(data.snapshots,r=>r.at,'盘点');
  for(const v of data.valuations) {ref(v.accountId,'accounts');ref(v.snapshotId,'snapshots',true);integer(v.value);integer(v.at);ensure(v.at>=data.accounts.find(a=>a.id===v.accountId).baselineAt,'估值早于期初');if(v.snapshotId)ensure(v.at===data.snapshots.find(s=>s.id===v.snapshotId).at,'盘点时间不一致');}
  unique(data.snapshotItems,r=>`${r.snapshotId}/${r.accountId}`,'盘点明细');
  for(const i of data.snapshotItems) {ref(i.snapshotId,'snapshots');ref(i.accountId,'accounts');ref(i.valuationId,'valuations');const v=data.valuations.find(v=>v.id===i.valuationId);ensure(v.accountId===i.accountId&&v.at===data.snapshots.find(s=>s.id===i.snapshotId).at&&(!v.snapshotId||v.snapshotId===i.snapshotId),'盘点明细不一致');}
  for(const f of data.flows) checkFlow(data,f);
  for(const s of data.todoSections) ref(s.listId,'todoLists');
  for(const t of data.todos) {
    ensure(typeof t.title==='string','待办标题无效');ref(t.listId,'todoLists',true);ref(t.sectionId,'todoSections',true);ref(t.repeatSourceId,'todos',true);
    if(t.sectionId) ensure(data.todoSections.find(s=>s.id===t.sectionId).listId===t.listId,'分区不属于当前清单');
    ensure(!t.remindTime || /^([01]\d|2[0-3]):[0-5]\d$/.test(t.remindTime)&&t.planDate,'提醒必须有计划日和正确时间');
    ensure((t.remindBefore??0)>=0&&(t.remindBefore??0)<=10080&&(!t.remindBefore||t.remindTime),'提前提醒无效');
    ensure(['ORDINARY','REPEAT_SOURCE','COMPLETION'].includes(t.kind),'待办类型错误');
    if(t.kind==='REPEAT_SOURCE') {ensure(t.planDate&&!t.someday&&t.completedAt==null&&t.repeatSourceId==null,'重复待办状态无效');checkRule(JSON.parse(t.repeatRule));}
    else {ensure(t.repeatRule==null,'普通待办不能有重复规则');if(t.kind==='COMPLETION')ensure(t.completedAt!=null,'完成记录缺少完成时间');}
  }
  for(const c of data.checklistItems) ref(c.todoId,'todos');
  for(const n of data.notes) ref(n.topicId,'noteTopics',true);
  for(const s of data.noteSources) {ref(s.noteId,'notes');ensure(['DAY','WEEK'].includes(s.kind)&&validDate(s.ref),'笔记来源无效');}
  for(const [table,field,target] of [['diaryDrafts','diaryId','diaryEntries'],['noteDrafts','noteId','notes'],['weeklyDrafts','reviewId','weeklyReviews']]) for(const r of data[table])ref(r[field],target,true);
  unique(live(data.diaryEntries),r=>r.date,'日记');unique(live(data.weeklyReviews),r=>r.weekStart,'周复盘');unique(data.bodyMeasurements,r=>r.date,'称重');unique(data.habitChecks,r=>`${r.date}/${r.habit}`,'打卡');unique(data.habits,r=>r.key,'习惯');unique(data.noteSources,r=>`${r.noteId}/${r.kind}/${r.ref}`,'笔记来源');
  for(const d of data.diaryEntries)ensure(d.mood==null||[1,2,3,4,5].includes(d.mood),'心情无效');
  for(const h of data.habitChecks) ensure((data.habits.length?data.habits:defaultHabits).some(x=>x.key===h.habit),'未知的习惯');
  if(data.habits.length){ensure(data.habits.filter(h=>!h.removedOn).length>=1&&data.habits.filter(h=>!h.removedOn).length<=7,'每日习惯需为 1–7 项');for(const h of data.habits)ensure(h.label?.trim()&&h.label.length<=8&&(h.rubric??'').length<=200,'习惯名称或标准过长');}
  for(const m of data.bodyMeasurements) {ensure(Number.isSafeInteger(m.weightG)&&m.weightG>=20000&&m.weightG<=300000,'体重超出范围');ensure(m.bodyFatBp==null||Number.isInteger(m.bodyFatBp)&&m.bodyFatBp>=300&&m.bodyFatBp<=7000,'体脂率超出范围');}
  for(const f of data.foodEntries) {ensure(Number.isSafeInteger(f.energyCal)&&f.energyCal>=0&&f.energyCal<=20000000,'热量超出范围');ensure((f.per==null)===(f.amount==null),'份量必须成对填写');if(f.per!=null)ensure(Number.isInteger(f.per)&&f.per>0&&f.per<=10000&&Number.isInteger(f.amount)&&f.amount>0&&f.amount<=100000&&['G','ML','SERVING'].includes(f.unit),'份量无效');for(const n of ['proteinMg','fatMg','carbMg','sodiumMg'])ensure(f[n]==null||Number.isSafeInteger(f[n])&&f[n]>=0&&f[n]<=5000000,'营养素超出范围');}
  if(data.healthProfile){const p=data.healthProfile;ensure(p.id==='self'&&['MALE','FEMALE'].includes(p.sex)&&validDate(p.birthDate)&&p.heightMm>=1000&&p.heightMm<=2500,'健康档案无效');ensure(p.targetWeightG==null||Number.isSafeInteger(p.targetWeightG)&&p.targetWeightG>=20000&&p.targetWeightG<=300000,'目标体重需在 20–300 kg 之间');ensure(Number.isSafeInteger(p.changedAt)&&p.changedAt>=0&&typeof p.changeId==='string'&&p.changeId,'健康档案缺少时间戳');}
  ensure(data.netWorthGoal==null||Number.isSafeInteger(data.netWorthGoal)&&data.netWorthGoal>0,'目标资产必须大于 0');
  ensure(Number.isSafeInteger(data.netWorthGoalAt)&&data.netWorthGoalAt>=0&&typeof data.netWorthGoalChangeId==='string','目标资产时间戳无效');
  ensure(Array.isArray(data.tombstones),'墓碑数据格式错误');
  const tombKeys=new Set();
  for(const t of data.tombstones){
    ensure(t&&typeof t==='object'&&!Array.isArray(t),'墓碑记录无效');
    ensure(Object.keys(t).every(k=>['table','rowKey','deletedAt','changeId'].includes(k)),'墓碑含未知字段');
    ensure(typeof t.table==='string'&&(tables.includes(t.table)||(data.formatVersion>=17&&t.table==='healthProfile'&&t.rowKey==='healthProfile#self')),'墓碑表名无效');
    ensure(typeof t.rowKey==='string'&&t.rowKey.startsWith(t.table+'#'),'墓碑行键无效');
    ensure(Number.isSafeInteger(t.deletedAt)&&t.deletedAt>=0,'墓碑时间无效');
    ensure(typeof t.changeId==='string'&&t.changeId,'墓碑缺少版本');
    ensure(!tombKeys.has(t.table+'/'+t.rowKey),'墓碑重复');
    tombKeys.add(t.table+'/'+t.rowKey);
  }
  ensure(data.countdowns.filter(c=>c.pinned).length<=1,'只可置顶一个倒数日');
  return data;
}
export function seedDefaultGroups(d){if(d.groups.length)return;['现金账户','储蓄账户','虚拟账户','负债账户','投资账户'].forEach((name,i)=>d.groups.push(row('groups',{name,isLiability:name==='负债账户',colorIndex:i,sortOrder:i})));}
export const flowKinds={EXPENSE:'支出',INCOME:'收入',BUY:'申购',SELL:'赎回',DIVIDEND:'分红',TRANSFER:'转账',REPAY:'还款',BORROW:'借款',EXTERNAL_IN:'外部转入',EXTERNAL_OUT:'外部转出'};
export function checkFlow(d,f) {
  ensure(flowKinds[f.kind],'流水类型错误');integer(f.amount);integer(f.fee);integer(f.at);
  ensure(f.amount>0&&f.fee>=0&&f.fee<=f.amount,'金额必须为正，手续费不能超过金额');ensure(['FROM','TO'].includes(f.feeOn),'手续费承担方无效');
  ensure(f.fromAccountId||f.toAccountId,'请选择转入或转出账户');ensure(!f.fromAccountId||f.fromAccountId!==f.toAccountId,'转入转出账户不能相同');
  for(const id of [f.fromAccountId,f.toAccountId].filter(Boolean)){const a=d.accounts.find(a=>a.id===id);ensure(a,'账户不存在');ensure(f.at>a.baselineAt,'流水必须晚于账户期初，请前移期初日期');}
  if(f.kind==='EXPENSE')ensure(f.fromAccountId&&!f.toAccountId&&f.fee===0,'支出只能从一个账户付出');
  else if(f.kind==='INCOME')ensure(f.toAccountId&&!f.fromAccountId&&f.fee===0,'收入只能进入一个账户');else ensure(f.category==null,'只有收支记账能有分类');
}
export function delta(f,id) {return (f.toAccountId===id?f.amount-f.fee:0)-(f.fromAccountId===id?f.amount:0);}
export function balance(d,id,at=Date.now()) {
  const v=d.valuations.filter(v=>v.accountId===id&&v.at<=at).sort((a,b)=>b.at-a.at)[0];
  return v?integer(v.value+d.flows.filter(f=>f.at>v.at&&f.at<=at).reduce((n,f)=>n+delta(f,id),0)):null;
}
export function invested(d,a,at=Date.now()) {if(at<a.baselineAt)return null;const v=d.valuations.find(v=>v.accountId===a.id&&v.at===a.baselineAt);return integer((a.baselineCost??v?.value??0)+d.flows.filter(f=>f.at>a.baselineAt&&f.at<=at).reduce((n,f)=>n+(f.toAccountId===a.id?f.amount-(f.feeOn==='FROM'?f.fee:0):0)-(f.fromAccountId===a.id?f.amount-(f.feeOn==='FROM'?f.fee:0):0),0));}
export function totals(d,at=Date.now()) {
  let assets=0,liabilities=0,cash=0,locked=0,investment=0;
  for(const a of d.accounts.filter(a=>a.includeInTotal&&!a.isDisabled)){const n=balance(d,a.id,at)??0;const liability=d.groups.find(g=>g.id===a.groupId)?.isLiability;if(liability)liabilities+=n;else {assets+=n;if(a.kind==='INVESTMENT')investment+=n;else if(a.lockedUntil&&a.lockedUntil>at)locked+=n;else cash+=n;}}
  return {assets:integer(assets),liabilities:integer(liabilities),netWorth:integer(assets+liabilities),cash:integer(cash),locked:integer(locked),investment:integer(investment)};
}
export function trend(d) {return [...d.snapshots].sort((a,b)=>a.at-b.at).map(s=>({...s,value:d.snapshotItems.filter(i=>i.snapshotId===s.id&&i.included).reduce((n,i)=>n+(d.valuations.find(v=>v.id===i.valuationId)?.value??0),0)}));}
export function stocktake(d,values,at=Date.now(),note='') {
  ensure(!d.snapshots.some(s=>s.at===at),'此时刻已有盘点');const s=row('snapshots',{at,note});d.snapshots.push(s);
  for(const a of d.accounts.filter(a=>!a.isDisabled&&a.baselineAt<=at)){
    const value=values[a.id]??balance(d,a.id,at);ensure(value!=null,'账户缺少期初估值');
    let v=d.valuations.find(v=>v.accountId===a.id&&v.at===at);
    if(v){ensure(!v.snapshotId,'此估值已属于盘点');v.value=value;}
    else {v=row('valuations',{accountId:a.id,at,value,snapshotId:s.id});d.valuations.push(v);}
    d.snapshotItems.push({snapshotId:s.id,accountId:a.id,valuationId:v.id,included:a.includeInTotal,changedAt:at,changeId:uid()});
  }
  ensure(d.snapshotItems.some(i=>i.snapshotId===s.id),'此日期没有可盘点的账户');
}
export const repeatNames={DAILY:'每 N 天',WEEKLY:'每周',MONTHLY_DAY:'每月几号',MONTHLY_LAST:'每月最后一天',YEARLY:'每年',WORKDAY:'法定工作日',AFTER_COMPLETION:'完成后间隔',LUNAR_YEARLY:'每年农历'};
export function checkRule(r) {ensure(r&&repeatNames[r.type],'重复规则无效');ensure(Number.isInteger(r.interval)&&r.interval>=1&&r.interval<=999,'重复间隔无效');if(r.type==='WEEKLY')ensure(r.weekdays?.length&&r.weekdays.every(n=>Number.isInteger(n)&&n>=1&&n<=7),'请选择星期');ensure(r.day>=1&&r.day<=31&&r.month>=1&&r.month<=12,'重复日期无效');ensure(r.count==null||Number.isInteger(r.count)&&r.count>=1&&r.count<=9999,'重复次数无效');}
const lunarFormatter=new Intl.DateTimeFormat('en-u-ca-chinese',{month:'numeric',day:'numeric'});
export function nextDate(rule,current,doneOn=today()) {
  checkRule(rule);
  if(rule.type==='AFTER_COMPLETION') {const d=day(doneOn);if(rule.unit==='MONTH'){const original=d.getDate();d.setDate(1);d.setMonth(d.getMonth()+rule.interval);d.setDate(Math.min(original,new Date(d.getFullYear(),d.getMonth()+1,0).getDate()));return dateString(d);}return addDays(doneOn,rule.interval*(rule.unit==='WEEK'?7:1));}
  let date=addDays(current,1); if(date<doneOn)date=doneOn;
  for(let n=0;n<3660;n++,date=addDays(date,1)){
    const d=day(date),m=d.getMonth()+1,dom=d.getDate(),last=new Date(d.getFullYear(),m,0).getDate();let match=false;
    switch(rule.type){
      case 'DAILY':match=Math.round((Date.UTC(d.getFullYear(),d.getMonth(),dom)-Date.parse(current+'T00:00:00Z'))/86400000)%rule.interval===0;break;
      case 'WEEKLY':match=rule.weekdays.includes(d.getDay()||7);break;
      case 'MONTHLY_DAY':match=dom===Math.min(rule.day,last);break;
      case 'MONTHLY_LAST':match=dom===last;break;
      case 'YEARLY':match=m===rule.month&&dom===Math.min(rule.day,last);break;
      case 'WORKDAY':match=holidays[date]?!holidays[date].isOffDay:![0,6].includes(d.getDay());break;
      case 'LUNAR_YEARLY':{const p=Object.fromEntries(lunarFormatter.formatToParts(d).map(p=>[p.type,p.value]));match=p.month===String(rule.month)&&p.day===String(rule.day);break;}
    }
    if(match)return date;
  }
  return null;
}
export function completeTodo(d,id,now=Date.now()) {
  const t=d.todos.find(t=>t.id===id);ensure(t&&!t.deletedAt,'待办不存在');
  const items=d.checklistItems.filter(c=>c.todoId===id);
  if(t.kind==='COMPLETION') {
    const source=d.todos.find(s=>s.id===t.repeatSourceId);
    ensure(source&&source.revision===t.undoRevision&&t.undoBefore,'后续已经编辑，请在日志中保留这条完成记录');
    const before=JSON.parse(t.undoBefore);Object.assign(source,{planDate:before.planDate,deadline:before.deadline,occurrenceCount:before.occurrenceCount,remindedPeriod:before.remindedPeriod,repeatEndedAt:before.ended?now:null});
    for(const c of d.checklistItems.filter(c=>c.todoId===source.id))c.done=before.checklist[c.id]??false;
    source.revision++;source.stateSeq++;d.todos=d.todos.filter(x=>x.id!==id);d.checklistItems=d.checklistItems.filter(c=>c.todoId!==id);return;
  }
  const before={planDate:t.planDate,deadline:t.deadline,checklist:Object.fromEntries(items.map(c=>[c.id,c.done])),occurrenceCount:t.occurrenceCount,remindedPeriod:t.remindedPeriod,ended:t.repeatEndedAt!=null};
  if(t.kind==='ORDINARY') {if(t.completedAt&&t.undoBefore){const b=JSON.parse(t.undoBefore);items.forEach(c=>{c.done=b.checklist[c.id]??c.done;});}else if(!t.completedAt){items.forEach(c=>{c.done=true;});t.undoBefore=JSON.stringify(before);}t.completedAt=t.completedAt?null:now;t.revision++;t.stateSeq++;return;}
  const rule=JSON.parse(t.repeatRule),next=nextDate(rule,t.planDate,dateString(new Date(now)));
  const ended=!next||(rule.count!=null&&t.occurrenceCount-(rule.countBase??0)+1>=rule.count)||(rule.untilDate&&next>rule.untilDate);
  const record={...t,id:uid(),kind:'COMPLETION',repeatRule:null,repeatSourceId:t.id,completedAt:now,createdAt:now,undoBefore:JSON.stringify(before),undoRevision:t.revision+1};
  d.todos.push(record);for(const c of items){d.checklistItems.push({...c,id:uid(),todoId:record.id,done:true});c.done=false;}
  if(!ended&&t.deadline)t.deadline=addDays(t.deadline,Math.round((day(next)-day(t.planDate))/86400000));
  if(!ended)t.planDate=next;t.repeatEndedAt=ended?now:null;t.occurrenceCount++;t.revision++;t.stateSeq++;t.snoozeAt=null;
}
export function remove(d,table,id) {
  const r=d[table].find(r=>r.id===id);ensure(r,'记录不存在');
  if(table==='accounts'){const has=d.flows.some(f=>f.fromAccountId===id||f.toAccountId===id)||d.valuations.filter(v=>v.accountId===id).length>1||d.snapshotItems.some(i=>i.accountId===id);if(has)r.isDisabled=true;else {d.accounts=d.accounts.filter(a=>a.id!==id);d.valuations=d.valuations.filter(v=>v.accountId!==id);}return;}
  if('deletedAt' in r){const batch=uid(),now=Date.now();const mark=x=>{x.deletedAt=now;x.deleteBatch=batch;if('bodyRev'in x)x.bodyRev++;if('revision'in x){x.revision++;x.stateSeq++;}};mark(r);if(table==='todoLists'){live(d.todos).filter(t=>t.listId===id).forEach(mark);live(d.todoSections).filter(t=>t.listId===id).forEach(mark);}return;}
  if(table==='groups')ensure(!d.accounts.some(a=>a.groupId===id),'分组下还有账户，请先移动');
  if(table==='noteTopics')d.notes.filter(n=>n.topicId===id).forEach(n=>{n.topicId=null;});
  if(table==='snapshots'){const vids=new Set(d.valuations.filter(v=>v.snapshotId===id).map(v=>v.id));ensure(!d.accounts.some(a=>d.valuations.some(v=>vids.has(v.id)&&v.accountId===a.id&&v.at===a.baselineAt)),'盘点包含期初估值，不能删除');d.snapshotItems=d.snapshotItems.filter(i=>i.snapshotId!==id);d.valuations=d.valuations.filter(v=>!vids.has(v.id));}
  if(table==='valuations'){ensure(!d.accounts.some(a=>a.id===r.accountId&&a.baselineAt===r.at),'期初估值不能删除');ensure(!d.snapshotItems.some(i=>i.valuationId===id),'请从盘点历史删除关联盘点');}
  d[table]=d[table].filter(x=>x.id!==id);
}
export function restoreBatch(d,batch) {for(const k of tables)for(const r of d[k])if(r.deleteBatch===batch){r.deletedAt=null;r.deleteBatch=null;if('bodyRev'in r)r.bodyRev++;}validate(d);}
export const energy=f=>Math.floor(f.per&&f.amount!=null?(f.energyCal*f.amount+f.per/2)/f.per:f.energyCal);
export function calendarFile(todos) {
  const escape=s=>String(s??'').replace(/\\/g,'\\\\').replace(/\r?\n/g,'\\n').replace(/,/g,'\\,').replace(/;/g,'\\;');
  const lines=['BEGIN:VCALENDAR','VERSION:2.0','PRODID:-//Gain//Web//ZH','CALSCALE:GREGORIAN'];
  for(const t of todos.filter(t=>t.planDate&&!t.deletedAt&&!t.completedAt)){
    const start=t.planDate.replaceAll('-','');lines.push('BEGIN:VEVENT',`UID:${t.id}@gain`,`DTSTAMP:${new Date().toISOString().replace(/[-:]/g,'').replace(/\.\d{3}/,'')}`,t.remindTime?`DTSTART:${start}T${t.remindTime.replace(':','')}00`:`DTSTART;VALUE=DATE:${start}`,`SUMMARY:${escape(t.title)}`,`DESCRIPTION:${escape(t.notes)}`);
    if(!t.remindTime)lines.push(`DTEND;VALUE=DATE:${addDays(t.planDate,1).replaceAll('-','')}`);
    else {const end=new Date(`${t.planDate}T${t.remindTime}:00`);end.setMinutes(end.getMinutes()+30);lines.push(`DTEND:${dateString(end).replaceAll('-','')}T${String(end.getHours()).padStart(2,'0')}${String(end.getMinutes()).padStart(2,'0')}00`);}
    lines.push('END:VEVENT');
  }
  lines.push('END:VCALENDAR');return lines.join('\r\n')+'\r\n';
}

export function saveDocument(d,session) {
  const {table,id,rev,baseBody,text,created,date,weekStart}=session,existing=d[table].find(r=>r.id===id);
  const dateCollision=!existing&&table!=='notes'&&live(d[table]).some(r=>table==='diaryEntries'?r.date===date:r.weekStart===weekStart);
  // Revision counters can collide across devices; the loaded body identifies the editor's base.
  if(dateCollision||created&&!existing||existing&&(existing.deletedAt||existing.bodyRev!==rev||existing.body!==baseBody)) {
    const draftTable={notes:'noteDrafts',diaryEntries:'diaryDrafts',weeklyReviews:'weeklyDrafts'}[table];
    const ref={notes:'noteId',diaryEntries:'diaryId',weeklyReviews:'reviewId'}[table];
    d[draftTable].push({id:uid(),[ref]:existing?.id??null,text,reason:'网页编辑时发现版本变化',createdAt:Date.now(),changedAt:Date.now(),changeId:uid(),...(table==='diaryEntries'?{date}:table==='weeklyReviews'?{weekStart}:{})});
    return {conflict:true,rev};
  }
  if(existing){existing.body=text;existing.bodyRev++;existing.updatedAt=Date.now();return {rev:existing.bodyRev,conflict:false};}
  d[table].push(row(table,{id,body:text,bodyRev:1,...(table==='diaryEntries'?{date}:table==='weeklyReviews'?{weekStart}:{position:-Date.now()})}));
  return {rev:1,conflict:false};
}
