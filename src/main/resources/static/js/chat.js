const chatLog = document.getElementById('chat-log');
const chatForm = document.getElementById('chat-form');
const messageInput = document.getElementById('message');
const uploadForm = document.getElementById('upload-form');
const portfolioFile = document.getElementById('portfolio-file');
const documentType = document.getElementById('document-type');
const uploadStatus = document.getElementById('upload-status');
const documentsList = document.getElementById('documents-list');

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
		body: JSON.stringify({ message }),
	});
	if (response.status === 401) return handleSessionExpired();
	if (!response.ok) {
		appendMessage('Error: ' + response.status, 'agent');
		return;
	}
	const data = await response.json();
	appendMessage(data.reply, 'agent');
});

refreshDocuments();
