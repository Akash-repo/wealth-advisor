const form = document.getElementById('signup-form');
const errorDiv = document.getElementById('error');

form.addEventListener('submit', async (event) => {
	event.preventDefault();
	errorDiv.textContent = '';
	errorDiv.classList.add('d-none');

	const body = {
		username: document.getElementById('username').value,
		email: document.getElementById('email').value,
		password: document.getElementById('password').value,
	};

	const response = await fetch('/api/auth/register', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify(body),
	});

	if (response.ok) {
		window.location.href = '/login.html';
		return;
	}
	const data = await response.json();
	errorDiv.textContent = data.error || 'Registration failed.';
	errorDiv.classList.remove('d-none');
});
