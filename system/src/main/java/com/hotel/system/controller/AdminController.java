package com.hotel.system.controller;

import com.hotel.system.entity.Account;
import com.hotel.system.entity.AccountStatus;
import com.hotel.system.entity.Admin;
import com.hotel.system.entity.Manager;
import com.hotel.system.entity.Policy;
import com.hotel.system.entity.Staff;
import com.hotel.system.entity.Users;
import com.hotel.system.entity.enums.AccountState;
import com.hotel.system.entity.enums.Gender;
import com.hotel.system.entity.enums.ManagerType;
import com.hotel.system.entity.enums.PolicySubject;
import com.hotel.system.entity.enums.Role;
import com.hotel.system.repository.AccountRepository;
import com.hotel.system.repository.AccountStatusRepository;
import com.hotel.system.repository.AdminRepository;
import com.hotel.system.repository.ManagerRepository;
import com.hotel.system.repository.PolicyRepository;
import com.hotel.system.repository.StaffRepository;
import com.hotel.system.repository.UsersRepository;
import com.hotel.system.service.EmailService;
import com.hotel.system.util.PasswordUtils;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789@#$%";
    private static final int DEFAULT_PASSWORD_LENGTH = 12;

    private final UsersRepository usersRepository;
    private final AccountRepository accountRepository;
    private final AccountStatusRepository accountStatusRepository;
    private final AdminRepository adminRepository;
    private final ManagerRepository managerRepository;
    private final StaffRepository staffRepository;
    private final PolicyRepository policyRepository;
    private final EmailService emailService;

    public AdminController(UsersRepository usersRepository,
                           AccountRepository accountRepository,
                           AccountStatusRepository accountStatusRepository,
                           AdminRepository adminRepository,
                           ManagerRepository managerRepository,
                           StaffRepository staffRepository,
                           PolicyRepository policyRepository,
                           EmailService emailService) {
        this.usersRepository = usersRepository;
        this.accountRepository = accountRepository;
        this.accountStatusRepository = accountStatusRepository;
        this.adminRepository = adminRepository;
        this.managerRepository = managerRepository;
        this.staffRepository = staffRepository;
        this.policyRepository = policyRepository;
        this.emailService = emailService;
    }

    @GetMapping("/dashboard")
    @Transactional
    public String adminPage(HttpSession session, Model model, RedirectAttributes redirectAttributes) {
        Users currentUser = requireAdmin(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập bằng tài khoản quản trị.");
            return "redirect:/login";
        }

        Admin admin = adminRepository.findById(currentUser.getId()).orElse(null);

        List<Account> accounts = accountRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(
                        account -> account.getUser() != null ? account.getUser().getCreateDate() : null,
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .toList();

        Map<String, AccountState> latestAccountStates = new HashMap<>();
        Map<String, String> latestAccountReasons = new HashMap<>();
        Map<String, LocalDateTime> unlockTimes = new HashMap<>();

        for (Account account : accounts) {
            if (account.getUser() == null || account.getUser().getId() == null) {
                continue;
            }

            AccountStatus effectiveStatus = getEffectiveLatestStatus(account.getUser().getId());
            AccountState state = effectiveStatus != null && effectiveStatus.getName() != null
                    ? effectiveStatus.getName()
                    : AccountState.ACTIVE;

            latestAccountStates.put(account.getUser().getId(), state);
            latestAccountReasons.put(account.getUser().getId(),
                    effectiveStatus != null ? effectiveStatus.getReason() : "");

            if (state == AccountState.LOCKED && effectiveStatus != null) {
                unlockTimes.put(account.getUser().getId(), effectiveStatus.getEndTime());
            }
        }

        List<Policy> policies = policyRepository.findAllByOrderByCreateDateDesc();

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("currentAdmin", admin);
        model.addAttribute("accounts", accounts);
        model.addAttribute("managerTypes", ManagerType.values());
        model.addAttribute("latestAccountStates", latestAccountStates);
        model.addAttribute("latestAccountReasons", latestAccountReasons);
        model.addAttribute("unlockTimes", unlockTimes);
        model.addAttribute("policies", policies);
        model.addAttribute("policySubjects", PolicySubject.values());

        return "admin/Admin";
    }

    @PostMapping("/policies/create")
    @Transactional
    public String createPolicy(@RequestParam String name,
                               @RequestParam String content,
                               @RequestParam PolicySubject subject,
                               HttpSession session,
                               RedirectAttributes redirectAttributes) {
        Users currentUser = requireAdmin(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập bằng tài khoản quản trị.");
            return "redirect:/login";
        }

        Admin admin = adminRepository.findById(currentUser.getId()).orElse(null);
        if (admin == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy hồ sơ Admin.");
            return "redirect:/admin/dashboard#policy-management-section";
        }

        String trimmedName = safeTrim(name);
        String trimmedContent = safeTrim(content);

        if (trimmedName.isBlank() || trimmedContent.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Tên và nội dung chính sách không được để trống.");
            return "redirect:/admin/dashboard#policy-management-section";
        }

        if (trimmedName.length() > 100) {
            redirectAttributes.addFlashAttribute("error", "Tên chính sách không được quá 100 ký tự.");
            return "redirect:/admin/dashboard#policy-management-section";
        }

        Policy policy = new Policy();
        policy.setPolicyNumber(generateId("POL", 10));
        policy.setName(trimmedName);
        policy.setContent(trimmedContent);
        policy.setSubject(subject);
        policy.setCreateDate(LocalDateTime.now());
        policy.setAdmin(admin);

        policyRepository.save(policy);

        redirectAttributes.addFlashAttribute("message", "Đã thêm chính sách mới thành công.");
        return "redirect:/admin/dashboard#policy-management-section";
    }

    @PostMapping("/policies/{policyNumber}/update")
    @Transactional
    public String updatePolicy(@PathVariable String policyNumber,
                               @RequestParam String name,
                               @RequestParam String content,
                               @RequestParam PolicySubject subject,
                               HttpSession session,
                               RedirectAttributes redirectAttributes) {
        Users currentUser = requireAdmin(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập bằng tài khoản quản trị.");
            return "redirect:/login";
        }

        Policy policy = policyRepository.findById(policyNumber).orElse(null);
        if (policy == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy chính sách.");
            return "redirect:/admin/dashboard#policy-management-section";
        }

        String trimmedName = safeTrim(name);
        String trimmedContent = safeTrim(content);

        if (trimmedName.isBlank() || trimmedContent.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Tên và nội dung chính sách không được để trống.");
            return "redirect:/admin/dashboard#policy-management-section";
        }

        policy.setName(trimmedName);
        policy.setContent(trimmedContent);
        policy.setSubject(subject);
        policy.setUpdateDate(LocalDateTime.now());

        policyRepository.save(policy);

        redirectAttributes.addFlashAttribute("message", "Đã cập nhật chính sách thành công.");
        return "redirect:/admin/dashboard#policy-management-section";
    }

    @PostMapping("/policies/{policyNumber}/delete")
    @Transactional
    public String deletePolicy(@PathVariable String policyNumber,
                               HttpSession session,
                               RedirectAttributes redirectAttributes) {
        Users currentUser = requireAdmin(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập bằng tài khoản quản trị.");
            return "redirect:/login";
        }

        Policy policy = policyRepository.findById(policyNumber).orElse(null);
        if (policy == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy chính sách.");
            return "redirect:/admin/dashboard#policy-management-section";
        }

        policyRepository.delete(policy);
        redirectAttributes.addFlashAttribute("message", "Đã xóa chính sách thành công.");
        return "redirect:/admin/dashboard#policy-management-section";
    }

    @PostMapping("/accounts/create")
    @Transactional
    public String createAccount(@RequestParam String firstName,
                                @RequestParam String lastName,
                                @RequestParam String email,
                                @RequestParam String pid,
                                @RequestParam String phoneNumber,
                                @RequestParam String nationality,
                                @RequestParam String dateOfBirth,
                                @RequestParam("sex") Gender gender,
                                @RequestParam Role role,
                                @RequestParam(required = false) String managerType,
                                @RequestParam(required = false) String employmentTime,
                                HttpSession session,
                                RedirectAttributes redirectAttributes) {

        Users currentUser = requireAdmin(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập bằng tài khoản quản trị.");
            return "redirect:/login";
        }

        String trimmedFirstName = safeTrim(firstName);
        String trimmedLastName = safeTrim(lastName);
        String trimmedEmail = safeTrim(email);
        String trimmedPid = safeTrim(pid);
        String trimmedPhoneNumber = safeTrim(phoneNumber);
        String trimmedNationality = safeTrim(nationality);

        if (trimmedFirstName.isBlank() || trimmedLastName.isBlank() || trimmedEmail.isBlank() ||
                trimmedPid.isBlank() || trimmedPhoneNumber.isBlank() || trimmedNationality.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng nhập đầy đủ thông tin bắt buộc.");
            return "redirect:/admin/dashboard#create-account-section";
        }

        if (!isValidEmail(trimmedEmail)) {
            redirectAttributes.addFlashAttribute("error", "Email không hợp lệ hoặc vượt quá 50 ký tự.");
            return "redirect:/admin/dashboard#create-account-section";
        }

        if (usersRepository.findByEmail(trimmedEmail).isPresent()) {
            redirectAttributes.addFlashAttribute("error", "Email đã tồn tại trong hệ thống.");
            return "redirect:/admin/dashboard#create-account-section";
        }

        if (usersRepository.findByPid(trimmedPid).isPresent()) {
            redirectAttributes.addFlashAttribute("error", "CCCD/CMND đã tồn tại trong hệ thống.");
            return "redirect:/admin/dashboard#create-account-section";
        }

        if (usersRepository.findByPhoneNumber(trimmedPhoneNumber).isPresent()) {
            redirectAttributes.addFlashAttribute("error", "Số điện thoại đã tồn tại trong hệ thống.");
            return "redirect:/admin/dashboard#create-account-section";
        }

        LocalDate dob;
        try {
            dob = LocalDate.parse(dateOfBirth);
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("error", "Ngày sinh không hợp lệ.");
            return "redirect:/admin/dashboard#create-account-section";
        }

        if (!isValidDateOfBirth(dob)) {
            redirectAttributes.addFlashAttribute("error", "Ngày sinh phải từ năm 1900 đến hiện tại.");
            return "redirect:/admin/dashboard#create-account-section";
        }

        ManagerType parsedManagerType = null;
        LocalDateTime parsedEmploymentTime = null;

        if (role == Role.MANAGER) {
            try {
                parsedManagerType = ManagerType.valueOf(safeTrim(managerType).toUpperCase());
            } catch (Exception ex) {
                redirectAttributes.addFlashAttribute("error", "Chức vụ manager không hợp lệ.");
                return "redirect:/admin/dashboard#create-account-section";
            }
        }

        if (role == Role.STAFF) {
            try {
                parsedEmploymentTime = LocalDateTime.parse(employmentTime);
            } catch (Exception ex) {
                redirectAttributes.addFlashAttribute("error", "Thời gian vào làm của staff không hợp lệ.");
                return "redirect:/admin/dashboard#create-account-section";
            }
        }

        LocalDateTime now = LocalDateTime.now();

        Users newUser = new Users();
        newUser.setId(generateId("USR", 10));
        newUser.setFirstName(trimmedFirstName);
        newUser.setLastName(trimmedLastName);
        newUser.setEmail(trimmedEmail);
        newUser.setPid(trimmedPid);
        newUser.setPhoneNumber(trimmedPhoneNumber);
        newUser.setNationality(trimmedNationality);
        newUser.setSex(gender);
        newUser.setDateOfBirth(dob);
        newUser.setRole(role);
        newUser.setCreateDate(now);
        newUser.setUpdateDate(now);
        newUser.setAvatar(role == Role.STAFF ? "default_staff_avatar.jpg" : "default_manager_avatar.jpg");
        usersRepository.saveAndFlush(newUser);

        Users managedUser = usersRepository.findById(newUser.getId())
                .orElseThrow(() -> new RuntimeException("Không thể tải lại người dùng vừa tạo."));

        String rawPassword = generateRandomPassword(DEFAULT_PASSWORD_LENGTH);

        Account newAccount = new Account();
        newAccount.setUser(managedUser);
        newAccount.setPassword(PasswordUtils.hashPassword(rawPassword));
        accountRepository.saveAndFlush(newAccount);

        Account managedAccount = accountRepository.findById(managedUser.getId())
                .orElseThrow(() -> new RuntimeException("Không thể tải lại tài khoản vừa tạo."));

        if (role == Role.MANAGER) {
            Manager manager = new Manager();
            manager.setUser(managedUser);
            manager.setTitle(parsedManagerType);
            managerRepository.saveAndFlush(manager);
        } else {
            Staff staff = new Staff();
            staff.setUser(managedUser);
            staff.setEmploymentTime(parsedEmploymentTime);
            staffRepository.saveAndFlush(staff);
        }

        AccountStatus accountStatus = new AccountStatus();
        accountStatus.setId(generateId("AST", 10));
        accountStatus.setName(AccountState.ACTIVE);
        accountStatus.setStartTime(now);
        accountStatus.setEndTime(null);
        accountStatus.setReason("Tài khoản được admin tạo mới");
        accountStatus.setAccount(managedAccount);
        accountStatusRepository.saveAndFlush(accountStatus);

        String fullName = buildFullName(trimmedFirstName, trimmedLastName);
        String roleDisplayName = role == Role.MANAGER
                ? "quản lý " + formatManagerType(parsedManagerType)
                : "nhân viên";

        try {
            emailService.sendCreatedAccountEmail(trimmedEmail, fullName, rawPassword, roleDisplayName);
            redirectAttributes.addFlashAttribute(
                    "message",
                    "Đã tạo tài khoản " + role.name()
                            + " thành công và gửi email thông tin đăng nhập tới " + trimmedEmail + "."
            );
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute(
                    "message",
                    "Đã tạo tài khoản " + role.name() + " thành công."
            );
            redirectAttributes.addFlashAttribute(
                    "error",
                    "Tài khoản đã được tạo nhưng gửi email thông tin đăng nhập tới " + trimmedEmail
                            + " thất bại: " + ex.getMessage()
            );
        }
        return "redirect:/admin/dashboard#create-account-section";
    }

    @PostMapping("/accounts/{userId}/lock")
    @Transactional
    public String lockAccount(@PathVariable String userId,
                              @RequestParam Integer durationDays,
                              @RequestParam(required = false) String reason,
                              HttpSession session,
                              RedirectAttributes redirectAttributes) {

        Users currentUser = requireAdmin(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập bằng tài khoản quản trị.");
            return "redirect:/login";
        }

        if (currentUser.getId().equals(userId)) {
            redirectAttributes.addFlashAttribute("error", "Admin không thể tự khóa chính mình.");
            return "redirect:/admin/dashboard#account-list-section";
        }

        Users targetUser = usersRepository.findById(userId).orElse(null);
        if (targetUser == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy người dùng.");
            return "redirect:/admin/dashboard#account-list-section";
        }

        Account targetAccount = accountRepository.findById(userId).orElse(null);
        if (targetAccount == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy tài khoản.");
            return "redirect:/admin/dashboard#account-list-section";
        }

        if (durationDays == null || !(durationDays == 7 || durationDays == 15 || durationDays == 30 || durationDays == -1 || durationDays == 0)) {
            redirectAttributes.addFlashAttribute("error", "Thời hạn khóa không hợp lệ. Chỉ hỗ trợ 7, 15, 30 ngày hoặc vô hạn.");
            return "redirect:/admin/dashboard#account-list-section";
        }

        AccountStatus latestStatus = getEffectiveLatestStatus(userId);
        AccountState latestState = latestStatus != null && latestStatus.getName() != null
                ? latestStatus.getName()
                : AccountState.ACTIVE;

        if (latestState == AccountState.LOCKED) {
            redirectAttributes.addFlashAttribute("error", "Tài khoản này đang bị khóa.");
            return "redirect:/admin/dashboard#account-list-section";
        }

        closeCurrentOpenStatus(targetAccount);

        LocalDateTime now = LocalDateTime.now();
        boolean indefiniteLock = durationDays == -1 || durationDays == 0;
        LocalDateTime unlockTime = indefiniteLock ? null : now.plusDays(durationDays);

        AccountStatus lockedStatus = new AccountStatus();
        lockedStatus.setId(generateId("AST", 10));
        lockedStatus.setName(AccountState.LOCKED);
        lockedStatus.setStartTime(now);
        lockedStatus.setEndTime(unlockTime);
        lockedStatus.setReason(buildLockReason(reason, indefiniteLock ? -1 : durationDays));
        lockedStatus.setAccount(targetAccount);
        accountStatusRepository.saveAndFlush(lockedStatus);

        String timeText = unlockTime == null
                ? "vô thời hạn cho đến khi admin mở thủ công"
                : "đến " + unlockTime;

        redirectAttributes.addFlashAttribute(
                "message",
                "Đã khóa tài khoản của " + targetUser.getLastName() + " " + targetUser.getFirstName() + " " + timeText
        );
        return "redirect:/admin/dashboard#account-list-section";
    }

    @PostMapping("/accounts/{userId}/unlock")
    @Transactional
    public String unlockAccount(@PathVariable String userId,
                                @RequestParam(required = false) String reason,
                                HttpSession session,
                                RedirectAttributes redirectAttributes) {

        Users currentUser = requireAdmin(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập bằng tài khoản quản trị.");
            return "redirect:/login";
        }

        Users targetUser = usersRepository.findById(userId).orElse(null);
        if (targetUser == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy người dùng.");
            return "redirect:/admin/dashboard#account-list-section";
        }

        Account targetAccount = accountRepository.findById(userId).orElse(null);
        if (targetAccount == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy tài khoản.");
            return "redirect:/admin/dashboard#account-list-section";
        }

        AccountStatus latestStatus = getEffectiveLatestStatus(userId);
        AccountState latestState = latestStatus != null && latestStatus.getName() != null
                ? latestStatus.getName()
                : AccountState.ACTIVE;

        if (latestState != AccountState.LOCKED) {
            redirectAttributes.addFlashAttribute("error", "Tài khoản này hiện không ở trạng thái khóa.");
            return "redirect:/admin/dashboard#account-list-section";
        }

        closeCurrentOpenStatus(targetAccount);

        AccountStatus activeStatus = new AccountStatus();
        activeStatus.setId(generateId("AST", 10));
        activeStatus.setName(AccountState.ACTIVE);
        activeStatus.setStartTime(LocalDateTime.now());
        activeStatus.setEndTime(null);
        activeStatus.setReason(safeTrim(reason).isBlank() ? "Admin mở khóa tài khoản" : safeTrim(reason));
        activeStatus.setAccount(targetAccount);
        accountStatusRepository.saveAndFlush(activeStatus);

        redirectAttributes.addFlashAttribute(
                "message",
                "Đã mở khóa tài khoản của " + targetUser.getLastName() + " " + targetUser.getFirstName()
        );
        return "redirect:/admin/dashboard#account-list-section";
    }

    @GetMapping("/profile")
    public String otherProfile(HttpSession session,
                               Model model,
                               RedirectAttributes redirectAttributes) {
        Users currentUser = requireAdmin(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập bằng tài khoản quản trị.");
            return "redirect:/login";
        }

        model.addAttribute("currentUser", currentUser);
        return "common/OtherProfile";
    }

    private Users requireAdmin(HttpSession session) {
        Object sessionUser = session.getAttribute("loggedInUser");
        if (!(sessionUser instanceof Users currentUser)) {
            return null;
        }
        if (currentUser.getRole() != Role.ADMIN) {
            return null;
        }
        return currentUser;
    }

    private String buildFullName(String firstName, String lastName) {
        return (safeTrim(lastName) + " " + safeTrim(firstName)).trim();
    }

    private String formatManagerType(ManagerType managerType) {
        if (managerType == null) {
            return "không xác định";
        }

        return switch (managerType) {
            case CUSTOMER_MANAGER -> "khách hàng";
            case HR_MANAGER -> "nhân sự";
            case ROOM_PRICING_MANAGER -> "phòng và giá";
            case SERVICE_MANAGER -> "dịch vụ";
        };
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isValidEmail(String email) {
        if (email == null || email.length() > 50) {
            return false;
        }
        return email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");
    }

    private boolean isValidDateOfBirth(LocalDate dob) {
        return dob != null && !dob.isAfter(LocalDate.now()) && dob.getYear() >= 1900;
    }

    private String generateId(String prefix, int totalLength) {
        String uuidPart = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        int remainingLength = Math.max(totalLength - prefix.length(), 1);
        if (uuidPart.length() > remainingLength) {
            uuidPart = uuidPart.substring(0, remainingLength);
        }
        return prefix + uuidPart;
    }

    private String generateRandomPassword(int length) {
        SecureRandom random = new SecureRandom();
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        return builder.toString();
    }

    private AccountStatus getEffectiveLatestStatus(String userId) {
        List<AccountStatus> statuses = accountStatusRepository.findAll()
                .stream()
                .filter(status -> status.getAccount() != null
                        && status.getAccount().getId() != null
                        && status.getAccount().getId().equals(userId))
                .sorted(Comparator.comparing(AccountStatus::getStartTime))
                .toList();

        if (statuses.isEmpty()) {
            return null;
        }

        AccountStatus latestStatus = statuses.get(statuses.size() - 1);
        if (latestStatus.getName() == AccountState.LOCKED
                && latestStatus.getEndTime() != null
                && latestStatus.getEndTime().isBefore(LocalDateTime.now())) {
            return null;
        }

        return latestStatus;
    }

    private String buildLockReason(String reason, Integer durationDays) {
        String trimmed = safeTrim(reason);
        String suffix = durationDays != null && durationDays == -1
                ? "Khóa vô thời hạn"
                : "Khóa " + durationDays + " ngày";

        if (trimmed.isBlank()) {
            return suffix;
        }

        return trimmed + " | " + suffix;
    }

    private void closeCurrentOpenStatus(Account account) {
        if (account == null || account.getId() == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        accountStatusRepository.findAll()
                .stream()
                .filter(status -> status.getAccount() != null
                        && status.getAccount().getId() != null
                        && status.getAccount().getId().equals(account.getId()))
                .filter(status -> status.getEndTime() == null
                        || status.getEndTime().isAfter(now))
                .forEach(status -> {
                    status.setEndTime(now);
                    accountStatusRepository.save(status);
                });
    }
}