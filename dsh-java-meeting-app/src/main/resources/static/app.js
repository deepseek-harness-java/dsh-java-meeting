const state = {
  meetings: [],
  meetingId: null,
  conversations: [],
  activeConversationId: null,
  activeStream: null
};
const $ = selector => document.querySelector(selector);

function createId() {
  if (crypto.randomUUID) return crypto.randomUUID();
  return 'id-' + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

function toast(message, error = false) {
  const item = document.createElement('div');
  item.textContent = message;
  if (error) item.classList.add('error');
  $('#toast').appendChild(item);
  setTimeout(() => item.remove(), 4000);
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[char]));
}

function renderMarkdown(text) {
  if (window.marked && window.DOMPurify) {
    return DOMPurify.sanitize(marked.parse(text));
  }
  return escapeHtml(text);
}

function setStreamStatus(text, visible = true) {
  $('#streamStatus').classList.toggle('hidden', !visible);
  $('#streamStatusText').innerHTML = `<span class="status-spinner"></span>${escapeHtml(text)}`;
}

function setBusy(busy) {
  $('#sendMessage').disabled = busy;
  $('#chatInput').disabled = busy;
  if (!busy) setStreamStatus('', false);
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  });
  const body = response.headers.get('content-type')?.includes('json') ? await response.json() : null;
  if (!response.ok) throw new Error(body?.message || `请求失败 (${response.status})`);
  return body;
}

// ---------- conversations ----------

function newConversation() {
  const conversation = { id: createId(), title: '新对话', messages: [], updatedAt: new Date().toISOString() };
  state.conversations.unshift(conversation);
  state.activeConversationId = conversation.id;
  saveConversations();
  renderConversations();
  renderChatMessages();
}

function saveConversations() {
  localStorage.setItem('dsh.meeting.conversations', JSON.stringify(state.conversations.slice(0, 50)));
}

function loadConversations() {
  try { state.conversations = JSON.parse(localStorage.getItem('dsh.meeting.conversations') || '[]'); }
  catch { state.conversations = []; }
}

function renderConversations() {
  const list = $('#conversationList');
  list.innerHTML = '';
  state.conversations.forEach(conversation => {
    const button = document.createElement('button');
    button.className = 'conv-item' + (conversation.id === state.activeConversationId ? ' active' : '');
    button.textContent = conversation.title;
    button.onclick = () => {
      state.activeConversationId = conversation.id;
      renderConversations();
      renderChatMessages();
    };
    list.appendChild(button);
  });
}

function renderChatMessages() {
  const container = $('#chatMessages');
  container.innerHTML = '';
  const conversation = state.conversations.find(item => item.id === state.activeConversationId);
  if (!conversation) return;
  conversation.messages.forEach(message => {
    const div = document.createElement('div');
    if (message.role === 'user') {
      div.className = 'msg user';
      div.textContent = message.content;
    } else {
      div.className = 'msg assistant';
      div.innerHTML = assistantHtml(message);
    }
    container.appendChild(div);
  });
  container.scrollTop = container.scrollHeight;
}

function assistantHtml(message) {
  let html = '';
  if (message.reasoning) html += `<div class="reasoning">${escapeHtml(message.reasoning.slice(-600))}</div>`;
  if (message.steps?.length) {
    html += '<div>' + message.steps.map((step, index) => `
      <details class="step-item">
        <summary>
          <span class="step-name">${escapeHtml(step.toolName || '工具 ' + (index + 1))}</span>
          <span class="step-state ${step.status === 'result' ? 'ok' : 'run'}">${step.status === 'result' ? '✓ 完成' : '… 执行中'}</span>
        </summary>
        <div class="step-detail">
          ${step.argsJson ? `<pre>${escapeHtml(step.argsJson)}</pre>` : ''}
          ${step.resultJson ? `<pre>${escapeHtml(String(step.resultJson).slice(0, 1200))}</pre>` : ''}
        </div>
      </details>`).join('') + '</div>';
  }
  html += renderMarkdown(message.content || (message.streaming ? '…' : '（本轮无内容返回）'));
  return html;
}

// ---------- meetings ----------

async function loadMeetings() {
  state.meetings = await request('/api/meetings');
  renderMeetingSelect();
}

function renderMeetingSelect() {
  const select = $('#meetingSelect');
  select.innerHTML = '<option value="">选择会议…</option>' + state.meetings.map(m =>
    `<option value="${escapeHtml(m.id)}" ${m.id === state.meetingId ? 'selected' : ''}>${escapeHtml(m.title)}（${m.chars} 字）</option>`
  ).join('');
  const meeting = state.meetings.find(m => m.id === state.meetingId);
  $('#currentName').textContent = meeting ? meeting.title : '未选择会议';
  $('#currentDetail').textContent = meeting
    ? `${meeting.chars} 字 · 摄入于 ${new Date(meeting.ingestedAt).toLocaleString()}`
    : '粘贴转录文本或选择已有会议';
  $('#statusDot').classList.toggle('ok', !!meeting);
}

// ---------- analysis panels ----------

async function loadActions() {
  if (!state.meetingId) return;
  const data = await request(`/api/meetings/${state.meetingId}/actions`, { method: 'POST' });
  $('#actionsCard').innerHTML =
    `<div class="small muted">共 ${data.actionCount} 条待办</div>` +
    (data.actions || []).map(a => `
      <div class="branch-row">
        <b>${escapeHtml(a.owner)}</b> <span class="badge info">${escapeHtml(a.verb)}</span>
        <span class="badge ${a.deadline === '未明确' ? 'warn' : 'ok'}">${escapeHtml(a.deadline)}</span>
        <div class="muted small">${escapeHtml(a.context)}</div>
      </div>`).join('') || '<div class="muted small">未提取到待办</div>';
}

async function loadDecisions() {
  if (!state.meetingId) return;
  const data = await request(`/api/meetings/${state.meetingId}/decisions`, { method: 'POST' });
  $('#decisionsCard').innerHTML =
    `<div class="small muted">共 ${data.decisionCount} 条决议</div>` +
    (data.decisions || []).map(d => `
      <div class="branch-row"><b>${escapeHtml(d.by || '集体')}</b>：${escapeHtml(d.decision)}</div>`).join('')
    || '<div class="muted small">未提取到决议</div>';
}

async function loadTopics() {
  if (!state.meetingId) return;
  const data = await request(`/api/meetings/${state.meetingId}/topics`, { method: 'POST' });
  $('#topicsCard').innerHTML =
    `<div class="small muted">${data.topicCount} 个话题段</div>` +
    (data.topics || []).map((t, i) => `
      <div class="branch-row"><b>#${i + 1} ${escapeHtml(t.leadSpeaker)}</b>（${t.lines} 行）
        <div class="muted small">${escapeHtml(t.preview)}</div>
      </div>`).join('');
}

async function loadFollowups() {
  if (!state.meetingId) return;
  const data = await request(`/api/meetings/${state.meetingId}/followups`, { method: 'POST' });
  $('#followupsCard').innerHTML =
    `<div class="metric-grid">
      <div class="metric"><b>${data.actions}</b><span>待办</span></div>
      <div class="metric"><b>${data.concernCount}</b><span>分歧点</span></div>
    </div>
    <h4 style="margin:8px 0 4px;font-size:12.5px">按负责人</h4>` +
    (data.byOwner || []).map(o => `
      <div class="bar-row"><span class="label">${escapeHtml(o.owner)}</span>
        <span class="bar" style="width:${Math.max(4, o.pendingActions * 14)}px"></span>
        <span class="num">${o.pendingActions}</span></div>`).join('') +
    '<h4 style="margin:10px 0 4px;font-size:12.5px">分歧/顾虑</h4>' +
    (data.concerns || []).map(c => `
      <div class="branch-row"><b>${escapeHtml(c.by || '未知')}</b>：${escapeHtml(c.text)}</div>`).join('')
    || '<div class="muted small">无</div>';
}

async function refreshAll() {
  await Promise.all([loadActions(), loadDecisions(), loadTopics(), loadFollowups()]
    .map(p => p.catch(e => toast(e.message, true))));
}

// ---------- chat ----------

async function sendMessage() {
  const input = $('#chatInput');
  const text = input.value.trim();
  if (!text) return;
  if (!state.meetingId) return toast('请先摄入或选择会议', true);
  if (!state.activeConversationId) newConversation();
  const conversation = state.conversations.find(item => item.id === state.activeConversationId);
  if (conversation.title === '新对话') conversation.title = text.slice(0, 30);
  conversation.messages.push({ role: 'user', content: text });
  const pending = { id: createId(), role: 'assistant', content: '', reasoning: '', steps: [], streaming: true };
  conversation.messages.push(pending);
  input.value = '';
  saveConversations();
  renderConversations();
  renderChatMessages();
  setBusy(true);
  setStreamStatus('AI 正在思考');
  try {
    await streamAssistant(state.meetingId, text, pending);
    setStreamStatus('已完成');
    setTimeout(() => setBusy(false), 450);
  } catch (error) {
    pending.content = error.name === 'AbortError' ? '**已停止生成。**' : error.message;
    setStreamStatus('执行失败');
    setTimeout(() => setBusy(false), 900);
  }
  saveConversations();
  renderChatMessages();
}

async function streamAssistant(meetingId, message, pending) {
  const controller = new AbortController();
  state.activeStream = controller;
  const response = await fetch('/api/assistant/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ agentId: state.activeConversationId, message, meetingId }),
    signal: controller.signal
  });
  if (!response.ok || !response.body) throw new Error(`请求失败 (${response.status})`);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let event = 'message';
  let data = '';
  let finished = false;
  let renderQueued = false;
  const render = () => {
    if (renderQueued) return;
    renderQueued = true;
    setTimeout(() => { renderQueued = false; renderChatMessages(); }, 60);
  };
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop();
    lines.forEach(line => {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data += `${line.slice(5).trim()}\n`;
      else if (!line && data) {
        try {
          const payload = JSON.parse(data);
          if (event === 'chunk') pending.content += payload.content || '';
          if (event === 'chunk' && pending.content) setStreamStatus('正在回复');
          if (event === 'reasoning') pending.reasoning += payload.content || '';
          if (event === 'step_break') {
            const exists = payload.callId && pending.steps.some(item => item.callId === payload.callId);
            if (!exists) pending.steps.push({ ...payload, status: 'running' });
            setStreamStatus(`正在执行 ${payload.toolName || '工具'}`);
          }
          if (event === 'tool_result') {
            const step = [...pending.steps].reverse().find(item => item.callId === payload.callId)
              || [...pending.steps].reverse().find(item => item.status === 'running');
            if (step) Object.assign(step, payload, { status: 'result' });
            else pending.steps.push({ ...payload, status: 'result' });
            setStreamStatus('正在整理结果');
          }
          if (event === 'finish' || event === 'done') {
            pending.steps.forEach(item => { if (item.status === 'running') item.status = 'result'; });
            pending.streaming = false;
            finished = true;
          }
          if (event === 'error') pending.content += `\n\n**错误：** ${payload.message || '未知错误'}`;
          render();
        } catch { }
        event = 'message';
        data = '';
      }
    });
    if (finished) break;
  }
  if (!finished) pending.streaming = false;
  state.activeStream = null;
  renderChatMessages();
}

// ---------- events ----------

$('#sendMessage').onclick = sendMessage;
$('#chatInput').addEventListener('keydown', event => {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
    event.preventDefault();
    sendMessage();
  }
});
$('#stopStream').onclick = () => state.activeStream?.abort();
$('#newConversation').onclick = () => { newConversation(); };
$('#meetingSelect').onchange = async event => {
  state.meetingId = event.target.value;
  renderMeetingSelect();
  await refreshAll();
};
$('#addMeeting').onclick = () => $('#meetingModal').classList.remove('hidden');
$('#cancelModal').onclick = () => $('#meetingModal').classList.add('hidden');
$('#meetingForm').onsubmit = async event => {
  event.preventDefault();
  const form = new FormData(event.target);
  try {
    const meeting = await request('/api/meetings', {
      method: 'POST',
      body: JSON.stringify({ title: form.get('title'), text: form.get('text') })
    });
    $('#meetingModal').classList.add('hidden');
    event.target.reset();
    state.meetingId = meeting.id;
    await loadMeetings();
    renderMeetingSelect();
    await refreshAll();
    toast('转录已保存：' + meeting.id);
  } catch (error) {
    toast(error.message, true);
  }
};
$('#deleteMeeting').onclick = async () => {
  if (!state.meetingId) return toast('请先选择会议', true);
  await request(`/api/meetings/${state.meetingId}`, { method: 'DELETE' });
  state.meetingId = null;
  await loadMeetings();
  renderMeetingSelect();
  toast('会议已删除');
};
$('#refreshResource').onclick = async () => {
  if (!state.meetingId) return toast('请先选择会议', true);
  await refreshAll();
  toast('已刷新');
};
document.querySelectorAll('[data-resource-tab]').forEach(button => {
  button.onclick = () => {
    document.querySelectorAll('[data-resource-tab]').forEach(item => item.classList.toggle('active', item === button));
    document.querySelectorAll('[data-tab]').forEach(panel => panel.classList.toggle('hidden', panel.dataset.tab !== button.dataset.resourceTab));
  };
});
$('#toggleTheme').onclick = () => {
  const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
  document.documentElement.dataset.theme = next;
  localStorage.setItem('dsh.meeting.theme', next);
  $('#toggleTheme').textContent = next === 'dark' ? '☀️' : '🌙';
};
document.documentElement.dataset.theme = localStorage.getItem('dsh.meeting.theme') || 'light';
$('#toggleTheme').textContent = document.documentElement.dataset.theme === 'dark' ? '☀️' : '🌙';

// ---------- init ----------

(async function init() {
  loadConversations();
  if (!state.conversations.length) newConversation();
  else { state.activeConversationId = state.conversations[0].id; }
  renderConversations();
  renderChatMessages();
  try {
    await loadMeetings();
    if (state.meetings.length) {
      state.meetingId = state.meetings[0].id;
      renderMeetingSelect();
      await refreshAll();
    }
  } catch (error) {
    toast(error.message, true);
  }
})();
