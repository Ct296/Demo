document.addEventListener("DOMContentLoaded", function () {
    const sectionLinks = document.querySelectorAll(".manager-link[href^='#']");
    const sections = Array.from(sectionLinks)
        .map(link => document.querySelector(link.getAttribute("href")))
        .filter(Boolean);

    function setActiveLink() {
        const scrollY = window.scrollY + 140;
        let currentId = sections.length ? sections[0].id : null;

        sections.forEach(section => {
            if (section.offsetTop <= scrollY) {
                currentId = section.id;
            }
        });

        sectionLinks.forEach(link => {
            const isActive = link.getAttribute("href") === `#${currentId}`;
            link.classList.toggle("active", isActive);
        });
    }

    setActiveLink();
    window.addEventListener("scroll", setActiveLink);
});