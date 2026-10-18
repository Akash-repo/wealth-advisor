// The "What I know about you" panel: lists what the advisor has learned (or the user added), lets the
// user delete an entry or add one. Values are user-controlled text, so everything is written with
// textContent, never innerHTML. Relies on handleSessionExpired() from chat.js, which is loaded first.

const preferencesList = document.getElementById('preferences-list');
const preferenceForm = document.getElementById('preference-form');
const preferenceCategory = document.getElementById('preference-category');
const preferenceValueSelect = document.getElementById('preference-value-select');
const preferenceValueText = document.getElementById('preference-value-text');
const preferenceStatus = document.getElementById('preference-status');

// The fixed lists the sector and industry-group categories must pick from; loaded once.
let preferenceOptions = { categories: [], sectors: [], industryGroups: [] };

const PLACEHOLDERS = {
	EXCLUDED_TICKERS: 'Ticker, e.g. XOM',
	GOALS: 'e.g. retire around 2035',
	RISK_STANCE: 'e.g. I sell in a panic when I lose 10%',
	STYLE: 'e.g. prefer low-cost index funds',
	OTHER: 'e.g. no tobacco companies',
};

function renderPreferences(entries) {
	preferencesList.replaceChildren();
	if (entries.length === 0) {
		const empty = document.createElement('li');
		empty.className = 'list-group-item text-muted';
		empty.textContent = 'Nothing yet. Tell the advisor what you want to avoid or aim for and it will remember.';
		preferencesList.appendChild(empty);
		return;
	}
	let lastTitle = null;
	for (const entry of entries) {
		if (entry.categoryTitle !== lastTitle) {
			lastTitle = entry.categoryTitle;
			const heading = document.createElement('li');
			heading.className = 'list-group-item bg-body-tertiary small text-uppercase text-muted';
			heading.textContent = entry.categoryTitle;
			preferencesList.appendChild(heading);
		}
		preferencesList.appendChild(preferenceItem(entry));
	}
}

function preferenceItem(entry) {
	const li = document.createElement('li');
	li.className = 'list-group-item d-flex justify-content-between align-items-start gap-2';

	const text = document.createElement('div');
	const value = document.createElement('div');
	value.textContent = entry.value;
	const origin = document.createElement('div');
	origin.className = 'small text-muted';
	const when = new Date(entry.lastConfirmed).toLocaleDateString();
	origin.textContent = entry.source === 'manual' ? `Added by you · ${when}` : `Learned from a conversation · ${when}`;
	text.append(value, origin);

	const remove = document.createElement('button');
	remove.type = 'button';
	remove.className = 'btn btn-sm btn-outline-danger';
	remove.textContent = 'Delete';
	remove.addEventListener('click', async () => {
		const response = await fetch(`/api/preferences/${encodeURIComponent(entry.id)}`, { method: 'DELETE' });
		if (response.status === 401) return handleSessionExpired();
		refreshPreferences();
	});

	li.append(text, remove);
	return li;
}

async function refreshPreferences() {
	const response = await fetch('/api/preferences');
	if (response.status === 401) return handleSessionExpired();
	if (!response.ok) return;
	renderPreferences(await response.json());
}

function showValueInputFor(category) {
	const choices = category === 'EXCLUDED_SECTORS' ? preferenceOptions.sectors
		: category === 'EXCLUDED_INDUSTRY_GROUPS' ? preferenceOptions.industryGroups
		: null;
	preferenceValueSelect.classList.toggle('d-none', !choices);
	preferenceValueText.classList.toggle('d-none', Boolean(choices));
	if (choices) {
		preferenceValueSelect.replaceChildren(...choices.map((label) => new Option(label, label)));
	} else {
		preferenceValueText.placeholder = PLACEHOLDERS[category] || '';
	}
}

async function loadPreferenceOptions() {
	const response = await fetch('/api/preferences/options');
	if (!response.ok) return;
	preferenceOptions = await response.json();
	preferenceCategory.replaceChildren(...preferenceOptions.categories.map((c) => new Option(c.title, c.name)));
	showValueInputFor(preferenceCategory.value);
}

preferenceCategory.addEventListener('change', () => showValueInputFor(preferenceCategory.value));

preferenceForm.addEventListener('submit', async (event) => {
	event.preventDefault();
	const usesList = !preferenceValueSelect.classList.contains('d-none');
	const value = (usesList ? preferenceValueSelect.value : preferenceValueText.value).trim();
	if (!value) return;
	const response = await fetch('/api/preferences', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ category: preferenceCategory.value, value }),
	});
	if (response.status === 401) return handleSessionExpired();
	if (!response.ok) {
		const problem = await response.json().catch(() => ({}));
		preferenceStatus.textContent = problem.detail || 'Could not add that.';
		return;
	}
	preferenceStatus.textContent = '';
	preferenceValueText.value = '';
	refreshPreferences();
});

loadPreferenceOptions().then(refreshPreferences);
// The background memory job started by chat.js usually finishes within seconds; look once more after it.
setTimeout(refreshPreferences, 20000);
