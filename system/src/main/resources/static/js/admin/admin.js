document.addEventListener("DOMContentLoaded", function () {
    initRoleFields();
    initSidebarLinks();
    initPolicyModals();
});

function initRoleFields() {
    const roleSelect = document.getElementById("roleSelect");
    const managerTypeWrapper = document.getElementById("managerTypeWrapper");
    const employmentTimeWrapper = document.getElementById("employmentTimeWrapper");
    const managerTypeSelect = document.getElementById("managerTypeSelect");
    const employmentTimeInput = document.getElementById("employmentTimeInput");

    if (!roleSelect) {
        return;
    }

    function toggleRoleFields() {
        const role = roleSelect.value;

        if (managerTypeWrapper) {
            managerTypeWrapper.style.display = role === "MANAGER" ? "block" : "none";
        }

        if (employmentTimeWrapper) {
            employmentTimeWrapper.style.display = role === "STAFF" ? "block" : "none";
        }

        if (role === "MANAGER") {
            if (managerTypeSelect) {
                managerTypeSelect.setAttribute("required", "required");
            }
            if (employmentTimeInput) {
                employmentTimeInput.removeAttribute("required");
                employmentTimeInput.value = "";
            }
        } else if (role === "STAFF") {
            if (employmentTimeInput) {
                employmentTimeInput.setAttribute("required", "required");
            }
            if (managerTypeSelect) {
                managerTypeSelect.removeAttribute("required");
                managerTypeSelect.value = "";
            }
        } else {
            if (managerTypeSelect) {
                managerTypeSelect.removeAttribute("required");
                managerTypeSelect.value = "";
            }
            if (employmentTimeInput) {
                employmentTimeInput.removeAttribute("required");
                employmentTimeInput.value = "";
            }
        }
    }

    roleSelect.addEventListener("change", toggleRoleFields);
    toggleRoleFields();
}

function initSidebarLinks() {
    const sidebarLinks = document.querySelectorAll(".sidebar-link");

    sidebarLinks.forEach(link => {
        link.addEventListener("click", function () {
            sidebarLinks.forEach(item => item.classList.remove("active"));
            this.classList.add("active");
        });
    });
}

function initPolicyModals() {
    const editPolicyBtns = document.querySelectorAll(".edit-policy-btn");
    const deletePolicyBtns = document.querySelectorAll(".delete-policy-btn");

    const editPolicyForm = document.getElementById("editPolicyForm");
    const editPolicyName = document.getElementById("editPolicyName");
    const editPolicySubject = document.getElementById("editPolicySubject");
    const editPolicyContent = document.getElementById("editPolicyContent");

    const deletePolicyForm = document.getElementById("deletePolicyForm");
    const deletePolicyNameLabel = document.getElementById("deletePolicyNameLabel");

    editPolicyBtns.forEach(btn => {
        btn.addEventListener("click", function () {
            const row = this.closest("tr");
            if (!row) {
                return;
            }

            const idInput = row.querySelector(".policy-id");
            const nameInput = row.querySelector(".policy-name");
            const subjectInput = row.querySelector(".policy-subject");
            const contentInput = row.querySelector(".policy-content");

            const policyId = idInput ? idInput.value : "";
            const policyName = nameInput ? nameInput.value : "";
            const policySubject = subjectInput ? subjectInput.value : "";
            const policyContent = contentInput ? contentInput.value : "";

            if (!policyId) {
                return;
            }

            if (editPolicyForm) {
                editPolicyForm.action = `/admin/policies/${encodeURIComponent(policyId)}/update`;
            }
            if (editPolicyName) {
                editPolicyName.value = policyName;
            }
            if (editPolicySubject) {
                editPolicySubject.value = policySubject;
            }
            if (editPolicyContent) {
                editPolicyContent.value = policyContent;
            }
        });
    });

    deletePolicyBtns.forEach(btn => {
        btn.addEventListener("click", function () {
            const row = this.closest("tr");
            if (!row) {
                return;
            }

            const idInput = row.querySelector(".policy-id");
            const nameInput = row.querySelector(".policy-name");

            const policyId = idInput ? idInput.value : "";
            const policyName = nameInput ? nameInput.value : "";

            if (!policyId) {
                return;
            }

            if (deletePolicyForm) {
                deletePolicyForm.action = `/admin/policies/${encodeURIComponent(policyId)}/delete`;
            }
            if (deletePolicyNameLabel) {
                deletePolicyNameLabel.textContent = policyName;
            }
        });
    });

    const editPolicyModalEl = document.getElementById("editPolicyModal");
    if (editPolicyModalEl) {
        editPolicyModalEl.addEventListener("hidden.bs.modal", function () {
            if (editPolicyForm) {
                editPolicyForm.removeAttribute("action");
            }
            if (editPolicyName) {
                editPolicyName.value = "";
            }
            if (editPolicySubject) {
                editPolicySubject.selectedIndex = 0;
            }
            if (editPolicyContent) {
                editPolicyContent.value = "";
            }
        });
    }

    const deletePolicyModalEl = document.getElementById("deletePolicyModal");
    if (deletePolicyModalEl) {
        deletePolicyModalEl.addEventListener("hidden.bs.modal", function () {
            if (deletePolicyForm) {
                deletePolicyForm.removeAttribute("action");
            }
            if (deletePolicyNameLabel) {
                deletePolicyNameLabel.textContent = "";
            }
        });
    }
}