function initWeatherWidget() {
    const widgets = document.querySelectorAll(".weather-widget");

    if (!widgets.length) {
        return;
    }

    function toSvgDataUrl(iconText) {
        const safeIcon = iconText || "❓";
        const svg = `
            <svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64">
                <text x="50%" y="50%" dominant-baseline="middle" text-anchor="middle" font-size="36">
                    ${safeIcon}
                </text>
            </svg>
        `.trim();

        return "data:image/svg+xml;charset=UTF-8," + encodeURIComponent(svg);
    }

    function renderWeather(data) {
        const hasTemperature = typeof data?.temperature !== "undefined" && data?.temperature !== null;
        const tempText = hasTemperature ? `${Math.round(Number(data.temperature))}°C` : "--°C";
        const descText = data?.description || "Khác";
        const iconText = data?.iconText || "❓";
        const iconUrl = toSvgDataUrl(iconText);

        widgets.forEach(widget => {
            const tempEl = widget.querySelector(".weather-temp");
            const descEl = widget.querySelector(".weather-desc");
            const iconEl = widget.querySelector(".weather-icon");

            if (tempEl) {
                tempEl.textContent = tempText;
            }

            if (descEl) {
                descEl.textContent = descText;
            }

            if (iconEl) {
                iconEl.src = iconUrl;
                iconEl.alt = descText;
            }

            widget.style.display = "flex";
        });
    }

    function renderFallback() {
        renderWeather({
            temperature: null,
            description: "Khác",
            iconText: "❓"
        });
    }

    fetch("/api/weather/current", {
        method: "GET",
        headers: {
            "Accept": "application/json"
        },
        cache: "no-store"
    })
        .then(async response => {
            const text = await response.text();
            console.log("Weather raw response:", text);

            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${text}`);
            }

            try {
                return JSON.parse(text);
            } catch (e) {
                throw new Error("Response không phải JSON hợp lệ: " + text);
            }
        })
        .then(data => {
            console.log("Weather parsed data:", data);
            renderWeather(data);
        })
        .catch(error => {
            console.error("Lỗi lấy dữ liệu thời tiết:", error);
            renderFallback();
        });
}

if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initWeatherWidget);
} else {
    initWeatherWidget();
}