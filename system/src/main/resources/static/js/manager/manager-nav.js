document.addEventListener("DOMContentLoaded", function () {
    const navLinks = document.querySelectorAll(".manager-link[href^='#'], .nav-link[href^='#']");

    if (!navLinks.length) {
        return;
    }

    const sections = Array.from(navLinks)
        .map(function (link) {
            const targetSelector = link.getAttribute("href");
            if (!targetSelector) {
                return null;
            }
            return document.querySelector(targetSelector);
        })
        .filter(Boolean);

    if (!sections.length) {
        return;
    }

    function setActiveLink() {
        const scrollY = window.scrollY + 140;
        let currentId = sections[0].id;

        sections.forEach(function (section) {
            if (section.offsetTop <= scrollY) {
                currentId = section.id;
            }
        });

        navLinks.forEach(function (link) {
            const isActive = link.getAttribute("href") === "#" + currentId;
            link.classList.toggle("active", isActive);
        });
    }

    setActiveLink();
    window.addEventListener("scroll", setActiveLink);
});