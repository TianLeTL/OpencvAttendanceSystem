(() => {
  function ensureModal() {
    if (document.getElementById('logoutModal')) {
      return;
    }

    const markup = `
      <div id="logoutModal"
           class="hidden fixed inset-0 z-50 items-center justify-center px-4"
           aria-hidden="true">
        <div data-modal-overlay class="absolute inset-0 bg-gray-900/40 backdrop-blur-sm"></div>
        <div class="relative w-full max-w-md rounded-2xl bg-white shadow-2xl border border-gray-100 px-8 py-6 animate-fade-in">
          <div class="flex items-start gap-3">
            <div class="flex-shrink-0 w-12 h-12 rounded-2xl bg-red-50 text-red-600 flex items-center justify-center">
              <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                      d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
              </svg>
            </div>
            <div>
              <h3 class="text-lg font-semibold text-gray-900">Sign out?</h3>
              <p class="text-sm text-gray-600 mt-1">You will be returned to the login screen and any unsaved changes will be lost.</p>
            </div>
          </div>
          <div class="flex justify-end gap-3 mt-6">
            <button id="cancelLogoutBtn"
                    class="px-4 py-2 rounded-xl border border-gray-200 text-gray-700 hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-gray-200">
              Cancel
            </button>
            <button id="confirmLogoutBtn"
                    class="px-4 py-2 rounded-xl bg-red-600 text-white font-semibold hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-red-200">
              Sign out
            </button>
          </div>
          <button id="closeLogoutModalBtn"
                  class="absolute top-4 right-4 text-gray-400 hover:text-gray-600 focus:outline-none"
                  aria-label="Close logout modal">
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                    d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>
      </div>
    `;
    document.body.insertAdjacentHTML('beforeend', markup);
  }

  window.setupLogoutModal = function setupLogoutModal(handler) {
    const logoutBtn = document.getElementById('logoutBtn');
    if (!logoutBtn || typeof handler !== 'function') {
      return;
    }
    if (logoutBtn.dataset.logoutModalBound === '1') {
      return;
    }
    logoutBtn.dataset.logoutModalBound = '1';

    ensureModal();

    const modal = document.getElementById('logoutModal');
    const overlay = modal.querySelector('[data-modal-overlay]');
    const cancelBtn = document.getElementById('cancelLogoutBtn');
    const closeBtn = document.getElementById('closeLogoutModalBtn');
    const confirmBtn = document.getElementById('confirmLogoutBtn');

    const closeModal = () => {
      modal.classList.add('hidden');
      modal.classList.remove('flex');
    };

    const openModal = () => {
      modal.classList.remove('hidden');
      modal.classList.add('flex');
    };

    logoutBtn.addEventListener('click', (event) => {
      event.preventDefault();
      openModal();
    });

    [overlay, cancelBtn, closeBtn].forEach(el => el?.addEventListener('click', closeModal));

    confirmBtn.addEventListener('click', async () => {
      confirmBtn.disabled = true;
      const originalText = confirmBtn.textContent;
      confirmBtn.textContent = 'Signing out...';
      try {
        await handler();
      } finally {
        confirmBtn.disabled = false;
        confirmBtn.textContent = originalText;
        closeModal();
      }
    });
  };
})();
