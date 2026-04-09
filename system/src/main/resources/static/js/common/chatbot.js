(function () {
    function initChatbot() {
        const toggleBtn = document.getElementById("chatbot-toggle");
        const chatBox = document.getElementById("chatbot-box");
        const closeBtn = document.getElementById("chatbot-close");
        const messagesEl = document.getElementById("chatbot-messages");
        const inputEl = document.getElementById("chatbot-input");
        const sendBtn = document.getElementById("chatbot-send");

        if (!toggleBtn || !chatBox || !closeBtn || !messagesEl || !inputEl || !sendBtn) {
            return;
        }

        function appendMessage(text, type) {
            const div = document.createElement("div");
            div.className = "chatbot-message " + type;
            div.textContent = text;
            messagesEl.appendChild(div);
            messagesEl.scrollTop = messagesEl.scrollHeight;
            return div;
        }

        function setSendingState(sending) {
            sendBtn.disabled = sending;
            inputEl.disabled = sending;
            sendBtn.textContent = sending ? "Đang gửi..." : "Gửi";
        }

        async function sendMessage() {
            const message = inputEl.value.trim();
            if (!message) return;

            appendMessage(message, "user");
            inputEl.value = "";
            setSendingState(true);

            const loadingEl = appendMessage("Đang xử lý...", "system");

            try {
                const res = await fetch("/api/chat", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/json"
                    },
                    body: JSON.stringify({ message })
                });

                if (!res.ok) {
                    loadingEl.remove();
                    appendMessage("Hiện chưa thể kết nối chatbot. Vui lòng thử lại sau.", "system");
                    return;
                }

                const data = await res.json();
                loadingEl.remove();
                appendMessage(data.reply || "Xin lỗi, hiện mình chưa trả lời được.", "bot");
            } catch (error) {
                loadingEl.remove();
                appendMessage("Có lỗi xảy ra khi gửi câu hỏi. Vui lòng thử lại sau.", "system");
                console.error(error);
            } finally {
                setSendingState(false);
                inputEl.focus();
            }
        }

        toggleBtn.addEventListener("click", function () {
            chatBox.classList.toggle("chatbot-hidden");
            if (!chatBox.classList.contains("chatbot-hidden")) {
                inputEl.focus();
            }
        });

        closeBtn.addEventListener("click", function () {
            chatBox.classList.add("chatbot-hidden");
        });

        sendBtn.addEventListener("click", sendMessage);

        inputEl.addEventListener("keydown", function (event) {
            if (event.key === "Enter" && !event.shiftKey) {
                event.preventDefault();
                sendMessage();
            }
        });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", initChatbot);
    } else {
        initChatbot();
    }
})();