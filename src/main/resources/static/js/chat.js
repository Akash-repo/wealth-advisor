const chatLog = document.getElementById('chat-log');
const chatForm = document.getElementById('chat-form');
const messageInput = document.getElementById('message');
const uploadForm = document.getElementById('upload-form');
const portfolioFile = document.getElementById('portfolio-file');
const documentType = document.getElementById('document-type');
const uploadStatus = document.getElementById('upload-status');
const documentsList = document.getElementById('documents-list');
const sessionSelect = document.getElementById('session-select');
const newSessionButton = document.getElementById('new-session');
const deleteSessionButton = document.getElementById('delete-session');

// null means "new conversation": the server creates the session on the first message and returns
// its id, which we then keep sending.
let currentSessionId = null;

function handleSessionExpired() {
	uploadStatus.textContent = 'Your session has expired — please log in again.';
	window.location.href = '/login.html';
}

async function refreshDocuments() {
	const response = await fetch('/api/portfolio/documents');
	if (response.status === 401) return handleSessionExpired();
	if (!response.ok) return;
	const docs = await response.json();
	documentsList.innerHTML = '';
	if (docs.length === 0) {
		documentsList.innerHTML = '<li class="list-group-item text-muted">No documents uploaded yet.</li>';
		return;
	}
	for (const doc of docs) {
		const li = document.createElement('li');
		li.className = 'list-group-item d-flex justify-content-between align-items-center';

		const label = document.createElement('span');
		label.textContent = `${doc.filename} (${doc.documentType}, ${doc.chunkCount} chunk(s)${doc.holdingsCount ? ', ' + doc.holdingsCount + ' holding(s)' : ''})`;

		const deleteButton = document.createElement('button');
		deleteButton.type = 'button';
		deleteButton.textContent = 'Delete';
		deleteButton.className = 'btn btn-sm btn-outline-danger';
		deleteButton.addEventListener('click', async () => {
			await fetch(`/api/portfolio/documents/${encodeURIComponent(doc.filename)}`, { method: 'DELETE' });
			refreshDocuments();
		});

		li.append(label, deleteButton);
		documentsList.appendChild(li);
	}
}

uploadForm.addEventListener('submit', async (event) => {
	event.preventDefault();
	const files = Array.from(portfolioFile.files);
	if (files.length === 0) return;

	for (const file of files) {
		uploadStatus.textContent = `Uploading ${file.name}...`;
		const body = new FormData();
		body.append('file', file);
		body.append('documentType', documentType.value);
		const response = await fetch('/api/portfolio/upload', { method: 'POST', body });
		if (response.status === 401) return handleSessionExpired();
		if (!response.ok) {
			uploadStatus.textContent = `Upload failed for ${file.name}: ` + response.status;
			return;
		}
	}
	uploadStatus.textContent = `Uploaded ${files.length} document(s).`;
	portfolioFile.value = '';
	refreshDocuments();
});

function appendMessage(text, who) {
	const div = document.createElement('div');
	div.className = 'msg ' + who;
	div.textContent = text;
	chatLog.appendChild(div);
	chatLog.scrollTop = chatLog.scrollHeight;
}

chatForm.addEventListener('submit', async (event) => {
	event.preventDefault();
	const message = messageInput.value.trim();
	if (!message) return;
	appendMessage(message, 'user');
	messageInput.value = '';

	const response = await fetch('/api/chat', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ sessionId: currentSessionId, message }),
	});
	if (response.status === 401) return handleSessionExpired();
	if (response.status === 404) {
		appendMessage('That conversation no longer exists.', 'agent');
		await refreshSessions(null);
		return;
	}
	if (response.status === 409) {
		appendMessage('Still working on your previous message in this conversation — one moment.', 'agent');
		return;
	}
	if (!response.ok) {
		appendMessage('Error: ' + response.status, 'agent');
		return;
	}
	const data = await response.json();
	appendMessage(data.reply, 'agent');
	if (currentSessionId !== data.sessionId) {
		currentSessionId = data.sessionId;
		await refreshSessions(currentSessionId);
	}
});

const NEW_CHAT_VALUE = '';

async function refreshSessions(selectId) {
	const response = await fetch('/api/sessions');
	if (response.status === 401) return handleSessionExpired();
	if (!response.ok) return;
	const sessions = await response.json();
	sessionSelect.innerHTML = '';
	const fresh = document.createElement('option');
	fresh.value = NEW_CHAT_VALUE;
	fresh.textContent = '＋ New conversation';
	sessionSelect.appendChild(fresh);
	for (const s of sessions) {
		const option = document.createElement('option');
		option.value = s.id;
		option.textContent = s.title;
		sessionSelect.appendChild(option);
	}
	// 'latest' = what the page does right after login: resume the most recently active
	// conversation (the list arrives newest-first) instead of showing an empty chat.
	const target = selectId === 'latest' ? (sessions[0] && sessions[0].id) : selectId;
	const wanted = target && sessions.some((s) => s.id === target) ? target : NEW_CHAT_VALUE;
	sessionSelect.value = wanted;
	currentSessionId = wanted || null;
	deleteSessionButton.disabled = !currentSessionId;
	if (!currentSessionId) chatLog.innerHTML = '';
}

async function openSession(sessionId) {
	currentSessionId = sessionId || null;
	deleteSessionButton.disabled = !currentSessionId;
	chatLog.innerHTML = '';
	if (!currentSessionId) return;
	const response = await fetch(`/api/sessions/${encodeURIComponent(currentSessionId)}/messages`);
	if (response.status === 401) return handleSessionExpired();
	if (!response.ok) return;
	for (const m of await response.json()) appendMessage(m.text, m.role);
}

sessionSelect.addEventListener('change', () => openSession(sessionSelect.value));
newSessionButton.addEventListener('click', () => {
	sessionSelect.value = NEW_CHAT_VALUE;
	openSession(null);
});
deleteSessionButton.addEventListener('click', async () => {
	if (!currentSessionId) return;
	const response = await fetch(`/api/sessions/${encodeURIComponent(currentSessionId)}`, { method: 'DELETE' });
	if (response.status === 401) return handleSessionExpired();
	await refreshSessions(null);
});

refreshDocuments();
refreshSessions('latest').then(() => {
	if (currentSessionId) openSession(currentSessionId);
});
