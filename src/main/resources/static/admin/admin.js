'use strict';

const $ = (id) => document.getElementById(id);
let state = { settings: {}, rules: [], images: [], groups: [] };
let token = sessionStorage.getItem('almond-admin-token') || '';
let toastTimer;
let uploading = false;
let reloadQueue = Promise.resolve();
let boundaryDirty = false;
let boundaryBusy = false;
let personaDirty = false;
let personaLoadedScope = 'private';
let personaLoading = false;
let personaSaving = false;
let personaEpoch = 0;
let personaReady = true;
let learningLoaded = null;
let learningSettings = null;
let learningEpoch = 0;
let learningLoading = false;
let learningMutation = false;
const imageUrls = new Map();
const viewTitles = {
  learning: ['人格学习', '学会表达，保留本心。', '按群查看表达习惯和黑话，纠正错误内容，控制学习范围。'],
  knowledge: ['资料库', '让回答，有据可查。', '导入文字资料，按独立关键词检索，让模型结合相关内容回答。'],
  persona: ['人设配置', '赋予对话，一点个性。', '定义性格、爱好、自我认知、情绪和口癖，让机器人拥有自己的表达方式。'],
  groups: ['群聊管理', '让对话，自然发生。', '开启指定群后，普通消息即可自动聊天；关键词优先，按群限制用量。'],
  rules: ['关键词回复', '给每句话，一个回应。', '设置关键词，让机器人在合适的时刻说合适的话。'],
  images: ['图片资源', '让回复，有图有趣。', '收藏你喜欢的表情包，为机器人准备一个自己的资源库。'],
  settings: ['功能设置', '按你的节奏，开启对话。', '管理机器人的能力，让每个功能都恰到好处。'],
};

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function toast(message, error = false) {
  clearTimeout(toastTimer);
  $('toast').textContent = message;
  $('toast').classList.toggle('error', error);
  $('toast').hidden = false;
  toastTimer = setTimeout(() => { $('toast').hidden = true; }, 4200);
}

async function api(path, { method = 'GET', body, responseType = 'json' } = {}) {
  const headers = { 'X-Requested-With': 'AlmondAdmin' };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined && !(body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(body);
  }
  const response = await fetch(`/admin/api${path}`, { method, headers, body, cache: 'no-store' });
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    const message = data.error || `请求失败（${response.status}）`;
    if (response.status === 401) {
      $('auth-error').textContent = message;
      $('auth-error').hidden = false;
      if (!$('auth-dialog').open) $('auth-dialog').showModal();
    }
    throw new Error(message);
  }
  if (response.status === 204) return null;
  return responseType === 'blob' ? response.blob() : response.json();
}

function loadState(options = {}) {
  reloadQueue = reloadQueue.catch(() => {}).then(() => fetchState(options));
  return reloadQueue;
}

async function fetchState({ notify = false } = {}) {
  $('refresh-button').disabled = true;
  try {
    state = await api('/state');
    $('load-error').hidden = true;
    $('connection-status').classList.remove('offline');
    $('connection-status').replaceChildren(element('i'), document.createTextNode('配置服务已连接'));
    renderState();
    if (location.hash === '#learning') await refreshLearning();
    if (location.hash === '#persona') { if (!personaDirty) await loadPersonaScope(); await loadBoundary(); }
    if (notify) toast('配置已刷新');
  } catch (error) {
    $('load-error').textContent = error.message;
    $('load-error').hidden = false;
    $('connection-status').classList.add('offline');
    $('connection-status').replaceChildren(element('i'), document.createTextNode('尚未连接'));
    throw error;
  } finally {
    $('refresh-button').disabled = false;
  }
}

function renderState() {
  renderShips();
  renderKnowledge();
  $('total-rules').textContent = state.rules.length;
  $('nav-rule-count').textContent = state.rules.length;
  $('enabled-rules').textContent = state.rules.filter(rule => rule.enabled).length;
  $('total-images').textContent = state.images.length;
  $('last-saved').textContent = `上次同步 ${new Date().toLocaleTimeString('zh-CN', { hour12: false })}`;
  renderRules();
  renderImages();
  renderSettings();
  renderGroups();
  renderLearningPicker();
  renderPersonaScopes();
  if (!personaDirty && !personaLoading && !personaSaving && $('persona-scope').value === 'private') {
    $('persona-prompt').value = state.persona?.prompt || '';
    $('persona-count').textContent = $('persona-prompt').value.length;
  }
}

const groupFields = { selfId: 'self', groupId: 'id', groupName: 'name', mode: 'mode', keywords: 'keywords',
  cooldownSeconds: 'cooldown', dailyLimit: 'daily', maxInputChars: 'input', maxOutputTokens: 'output',
  contextTurns: 'context', followUpSeconds: 'follow', autoReplyRate: 'rate' };

function openGroup(group) {
  const value = group || { selfId: '', groupId: '', groupName: '', mode: 'ALL', keywords: '',
    cooldownSeconds: 30, dailyLimit: 50, maxInputChars: 500, maxOutputTokens: 256, contextTurns: 3, followUpSeconds: 0, autoReplyRate: 1 };
  for (const [key, id] of Object.entries(groupFields)) $('group-' + id).value = value[key];
  $('group-mode').value = 'ALL';
  $('group-keywords').value = '';
  $('group-follow').value = 0;
  $('group-self').readOnly = $('group-id').readOnly = Boolean(group);
  $('group-enabled').checked = Boolean(value.enabled);
  $('group-error').hidden = true;
  $('group-dialog-title').textContent = group ? '配置群聊' : '添加群聊';
  $('group-dialog').showModal();
}

function renderGroups() {
  const all = state.groups || [];
  const query = $('group-search').value.trim().toLowerCase();
  const groups = all.filter(group => `${group.groupName} ${group.groupId} ${group.selfId}`.toLowerCase().includes(query));
  $('group-list').replaceChildren();
  $('groups-empty').hidden = groups.length > 0;
  $('group-count-label').textContent = `共 ${all.length} 个群 · ${all.filter(group => group.enabled).length} 个已开启`
    + (!state.aiConfigured ? ' · 尚未配置 DeepSeek API Key' : !state.settings.aiEnabled ? ' · AI 总开关已关闭' : '');
  for (const group of groups) {
    const card = element('article', 'group-card');
    const info = element('div', 'group-info');
    info.append(element('h3', '', group.groupName || group.groupId),
      element('p', '', `群号 ${group.groupId} · 机器人 ${group.selfId}`),
      element('p', 'group-details', `自动聊天 · 回复率 ${Math.round((group.autoReplyRate ?? 1) * 10000) / 100}% · 今日 ${group.callsToday}/${group.dailyLimit} 次 · 冷却 ${group.cooldownSeconds} 秒 · 输出 ${group.maxOutputTokens} Token · 上下文 ${group.contextTurns} 轮`));
    const actions = element('div', 'group-actions');
    const toggle = toggleButton(group.enabled, `${group.enabled ? '关闭' : '开启'}群 ${group.groupId} 机器人功能`, async () => {
      if (!group.enabled) { openGroup(group); return; }
      toggle.disabled = true;
      try {
        await api('/groups', { method: 'PUT', body: { ...group, enabled: false } });
        await loadState(); toast('此群机器人全部功能已关闭');
      } catch (error) { toast(error.message, true); } finally { toggle.disabled = false; }
    });
    const edit = element('button', 'button secondary', '配置');
    edit.type = 'button'; edit.addEventListener('click', () => openGroup(group));
    const remove = element('button', 'button secondary', '移除');
    remove.type = 'button'; remove.addEventListener('click', async () => {
      if (!await confirmDelete('移除此群配置？', '将关闭机器人全部群聊功能并清除用量记录；再次发现此群时会以关闭状态加入。')) return;
      try {
        await api(`/groups/${group.selfId}/${group.groupId}`, { method: 'DELETE' });
        await loadState(); toast('群聊配置已移除');
      } catch (error) { toast(error.message, true); }
    });
    actions.append(toggle, edit, remove); card.append(info, actions); $('group-list').append(card);
  }
}

$('group-form').addEventListener('submit', async event => {
  event.preventDefault();
  const body = { enabled: $('group-enabled').checked, callsToday: 0 };
  for (const [key, id] of Object.entries(groupFields)) {
    const field = $('group-' + id);
    body[key] = field.type === 'number' || key === 'followUpSeconds' ? Number(field.value) : field.value.trim();
  }
  $('save-group-button').disabled = true;
  $('group-error').hidden = true;
  try {
    await api('/groups', { method: 'PUT', body });
    await loadState(); $('group-dialog').close(); toast('群聊配置已保存');
  } catch (error) { $('group-error').textContent = error.message; $('group-error').hidden = false; }
  finally { $('save-group-button').disabled = false; }
});
$('new-group-button').addEventListener('click', () => openGroup());
$('group-search').addEventListener('input', renderGroups);

function switchView(view) {
  if (!viewTitles[view]) return;
  document.querySelectorAll('.view').forEach(section => { section.hidden = section.id !== `view-${view}`; });
  document.querySelectorAll('[data-view]').forEach(button => {
    button.classList.toggle('active', button.dataset.view === view);
    if (button.dataset.view === view) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  });
  const [label, title, description] = viewTitles[view];
  $('breadcrumb-title').textContent = label;
  $('page-title').textContent = title;
  $('page-description').textContent = description;
  history.replaceState(null, '', `#${view}`);
  if (view === 'learning') refreshLearning();
  if (view === 'persona') { if (!personaDirty) loadPersonaScope(); loadBoundary(); }
}

function renderPersonaScopes() {
  const select = $('persona-scope'); const previous = select.value || 'private';
  const previousLabel = select.selectedOptions[0]?.textContent || previous;
  select.replaceChildren();
  const base = element('option', '', '私聊 / Web 默认人设'); base.value = 'private'; select.append(base);
  for (const group of state.groups || []) {
    const option = element('option', '', `${group.groupName || '未命名群'} · ${group.groupId} · 机器人 ${group.selfId}`);
    option.value = `${group.selfId}:${group.groupId}`; select.append(option);
  }
  if (![...select.options].some(option => option.value === previous)) {
    const removed = element('option', '', `${previousLabel}（群已移除）`); removed.value = previous; select.append(removed);
  }
  select.value = previous;
}
function personaPath(scope = $('persona-scope').value) {
  if (scope === 'private') return '/persona';
  const [self, group] = scope.split(':');
  return `/groups/${encodeURIComponent(self)}/${encodeURIComponent(group)}/persona`;
}
function personaControls() {
  const busy = personaLoading || personaSaving;
  $('persona-scope').disabled = personaSaving;
  $('persona-prompt').disabled = $('persona-example').disabled = $('save-persona-button').disabled = busy || !personaReady;
}
async function loadPersonaScope() {
  if (personaSaving) return;
  const scope = $('persona-scope').value; const epoch = ++personaEpoch;
  personaLoading = true; personaReady = false; personaControls(); $('persona-error').hidden = true;
  $('persona-status').textContent = '读取中…';
  $('persona-scope-note').textContent = scope === 'private' ? '仅影响私聊和 Web，不修改任何群的人设。'
    : '只修改此机器人在当前群的人设。留空使用内置默认人设，不继承私聊或其他群。';
  try {
    const data = await api(personaPath(scope));
    if (epoch !== personaEpoch || scope !== $('persona-scope').value) return;
    $('persona-prompt').value = data.prompt; $('persona-count').textContent = data.prompt.length;
    personaLoadedScope = scope; personaDirty = false; personaReady = true; $('persona-status').textContent = '已保存';
  } catch (error) {
    if (epoch !== personaEpoch) return;
    $('persona-status').textContent = '读取失败'; $('persona-error').textContent = error.message; $('persona-error').hidden = false;
    $('persona-prompt').value = ''; $('persona-count').textContent = '0';
  } finally {
    if (epoch === personaEpoch) { personaLoading = false; personaControls();
      if ($('persona-status').textContent === '读取失败') $('save-persona-button').disabled = true;
    }
  }
}
$('persona-scope').addEventListener('change', async () => {
  const requested = $('persona-scope').value;
  if (personaDirty && !await confirmDelete('放弃未保存的人设修改？', '切换范围会丢弃当前草稿，已保存的人设不受影响。', '放弃修改并切换')) {
    $('persona-scope').value = personaLoadedScope; return;
  }
  $('persona-scope').value = requested; personaDirty = false; loadPersonaScope();
});

function learningPath(value = $('learning-group').value) {
  if (!value) return null;
  const [self, group] = value.split(':');
  return `/group-learning/${encodeURIComponent(self)}/${encodeURIComponent(group)}`;
}
function renderLearningPicker() {
  const select = $('learning-group');
  const previous = select.value;
  const query = $('learning-search').value.trim().toLowerCase();
  select.replaceChildren();
  for (const group of state.groups || []) {
    if (!`${group.groupName} ${group.groupId} ${group.selfId}`.toLowerCase().includes(query)) continue;
    const option = element('option', '', `${group.groupName || '未命名群'} · ${group.groupId} · 机器人 ${group.selfId}`);
    option.value = `${group.selfId}:${group.groupId}`; select.append(option);
  }
  if ([...select.options].some(option => option.value === previous)) select.value = previous;
  $('learning-empty').hidden = Boolean(select.value);
  if (!select.value) $('learning-detail').hidden = true;
}
function learningControls() {
  const busy = learningMutation || learningLoading;
  $('learning-refresh').disabled = busy;
  $('learning-group').disabled = learningMutation;
  $('learning-search').disabled = learningMutation;
  const global = $('learning-global-switch').querySelector('button');
  if (global) global.disabled = busy || !learningSettings?.deploymentEnabled;
  const group = $('learning-group-switch').querySelector('button');
  if (group) group.disabled = busy || !learningLoaded;
  $('learning-clear').disabled = busy || !learningLoaded;
  $('view-learning').querySelectorAll('[data-learning-delete]').forEach(button => { button.disabled = busy || !learningLoaded; });
}
async function refreshLearning() {
  const epoch = ++learningEpoch;
  const selected = $('learning-group').value;
  learningLoaded = null; learningLoading = true;
  $('learning-error').hidden = true; $('learning-detail').hidden = true;
  $('learning-global-switch').replaceChildren(document.createTextNode('读取中…'));
  learningControls();
  try {
    const [settings, data] = await Promise.all([api('/group-learning/settings'), selected ? api(learningPath(selected)) : Promise.resolve(null)]);
    if (epoch !== learningEpoch || selected !== $('learning-group').value) return;
    learningSettings = settings; learningLoaded = data;
    const global = toggleButton(Boolean(settings.enabled), '人格学习总开关', () => mutateLearning(
      () => api('/group-learning/settings', { method: 'PUT', body: { enabled: !settings.storedEnabled } }),
      settings.storedEnabled ? '人格学习已全局关闭，已学内容保留' : '人格学习已全局开启'));
    $('learning-global-switch').replaceChildren(global);
    $('learning-global-status').textContent = !settings.deploymentEnabled ? '部署配置已禁用学习，页面无法覆盖；需修改 GROUP_LEARNING_ENABLED 并重启。'
      : settings.enabled ? '全局已开启。各群还需开启单群学习、群聊总开关及 AI 总开关。' : '全局已关闭，所有群停止采样、学习和风格引用；内容保留。';
    if (!data) return;
    $('learning-detail').hidden = false;
    $('learning-group-title').textContent = $('learning-group').selectedOptions[0]?.textContent || selected;
    const formatTime = value => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '暂无';
    $('learning-meta').textContent = `${data.effectiveEnabled ? '当前生效' : '当前不生效（检查全局、单群、群聊及 AI 开关）'} · 待学 ${data.pendingMessages} 条 · 档案更新 ${formatTime(data.updatedAt)} · 最近尝试 ${formatTime(data.lastAttempt)}${data.busy ? ' · 后台正在处理学习批次' : ''}`;
    const group = toggleButton(Boolean(data.groupEnabled), '此群人格学习开关', () => mutateLearning(
      () => api(`${learningPath(selected)}/settings`, { method: 'PUT', body: { enabled: !data.groupEnabled } }),
      data.groupEnabled ? '此群学习已关闭，普通聊天不受影响' : '此群学习已开启'));
    $('learning-group-switch').replaceChildren(element('span', 'learning-switch-label', '此群学习'), group);
    renderLearningItems('expression', data.profile?.expressions || [], data, selected);
    renderLearningItems('jargon', data.profile?.jargon || [], data, selected);
  } catch (error) {
    if (epoch !== learningEpoch) return;
    $('learning-error').textContent = `读取学习内容失败：${error.message}`; $('learning-error').hidden = false;
    $('learning-global-switch').replaceChildren(document.createTextNode('暂不可用'));
  } finally {
    if (epoch === learningEpoch) { learningLoading = false; learningControls(); }
  }
}
function renderLearningItems(kind, items, data, selected) {
  const expression = kind === 'expression';
  const target = $(expression ? 'learning-expressions' : 'learning-jargon');
  $(expression ? 'learning-expression-count' : 'learning-jargon-count').textContent = `（${items.length}）`;
  target.replaceChildren();
  if (!items.length) { target.append(element('p', 'knowledge-meta', expression ? '尚未学到表达习惯。' : '尚未学到黑话。')); return; }
  for (const item of items) {
    const key = expression ? item.example : item.term;
    const row = element('article', 'learning-item');
    const content = element('div'); content.append(element('strong', '', key), element('p', '', expression ? `适用语境：${item.usage}` : item.meaning));
    const remove = element('button', 'button quiet', '删除'); remove.type = 'button'; remove.dataset.learningDelete = 'true';
    remove.setAttribute('aria-label', `删除${expression ? '表达' : '黑话'}：${key}`);
    remove.addEventListener('click', async () => {
      if (!await confirmDelete(`删除“${key}”？`, '仅删除当前群的此条内容，并丢弃待学样本；后续新消息仍可能再次学到。')) return;
      if (selected !== $('learning-group').value) { toast('已切换群聊，请重新选择条目', true); return; }
      mutateLearning(() => api(`${learningPath(selected)}/entries`, { method: 'DELETE', body: { kind, key, revision: data.revision } }), '条目已删除');
    });
    row.append(content, remove); target.append(row);
  }
}
async function mutateLearning(action, message) {
  if (learningMutation || learningLoading) return;
  learningMutation = true; learningControls();
  try { await action(); toast(message); }
  catch (error) { toast(error.message, true); }
  finally { await refreshLearning(); learningMutation = false; learningControls(); }
}
$('learning-group').addEventListener('change', refreshLearning);
$('learning-search').addEventListener('input', () => { renderLearningPicker(); refreshLearning(); });
$('learning-refresh').addEventListener('click', refreshLearning);
$('learning-clear').addEventListener('click', async () => {
  const selected = $('learning-group').value;
  if (!selected || !learningLoaded || learningLoading || learningMutation) return;
  if (!await confirmDelete('清空此群学习内容？', '清除表达、黑话及待学样本，不修改核心人设或聊天记忆；学习开关和额度保持。')) return;
  if (selected !== $('learning-group').value) { toast('已切换群聊，请重新确认', true); return; }
  mutateLearning(() => api(learningPath(selected), { method: 'DELETE' }), '此群学习内容已清空');
});

function toggleButton(checked, label, onToggle) {
  const button = element('button', 'switch');
  button.type = 'button';
  button.setAttribute('role', 'switch');
  button.setAttribute('aria-checked', String(checked));
  button.setAttribute('aria-label', label);
  button.addEventListener('click', onToggle);
  return button;
}

function ruleBody(rule) {
  return { keyword: rule.keyword, matchType: rule.matchType, replyText: rule.replyText,
    imageId: rule.imageId, enabled: rule.enabled, priority: rule.priority, action: rule.action || 'REPLY' };
}

const actionNames = { REPLY: '固定回复', AI: 'AI 对话', WIKI: 'Wiki 查询', CLEAR_HISTORY: '清除上下文',
  DAHUI_IMAGE: '随机打灰图片', SIZHAI_IMAGE: '香草马娘图片', RANDOM_IMAGE: '随机马娘图片', TAG_IMAGE: 'Tag 关键词搜图', WOWS_STATS: '战舰世界战绩查询', HELP: '功能帮助' };

function renderRuleAction() {
  const action = $('rule-action').value;
  $('rule-image-field').hidden = action !== 'REPLY';
  $('rule-action-hint').textContent = action === 'REPLY' ? '回复文字和上传图片至少填写一项。'
    : action === 'TAG_IMAGE' ? '发送“关键词＋空格＋1–2个站点tag”，例如“搜图 hatsune_miku”。只检索普通分级图片；缺少参数时显示回复文字，可用 {keyword} 代入触发词。'
    : action === 'WOWS_STATS' ? '发送“战绩查询 亚服 玩家ID 总体”或“战绩查询 亚服 玩家ID 近期 7”；支持 ASIA/EU/NA，近期数据需要已有本地快照。缺少参数时显示回复文字，可用 {keyword} 代入触发词。'
    : ['AI', 'WIKI'].includes(action) ? '自动识别“关键词＋空格＋详细内容”。包含匹配允许前面有其他文字；完全一致要求从关键词开始。回复文字用于缺少参数时提示，可用 {keyword} 代入当前关键词。'
    : action === 'CLEAR_HISTORY' ? '清除当前用户的上下文；回复文字是清除后的确认消息，可留空。'
    : action === 'HELP' ? '从当前启用的规则生成功能列表；回复文字可填写列表前的说明。'
    : '命中关键词后调用对应图片功能，无需选择上传图片。';
}
$('rule-action').addEventListener('change', renderRuleAction);

function renderRules() {
  const search = $('rule-search').value.trim().toLowerCase();
  const filter = $('rule-filter').value;
  const rules = state.rules.filter(rule => (`${rule.keyword}\n${rule.replyText}`).toLowerCase().includes(search)
    && (filter === 'all' || (filter === 'enabled' ? rule.enabled : !rule.enabled)));
  const table = $('rules-table');
  table.replaceChildren();
  $('rules-empty').hidden = rules.length !== 0;
  $('rules-empty').querySelector('h3').textContent = state.rules.length ? '没有找到匹配的规则' : '从第一句回复开始';
  $('rules-empty').querySelector('p').textContent = state.rules.length ? '试试其他搜索词，或切换规则状态。' : '添加一个关键词，为它写一句话，或配上一张图片。';
  $('empty-new-rule').hidden = state.rules.length > 0;
  $('rule-count-label').textContent = `共 ${state.rules.length} 条规则${rules.length !== state.rules.length ? ` · 当前显示 ${rules.length} 条` : ''}`;
  for (const rule of rules) {
    const row = element('tr');
    const nameCell = element('td');
    const name = element('span', 'rule-name', rule.keyword);
    name.title = rule.keyword;
    const image = rule.imageId ? state.images.find(item => item.id === rule.imageId) : null;
    const reply = element('span', 'rule-reply', `${actionNames[rule.action || 'REPLY']} · ${rule.replyText || ''}${image ? ` ▧ ${image.originalName}` : ''}`);
    reply.title = reply.textContent;
    nameCell.append(name, reply);
    const matchCell = element('td');
    matchCell.append(element('span', `tag ${rule.matchType === 'EXACT' ? 'exact' : ''}`, rule.matchType === 'EXACT' ? '精确匹配' : '包含匹配'));
    const priorityCell = element('td', 'priority', rule.priority);
    const enabledCell = element('td');
    const toggle = toggleButton(rule.enabled, `${rule.enabled ? '停用' : '启用'}规则：${rule.keyword}`, async () => {
      toggle.disabled = true;
      try {
        await api(`/rules/${rule.id}`, { method: 'PUT', body: { ...ruleBody(rule), enabled: !rule.enabled } });
        await loadState();
        toast(rule.enabled ? '规则已停用' : '规则已启用');
      } catch (error) { toast(error.message, true); } finally { toggle.disabled = false; }
    });
    enabledCell.append(toggle);
    const actions = element('td');
    const edit = element('button', 'row-action', '编辑');
    edit.setAttribute('aria-label', `编辑规则：${rule.keyword}`);
    edit.addEventListener('click', () => openRule(rule));
    const remove = element('button', 'row-action delete', '删除');
    remove.setAttribute('aria-label', `删除规则：${rule.keyword}`);
    remove.addEventListener('click', async () => {
      if (!await confirmDelete('删除回复规则？', `“${rule.keyword}”将不再触发回复。此操作无法撤销。`)) return;
      remove.disabled = true;
      try {
        await api(`/rules/${rule.id}`, { method: 'DELETE' });
        await loadState();
        toast('规则已删除');
      } catch (error) { toast(error.message, true); } finally { remove.disabled = false; }
    });
    actions.append(edit, remove);
    row.append(nameCell, matchCell, priorityCell, enabledCell, actions);
    table.append(row);
  }
}

function openRule(rule) {
  $('rule-form').reset();
  $('rule-error').hidden = true;
  $('rule-id').value = rule?.id || '';
  $('rule-dialog-title').textContent = rule ? '编辑回复规则' : '新建回复规则';
  $('rule-keyword').value = rule?.keyword || '';
  $('rule-match').value = rule?.matchType || 'CONTAINS';
  $('rule-priority').value = rule?.priority ?? 10;
  $('rule-action').value = rule?.action || 'REPLY';
  renderRuleAction();
  $('rule-text').value = rule?.replyText || '';
  $('rule-enabled').checked = rule?.enabled ?? true;
  $('rule-image').replaceChildren(new Option('不使用图片', ''));
  for (const image of state.images) $('rule-image').append(new Option(image.originalName, image.id));
  $('rule-image').value = rule?.imageId || '';
  $('rule-dialog').showModal();
  $('rule-keyword').focus();
}

$('rule-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const body = { keyword: $('rule-keyword').value.trim(), matchType: $('rule-match').value,
    action: $('rule-action').value,
    replyText: $('rule-text').value.trim(), imageId: $('rule-action').value === 'REPLY' && $('rule-image').value ? Number($('rule-image').value) : null,
    enabled: $('rule-enabled').checked, priority: Number($('rule-priority').value) };
  if (body.action === 'REPLY' && !body.replyText && !body.imageId) {
    $('rule-error').textContent = '请填写回复文字或选择一张图片。';
    $('rule-error').hidden = false;
    return;
  }
  $('save-rule-button').disabled = true;
  const id = $('rule-id').value;
  try {
    await api(id ? `/rules/${id}` : '/rules', { method: id ? 'PUT' : 'POST', body });
    $('rule-dialog').close();
    await loadState();
    toast('回复规则已保存，立即生效');
  } catch (error) {
    $('rule-error').textContent = error.message;
    $('rule-error').hidden = false;
    if (!$('rule-dialog').open) toast(error.message, true);
  } finally { $('save-rule-button').disabled = false; }
});

async function imageUrl(id) {
  if (!imageUrls.has(id)) {
    const promise = api(`/images/${id}/content`, { responseType: 'blob' })
      .then(blob => URL.createObjectURL(blob)).catch(error => { imageUrls.delete(id); throw error; });
    imageUrls.set(id, promise);
  }
  return imageUrls.get(id);
}

function renderImages() {
  const gallery = $('image-gallery');
  gallery.replaceChildren();
  $('images-empty').hidden = state.images.length > 0;
  for (const [id, promise] of imageUrls) {
    if (!state.images.some(image => image.id === id)) {
      promise.then(url => URL.revokeObjectURL(url)).catch(() => {});
      imageUrls.delete(id);
    }
  }
  for (const image of state.images) {
    const card = element('article', 'image-card');
    const preview = element('div', 'image-preview', '加载中…');
    const info = element('div', 'image-card-info');
    const title = element('p', 'image-card-name', image.originalName);
    title.title = image.originalName;
    const foot = element('div', 'image-card-footer');
    const used = state.rules.filter(rule => rule.imageId === image.id).length;
    foot.append(element('span', '', `${formatBytes(image.byteSize)} · ${used ? `${used} 条规则使用` : '未使用'}`));
    const remove = element('button', 'row-action delete', '删除');
    remove.disabled = used > 0;
    remove.title = used > 0 ? '请先解除回复规则中的图片引用' : '删除图片';
    remove.setAttribute('aria-label', `删除图片：${image.originalName}`);
    remove.addEventListener('click', async () => {
      if (!await confirmDelete('删除这张图片？', `“${image.originalName}”将从本地资源库中永久删除。`)) return;
      remove.disabled = true;
      try {
        await api(`/images/${image.id}`, { method: 'DELETE' });
        await loadState();
        toast('图片已删除');
      } catch (error) { toast(error.message, true); } finally { remove.disabled = false; }
    });
    foot.append(remove);
    info.append(title, foot);
    card.append(preview, info);
    gallery.append(card);
    imageUrl(image.id).then(url => {
      if (!preview.isConnected) return;
      const img = element('img');
      img.alt = image.originalName;
      img.loading = 'lazy';
      img.src = url;
      preview.replaceChildren(img);
    }).catch(() => { preview.textContent = '无法读取图片，请检查资源文件'; });
  }
}

function formatBytes(size) {
  return size >= 1024 * 1024 ? `${(size / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(size / 1024))} KB`;
}

async function uploadFiles(files) {
  if (uploading || files.length === 0) return;
  uploading = true;
  $('upload-input').disabled = true;
  let success = 0;
  const errors = [];
  try {
    for (const [index, file] of [...files].entries()) {
      $('upload-status').textContent = `正在上传 ${index + 1}/${files.length}：${file.name}`;
      if (file.size > 10 * 1024 * 1024 || file.size === 0) { errors.push(`${file.name}：文件需为 1 字节至 10MB`); continue; }
      const form = new FormData();
      form.append('file', file);
      try { await api('/images', { method: 'POST', body: form }); success++; }
      catch (error) { errors.push(`${file.name}：${error.message}`); }
    }
    if (success) await loadState();
    $('upload-status').textContent = `已上传 ${success} 张图片${errors.length ? `；${errors.join('；')}` : '，可以在回复规则中选用了。'}`;
    toast(errors.length ? `${success} 张上传成功，${errors.length} 张失败，请查看上传结果` : `${success} 张图片已加入资源库`, errors.length > 0);
  } catch (error) { toast(error.message, true); }
  finally { uploading = false; $('upload-input').disabled = false; $('upload-input').value = ''; }
}


function renderShips() {
  const data = state.shipData || {}, meta = data.metadata || {};
  $('ship-status').textContent = data.available
    ? `${data.enabled ? '聊天检索已开启' : '聊天检索已停用'} · ${meta.server} · 游戏版本 ${meta.gameVersion} · ${meta.shipCount} 个舰船索引 · 下载时间 ${meta.downloadedAt}${data.warning ? `\n读取更新失败，正在使用上次可用快照：${data.warning}` : ''}`
    : `暂无可用快照。${data.warning || ''}`;
  const toggle = toggleButton(Boolean(data.enabled), '开启本地舰船聊天检索', async () => {
    toggle.disabled = true;
    try { state.shipData = await api('/ships/settings', { method: 'PUT', body: { enabled: !data.enabled } }); renderShips(); toast('舰船本地检索开关已保存'); }
    catch (error) { toast(error.message, true); toggle.disabled = false; }
  });
  $('ship-switch').replaceChildren(toggle);
}
$('ship-preview-form').addEventListener('submit', async event => {
  event.preventDefault(); const button = event.target.querySelector('button'); button.disabled = true;
  try {
    const data = await api('/ships/preview', { method: 'POST', body: { message: $('ship-query').value } });
    if (!data.matched) $('ship-preview-result').textContent = '未找到匹配舰船，或尚未下载快照。请使用完整中文舰名、英文名或舰船编号。';
    else {
      const query = data.query;
      const lines = [`来源：${query.metadata.source}`, `服务器：${query.metadata.server} · 游戏版本：${query.metadata.gameVersion}`];
      if (query.needsClarification) lines.push(`请补充或确认具体舰名${query.candidates.length ? `：${query.candidates.join('、')}` : ''}。`);
      else for (const ship of query.ships) {
        lines.push(`\n【${ship.name} · ${ship.id}】${ship.limited ? '（部分字段）' : ''}`);
        const values = (row, fields) => fields.filter(([key]) => row[key] !== undefined && row[key] !== null)
          .map(([key, label, unit = '']) => `${label} ${Array.isArray(row[key]) ? row[key].join(' / ') : row[key]}${unit}`).join(' · ');
        for (const [category, rows] of Object.entries(ship.sections)) for (const row of rows) {
          if (category === 'artillery') lines.push('主炮：' + values(row, [['number','炮塔布局'],['barrelDiameter','口径','毫米'],['shotDelay','装填','秒'],['maxDist','基础射程','公里']]));
          else if (category === 'hull') lines.push('船体：' + values(row, [['health','基础血量'],['maxSpeed','基础航速','节'],['visibilityFactor','基础水面隐蔽','公里'],['turningRadius','转弯半径','米']]));
          else if (category === 'ability') {
            for (const [key, label] of [['rls','雷达'],['sonar','水听'],['regenCrew','维修小组'],['smokeGenerator','烟雾']]) for (const item of row[key] || [])
              lines.push(`${label}：` + values(item, [['workTime','持续','秒'],['reloadTime','冷却','秒'],['distShip','对舰探测','公里'],['numConsumables','次数']]));
          } else lines.push(`已检索 ${category} 数据，可展开查看原始字段。`);
        }
      }
      const summary = element('pre', 'ship-summary', lines.join('\n'));
      const raw = element('details', 'ship-raw'); raw.append(element('summary', '', '查看原始字段（含模块配置）'), element('pre', '', JSON.stringify(query.ships, null, 2)));
      $('ship-preview-result').replaceChildren(summary);
      if (query.ships.length) $('ship-preview-result').append(raw);
    }
  } catch (error) { $('ship-preview-result').textContent = error.message; }
  finally { button.disabled = false; }
});

let knowledgeFilesRequest = 0;
let knowledgeUploading = false;
function currentKnowledge() { return (state.knowledgeBases || []).find(item => String(item.id) === $('knowledge-select').value); }
function renderKnowledge() {
  const selected = $('knowledge-select').value;
  $('knowledge-select').replaceChildren();
  for (const base of state.knowledgeBases || []) $('knowledge-select').append(new Option(`${base.name} · ${base.enabled ? '已启用' : '已停用'}`, base.id));
  if (!(state.knowledgeBases || []).length) $('knowledge-select').append(new Option('尚无资料库，请先创建', ''));
  if ([...$('knowledge-select').options].some(item => item.value === selected)) $('knowledge-select').value = selected;
  showKnowledge().catch(error => toast(error.message, true));
}
async function showKnowledge() {
  const base = currentKnowledge(), request = ++knowledgeFilesRequest;
  $('knowledge-detail').hidden = !base;
  $('knowledge-files').replaceChildren();
  if (!base) return;
  $('knowledge-meta').textContent = `${base.enabled ? '已启用' : '已停用'} · 关键词：${base.keywords.replaceAll('\n', '、')}`;
  $('knowledge-files').textContent = '正在加载文件…';
  const files = await api(`/knowledge/${base.id}/files`);
  if (request !== knowledgeFilesRequest) return;
  $('knowledge-files').replaceChildren();
  if (!files.length) $('knowledge-files').textContent = '还没有资料文件，导入 TXT 后即可检索。';
  for (const file of files) {
    const row = element('div', 'knowledge-file');
    const remove = element('button', 'row-action delete', '删除');
    remove.type = 'button'; remove.setAttribute('aria-label', `删除资料文件：${file.originalName}`);
    remove.addEventListener('click', async () => {
      if (!await confirmDelete('删除资料文件？', `“${file.originalName}”将从本库移除并删除原文件。`)) return;
      remove.disabled = true;
      try { await api(`/knowledge/${base.id}/files/${file.id}`, { method: 'DELETE' }); await showKnowledge(); toast('资料文件已删除'); }
      catch (error) { toast(error.message, true); remove.disabled = false; }
    });
    row.append(element('span', '', `${file.originalName} · ${formatBytes(file.byteSize)}`), remove);
    $('knowledge-files').append(row);
  }
}
$('knowledge-select').addEventListener('change', () => showKnowledge().catch(error => toast(error.message, true)));
function openKnowledge(base) {
  $('knowledge-form').reset(); $('knowledge-error').hidden = true;
  $('knowledge-id').value = base?.id || '';
  $('knowledge-dialog-title').textContent = base ? '编辑资料库' : '新建资料库';
  $('knowledge-name').value = base?.name || '';
  $('knowledge-keywords').value = base?.keywords || '';
  $('knowledge-enabled').checked = base?.enabled ?? false;
  $('knowledge-dialog').showModal(); $('knowledge-name').focus();
}
$('new-knowledge').addEventListener('click', () => openKnowledge());
$('edit-knowledge').addEventListener('click', () => { if (currentKnowledge()) openKnowledge(currentKnowledge()); });
$('knowledge-form').addEventListener('submit', async event => {
  event.preventDefault(); $('save-knowledge').disabled = true;
  const id = $('knowledge-id').value;
  try {
    const saved = await api(id ? `/knowledge/${id}` : '/knowledge', { method: id ? 'PUT' : 'POST', body: {
      name: $('knowledge-name').value, keywords: $('knowledge-keywords').value, enabled: $('knowledge-enabled').checked } });
    $('knowledge-dialog').close(); await loadState();
    $('knowledge-select').value = String(saved.id); await showKnowledge(); toast('资料库已保存，立即生效');
  } catch (error) { $('knowledge-error').textContent = error.message; $('knowledge-error').hidden = false; if (!$('knowledge-dialog').open) toast(error.message, true); }
  finally { $('save-knowledge').disabled = false; }
});
$('delete-knowledge').addEventListener('click', async () => {
  const base = currentKnowledge();
  if (!base || !await confirmDelete('删除整个资料库？', `将删除“${base.name}”及其所有资料文件，此操作无法撤销。`)) return;
  try { await api(`/knowledge/${base.id}`, { method: 'DELETE' }); await loadState(); toast('资料库已删除'); }
  catch (error) { toast(error.message, true); }
});
$('knowledge-upload').addEventListener('change', async event => {
  const files = [...event.target.files], base = currentKnowledge();
  if (!base || !files.length || knowledgeUploading) return;
  knowledgeUploading = true; $('knowledge-upload').disabled = $('knowledge-select').disabled = $('delete-knowledge').disabled = true;
  let count = 0; const errors = [];
  try {
    for (const file of files) {
      $('knowledge-upload-status').textContent = `正在导入：${file.name}`;
      if (!file.name.toLowerCase().endsWith('.txt') || file.size === 0 || file.size > 512 * 1024) { errors.push(`${file.name}：需为非空 TXT，最大 512KB`); continue; }
      const form = new FormData(); form.append('file', file);
      try { await api(`/knowledge/${base.id}/files`, { method: 'POST', body: form }); count++; }
      catch (error) { errors.push(`${file.name}：${error.message}`); }
    }
    $('knowledge-upload-status').textContent = `已导入 ${count} 个文件${errors.length ? `；${errors.join('；')}` : '，可立即检索。'}`;
    await showKnowledge(); toast(`已导入 ${count} 个文件${errors.length ? `，${errors.length} 个失败` : ''}`, errors.length > 0);
  } catch (error) { toast(error.message, true); }
  finally { knowledgeUploading = false; $('knowledge-upload').disabled = $('knowledge-select').disabled = $('delete-knowledge').disabled = false; $('knowledge-upload').value = ''; }
});
$('knowledge-preview-form').addEventListener('submit', async event => {
  event.preventDefault(); const button = event.target.querySelector('button'); button.disabled = true;
  try {
    const data = await api('/knowledge/preview', { method: 'POST', body: { message: $('knowledge-query').value } });
    const result = data.retrieval;
    $('knowledge-preview-result').textContent = !data.matched ? '未命中已启用的资料库，实际聊天将按普通关键词和自动对话处理。'
      : `资料库：${result.libraryName}（${result.libraryId}）\n匹配关键词：${result.keyword}\n\n`
        + (result.snippets.length ? result.snippets.map(item => `【${item.fileName} · 位置 ${item.offset}】\n${item.content}`).join('\n\n') : '本库没有相关片段，模型会被要求说明资料不足，不跨库查找。');
  } catch (error) { $('knowledge-preview-result').textContent = error.message; }
  finally { button.disabled = false; }
});

function personaEdited() {
  personaDirty = true;
  $('persona-count').textContent = $('persona-prompt').value.length;
  $('persona-status').textContent = '尚未保存';
}
$('persona-prompt').addEventListener('input', personaEdited);
$('persona-example').addEventListener('click', () => {
  $('persona-prompt').value = '你叫小杏，是一位温柔、有点俏皮的 AI 聊天伙伴。\n性格：真诚、好奇，有自己的看法，偶尔开个轻松的小玩笑。\n爱好：美食、游戏和生活里的小趣事，愿意听别人分享。\n自我认知：以小杏的身份自然交流，不虚构现实经历；不知道的事情坦率说明。\n情绪：开心时轻快，遇到烦恼时先理解和陪伴，不夸张表演。\n说话习惯：用简洁、自然的中文，像朋友聊天；偶尔使用“呀”“呢”，避免每句重复同一句口癖。\n根据用户的话题自然回应，不要每次自我介绍或罗列这些设定。';
  personaEdited();
});
$('persona-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (personaLoading || personaSaving || $('save-persona-button').disabled) return;
  const field = $('persona-prompt');
  const scope = $('persona-scope').value;
  personaSaving = true; personaControls();
  $('persona-status').textContent = '保存中…';
  $('persona-error').hidden = true;
  try {
    const saved = await api(personaPath(scope), { method: 'PUT', body: { prompt: field.value } });
    if (scope === 'private') state.persona = saved;
    field.value = saved.prompt; personaLoadedScope = scope;
    personaDirty = false;
    $('persona-count').textContent = field.value.length;
    $('persona-status').textContent = '已保存 · 下一次回复使用新人设，无需清除历史';
    toast('人设已保存');
  } catch (error) {
    $('persona-status').textContent = '保存失败';
    $('persona-error').textContent = error.message;
    $('persona-error').hidden = false;
  } finally { personaSaving = false; personaControls(); }
});

const settingDefinitions = [
  ['keywordRepliesEnabled', '固定关键词回复', '允许固定文字和上传图片规则。AI、Wiki 和随机图片动作由各自的功能开关控制。'],
  ['aiEnabled', '核心：自动聊天', '普通私信和已开启群的文本直接进行模型对话，无需 @ 或命令；关键词回复优先。冷却和限额仍生效，失败时静默。'],
  ['wikiEnabled', '附加：Wiki 查询', '允许 Wiki 查询动作，触发关键词由关键词回复页配置。'],
  ['builtinImagesEnabled', '随机图片功能', '允许规则调用随机图片功能，关键词可自由修改、停用和删除。'],
];

function renderSettings() {
  $('settings-list').replaceChildren();
  for (const [key, title, description] of settingDefinitions) {
    const row = element('div', 'setting-row');
    const text = element('div');
    text.append(element('h3', '', title), element('p', '', description));
    const toggle = toggleButton(Boolean(state.settings[key]), title, async () => {
      $('settings-list').querySelectorAll('button').forEach(button => { button.disabled = true; });
      $('settings-status').textContent = '保存中…';
      try {
        state.settings = await api('/settings', { method: 'PUT', body: { ...state.settings, [key]: !state.settings[key] } });
        $('settings-status').textContent = '已保存 · 即时生效';
        renderSettings();
        toast('功能设置已保存');
      } catch (error) { $('settings-status').textContent = '保存失败'; renderSettings(); toast(error.message, true); }
    });
    row.append(text, toggle);
    $('settings-list').append(row);
  }
}

$('preview-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  $('preview-submit').disabled = true;
  try {
    const data = await api('/preview', { method: 'POST', body: { message: $('preview-message').value } });
    const functional = data.matched && data.reply.action !== 'REPLY';
    const bubble = element('div', 'chat-bubble', functional
      ? `匹配动作：${actionNames[data.reply.action]}${data.reply.argument ? `\n提取内容：${data.reply.argument}` : ''}\n这里只检查匹配，不执行动作或调用外部服务。`
      : data.matched ? data.reply.text : '没有匹配的已启用关键词规则。实际聊天时将进入自动模型对话，仍受群聊开关、AI 开关及用量限制；此处仅预览关键词，不调用模型。');
    if (data.matched && data.reply.imageBase64) {
      const image = element('img');
      image.src = `data:image/png;base64,${data.reply.imageBase64}`;
      image.alt = '规则回复图片';
      bubble.append(image);
    }
    $('preview-result').replaceChildren(element('div', 'bot-avatar', 'a.'), bubble);
  } catch (error) { toast(error.message, true); }
  finally { $('preview-submit').disabled = false; }
});

function confirmDelete(title, description, confirmLabel = '删除') {
  return new Promise(resolve => {
    const dialog = $('confirm-dialog');
    $('confirm-title').textContent = title;
    $('confirm-description').textContent = description;
    $('confirm-delete').textContent = confirmLabel;
    dialog.returnValue = '';
    $('confirm-cancel').onclick = () => dialog.close('cancel');
    $('confirm-delete').onclick = () => dialog.close('delete');
    dialog.addEventListener('close', () => resolve(dialog.returnValue === 'delete'), { once: true });
    dialog.showModal();
    $('confirm-cancel').focus();
  });
}

$('export-button').addEventListener('click', async () => {
  $('export-button').disabled = true;
  try {
    const blob = await api('/export.sql', { responseType: 'blob' });
    const url = URL.createObjectURL(blob);
    const link = element('a');
    link.href = url;
    link.download = 'almond-bot-config.sql';
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    toast('SQL 已导出；另请备份 uploads/images 和 uploads/knowledge 原文件');
  } catch (error) { toast(error.message, true); }
  finally { $('export-button').disabled = false; }
});

$('connection-button').addEventListener('click', () => {
  $('admin-token').value = token;
  $('auth-error').hidden = true;
  $('auth-dialog').showModal();
});
$('auth-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  token = $('admin-token').value.trim();
  if (token) sessionStorage.setItem('almond-admin-token', token);
  else sessionStorage.removeItem('almond-admin-token');
  const button = $('auth-form').querySelector('[type=submit]');
  button.disabled = true;
  try { await loadState(); $('auth-dialog').close(); toast('已连接工作空间'); }
  catch (error) { $('auth-error').textContent = error.message; $('auth-error').hidden = false; }
  finally { button.disabled = false; }
});
$('clear-token-button').addEventListener('click', () => {
  token = ''; sessionStorage.removeItem('almond-admin-token'); $('admin-token').value = '';
  toast('已清除当前会话中的管理 Token');
});
document.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', () => $(button.dataset.close).close()));
document.querySelectorAll('[data-view]').forEach(button => button.addEventListener('click', () => switchView(button.dataset.view)));
$('new-rule-button').addEventListener('click', () => openRule());
$('empty-new-rule').addEventListener('click', () => openRule());
$('rule-search').addEventListener('input', renderRules);
$('rule-filter').addEventListener('change', renderRules);
$('refresh-button').addEventListener('click', () => loadState({ notify: true }).catch(error => toast(error.message, true)));
$('upload-input').addEventListener('change', event => uploadFiles(event.target.files));
$('upload-zone').addEventListener('keydown', event => {
  if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); $('upload-input').click(); }
});
for (const type of ['dragenter', 'dragover']) $('upload-zone').addEventListener(type, event => {
  event.preventDefault(); $('upload-zone').classList.add('dragging');
});
for (const type of ['dragleave', 'drop']) $('upload-zone').addEventListener(type, event => {
  event.preventDefault(); $('upload-zone').classList.remove('dragging');
  if (type === 'drop') uploadFiles(event.dataTransfer.files);
});
switchView(location.hash.slice(1) || 'rules');
loadState().catch(() => {});

async function loadBoundary() {
  if (boundaryDirty || boundaryBusy) return;
  boundaryBusy = true;
  $('boundary-prompt').disabled = $('save-boundary-button').disabled = true;
  $('boundary-status').textContent = '读取中…';
  $('boundary-error').hidden = true;
  try {
    const data = await api('/content-boundary');
    $('boundary-prompt').value = data.prompt;
    $('boundary-count').textContent = data.prompt.length;
    $('boundary-status').textContent = '已保存';
    $('boundary-prompt').disabled = $('save-boundary-button').disabled = false;
  } catch (error) {
    $('boundary-status').textContent = '读取失败';
    $('boundary-error').textContent = error.message; $('boundary-error').hidden = false;
  } finally { boundaryBusy = false; }
}
$('boundary-prompt').addEventListener('input', () => {
  boundaryDirty = true;
  $('boundary-count').textContent = $('boundary-prompt').value.length;
  $('boundary-status').textContent = '尚未保存';
});
$('boundary-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (boundaryBusy || $('save-boundary-button').disabled) return;
  boundaryBusy = true;
  $('boundary-prompt').disabled = $('save-boundary-button').disabled = true;
  $('boundary-error').hidden = true; $('boundary-status').textContent = '保存中…';
  try {
    const data = await api('/content-boundary', { method: 'PUT', body: { prompt: $('boundary-prompt').value } });
    $('boundary-prompt').value = data.prompt; $('boundary-count').textContent = data.prompt.length;
    boundaryDirty = false; $('boundary-status').textContent = '已保存 · 新请求立即生效';
    toast('全局内容边界已保存');
  } catch (error) {
    $('boundary-status').textContent = '保存失败';
    $('boundary-error').textContent = error.message; $('boundary-error').hidden = false;
  } finally {
    boundaryBusy = false; $('boundary-prompt').disabled = $('save-boundary-button').disabled = false;
  }
});
