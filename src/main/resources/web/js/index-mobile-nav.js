(function () {
    const pageMap = {
        models: 'mobileMainModels',
        hf: 'mobileMainHf',
        downloads: 'mobileMainDownload',
        settings: 'mobileMainSettings',
        llamacpp: 'mobileMainLlamaCpp',
        modelpaths: 'mobileMainModelPaths'
    };

    function setActiveButton(page) {
        const resolved = (page === 'llamacpp' || page === 'modelpaths') ? 'settings' : page;
        const nav = document.getElementById('mobileBottomNav');
        if (!nav) return;
        const btns = nav.querySelectorAll('button[data-mobile-page]');
        btns.forEach((b) => {
            const p = b.getAttribute('data-mobile-page');
            if (p === resolved) b.classList.add('active');
            else b.classList.remove('active');
        });
    }

    function showPage(page) {
        const key = pageMap[page] ? page : 'models';
        Object.keys(pageMap).forEach((k) => {
            const el = document.getElementById(pageMap[k]);
            if (el) el.style.display = (k === key) ? '' : 'none';
        });
        setActiveButton(key);

        if (key === 'downloads') {
            if (window.MobileDownloadManager && typeof window.MobileDownloadManager.start === 'function') {
                window.MobileDownloadManager.start();
            }
        } else {
            if (window.MobileDownloadManager && typeof window.MobileDownloadManager.stop === 'function') {
                window.MobileDownloadManager.stop();
            }
        }

        if (key === 'llamacpp') {
            if (window.MobileLlamaCppSetting && typeof window.MobileLlamaCppSetting.refresh === 'function') {
                window.MobileLlamaCppSetting.refresh();
            }
        } else if (key === 'modelpaths') {
            if (window.MobileModelPathSetting && typeof window.MobileModelPathSetting.refresh === 'function') {
                window.MobileModelPathSetting.refresh();
            }
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        const nav = document.getElementById('mobileBottomNav');
        if (nav) {
            nav.addEventListener('click', function (e) {
                const target = e && e.target ? e.target.closest('button[data-mobile-page]') : null;
                if (!target) return;
                const page = target.getAttribute('data-mobile-page');
                showPage(page);
            });
        }

        showPage('models');
    });

    window.MobilePage = { show: showPage };
})();
