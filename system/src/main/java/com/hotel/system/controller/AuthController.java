package com.hotel.system.controller;

import com.hotel.system.entity.Account;
import com.hotel.system.entity.AccountStatus;
import com.hotel.system.entity.Customer;
import com.hotel.system.entity.TierCustomer;
import com.hotel.system.entity.TierHistory;
import com.hotel.system.entity.Users;
import com.hotel.system.entity.enums.AccountState;
import com.hotel.system.entity.enums.Gender;
import com.hotel.system.entity.enums.Role;
import com.hotel.system.repository.AccountRepository;
import com.hotel.system.repository.AccountStatusRepository;
import com.hotel.system.repository.CustomerRepository;
import com.hotel.system.repository.TierCustomerRepository;
import com.hotel.system.repository.TierHistoryRepository;
import com.hotel.system.repository.UsersRepository;
import com.hotel.system.service.EmailService;
import com.hotel.system.util.PasswordUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Controller
public class AuthController {

    private static final String PENDING_REGISTER_FIRST_NAME = "pendingRegisterFirstName";
    private static final String PENDING_REGISTER_LAST_NAME = "pendingRegisterLastName";
    private static final String PENDING_REGISTER_EMAIL = "pendingRegisterEmail";
    private static final String PENDING_REGISTER_PASSWORD = "pendingRegisterPassword";
    private static final String PENDING_REGISTER_PID = "pendingRegisterPid";
    private static final String PENDING_REGISTER_PHONE = "pendingRegisterPhone";
    private static final String PENDING_REGISTER_NATIONALITY = "pendingRegisterNationality";
    private static final String PENDING_REGISTER_SEX = "pendingRegisterSex";
    private static final String PENDING_REGISTER_DOB = "pendingRegisterDob";
    private static final String PENDING_REGISTER_OTP = "pendingRegisterOtp";
    private static final String PENDING_REGISTER_OTP_EXPIRE_AT = "pendingRegisterOtpExpireAt";
    private static final String PENDING_REGISTER_OTP_ATTEMPTS = "pendingRegisterOtpAttempts";
    private static final int REGISTER_OTP_EXPIRE_MINUTES = 5;
    private static final int REGISTER_OTP_MAX_ATTEMPTS = 5;

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AccountStatusRepository accountStatusRepository;

    @Autowired
    private TierCustomerRepository tierCustomerRepository;

    @Autowired
    private TierHistoryRepository tierHistoryRepository;

    @Autowired
    private EmailService emailService;

    @GetMapping("/login")
    public String loginPage(HttpSession session, HttpServletResponse response) {
        applyNoCacheHeaders(response);
        Object loggedInUser = session.getAttribute("loggedInUser");
        if (loggedInUser instanceof Users) {
            return "redirect:/home";
        }
        return "common/Login";
    }

    @GetMapping("/register")
    public String registerPage(HttpSession session, Model model, HttpServletResponse response) {
        applyNoCacheHeaders(response);
        Object loggedInUser = session.getAttribute("loggedInUser");
        if (loggedInUser instanceof Users) {
            return "redirect:/home";
        }

        restoreRegisterFormFromSession(session, model);
        return "common/Register";
    }

    @GetMapping("/register/verify")
    public String verifyRegisterPage(HttpSession session, Model model, HttpServletResponse response) {
        applyNoCacheHeaders(response);
        Object loggedInUser = session.getAttribute("loggedInUser");
        if (loggedInUser instanceof Users) {
            return "redirect:/home";
        }

        if (!hasPendingRegistration(session)) {
            return "redirect:/register";
        }

        model.addAttribute("pendingEmail", session.getAttribute(PENDING_REGISTER_EMAIL));
        model.addAttribute("otpExpireMinutes", REGISTER_OTP_EXPIRE_MINUTES);
        return "common/VerifyRegisterOtp";
    }

    @PostMapping("/login")
    @Transactional
    public String login(@RequestParam String email,
                        @RequestParam String password,
                        HttpSession session,
                        HttpServletRequest request,
                        HttpServletResponse response,
                        Model model) {

        applyNoCacheHeaders(response);
        session.removeAttribute("loggedInUser");

        String trimmedEmail = safeTrim(email).toLowerCase();
        Optional<Users> userOpt = usersRepository.findByEmail(trimmedEmail);

        if (userOpt.isPresent()) {
            Users user = userOpt.get();
            Optional<Account> accountOpt = accountRepository.findById(user.getId());

            if (accountOpt.isPresent()) {
                Account account = accountOpt.get();

                if (PasswordUtils.matches(password, account.getPassword())) {
                    Optional<AccountStatus> latestStatusOpt = accountStatusRepository
                            .findTopByAccountIdOrderByStartTimeDesc(account.getUser().getId());

                    if (latestStatusOpt.isPresent() && latestStatusOpt.get().getName() == AccountState.LOCKED) {
                        String lockReason = safeTrim(latestStatusOpt.get().getReason());
                        String errorMessage = "Tài khoản của bạn hiện đang bị khóa.";
                        if (!lockReason.isBlank()) {
                            errorMessage += " Lý do: " + lockReason;
                        }
                        model.addAttribute("error", errorMessage);
                        model.addAttribute("lockedReason", lockReason);
                        model.addAttribute("enteredEmail", trimmedEmail);
                        return "common/Login";
                    }

                    if (PasswordUtils.isLegacyPlainText(account.getPassword())) {
                        account.setPassword(PasswordUtils.hashPassword(password));
                        accountRepository.save(account);
                    }

                    session.invalidate();
                    HttpSession newSession = request.getSession(true);
                    newSession.setAttribute("loggedInUser", user);
                    return "redirect:/home";
                }
            }
        }

        model.addAttribute("error", "Email hoặc mật khẩu không đúng!");
        model.addAttribute("enteredEmail", trimmedEmail);
        return "common/Login";
    }

    @PostMapping("/register")
    public String registerUser(@RequestParam String firstName,
                               @RequestParam String lastName,
                               @RequestParam String email,
                               @RequestParam String password,
                               @RequestParam String confirmPassword,
                               @RequestParam String pid,
                               @RequestParam String phoneNumber,
                               @RequestParam String nationality,
                               @RequestParam String sex,
                               @RequestParam String dateOfBirth,
                               HttpSession session,
                               Model model) {

        try {
            clearPendingRegistration(session);

            String trimmedFirstName = safeTrim(firstName);
            String trimmedLastName = safeTrim(lastName);
            String trimmedEmail = safeTrim(email).toLowerCase();
            String trimmedPid = safeTrim(pid);
            String trimmedPhoneNumber = safeTrim(phoneNumber);
            String trimmedNationality = safeTrim(nationality);

            model.addAttribute("firstName", trimmedFirstName);
            model.addAttribute("lastName", trimmedLastName);
            model.addAttribute("email", trimmedEmail);
            model.addAttribute("pid", trimmedPid);
            model.addAttribute("phoneNumber", trimmedPhoneNumber);
            model.addAttribute("nationality", trimmedNationality);
            model.addAttribute("sex", safeTrim(sex).toUpperCase());
            model.addAttribute("dateOfBirth", safeTrim(dateOfBirth));

            if (trimmedFirstName.isBlank() || trimmedLastName.isBlank()) {
                model.addAttribute("error", "Họ tên không được để trống!");
                return "common/Register";
            }

            if (!trimmedFirstName.matches("^[\\p{L}\\s]{1,30}$") || !trimmedLastName.matches("^[\\p{L}\\s]{1,30}$")) {
                model.addAttribute("error", "Họ tên chỉ được chứa chữ cái và tối đa 30 ký tự!");
                return "common/Register";
            }

            if (trimmedEmail.isBlank() || trimmedEmail.length() > 50
                    || !trimmedEmail.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")) {
                model.addAttribute("error", "Email không hợp lệ!");
                return "common/Register";
            }

            if (!trimmedPhoneNumber.matches("^\\d{9,11}$")) {
                model.addAttribute("error", "Số điện thoại phải từ 9 đến 11 chữ số!");
                return "common/Register";
            }

            if (trimmedPid.isBlank() || trimmedPid.length() < 9 || trimmedPid.length() > 20) {
                model.addAttribute("error", "CCCD/CMND/Hộ chiếu phải từ 9 đến 20 ký tự!");
                return "common/Register";
            }

            if (trimmedNationality.isBlank() || trimmedNationality.length() > 100) {
                model.addAttribute("error", "Quốc tịch không hợp lệ!");
                return "common/Register";
            }

            if (password == null || password.length() < 8 || password.length() > 255) {
                model.addAttribute("error", "Mật khẩu phải từ 8 đến 255 ký tự!");
                return "common/Register";
            }

            if (!password.equals(confirmPassword)) {
                model.addAttribute("error", "Xác nhận mật khẩu không khớp!");
                return "common/Register";
            }

            if (usersRepository.findByEmail(trimmedEmail).isPresent()) {
                model.addAttribute("error", "Email này đã tồn tại!");
                return "common/Register";
            }

            if (usersRepository.findByPid(trimmedPid).isPresent()) {
                model.addAttribute("error", "Số CCCD/CMND/Hộ chiếu này đã được đăng ký!");
                return "common/Register";
            }

            if (usersRepository.findByPhoneNumber(trimmedPhoneNumber).isPresent()) {
                model.addAttribute("error", "Số điện thoại này đã được đăng ký!");
                return "common/Register";
            }

            Gender gender;
            try {
                gender = Gender.valueOf(safeTrim(sex).toUpperCase());
            } catch (Exception ex) {
                model.addAttribute("error", "Giới tính không hợp lệ!");
                return "common/Register";
            }

            LocalDate dob;
            try {
                dob = LocalDate.parse(dateOfBirth);
            } catch (Exception ex) {
                model.addAttribute("error", "Ngày sinh không hợp lệ!");
                return "common/Register";
            }

            if (!isValidDateOfBirth(dob)) {
                model.addAttribute("error", "Ngày sinh phải từ năm 1900 đến hiện tại!");
                return "common/Register";
            }

            List<TierCustomer> tiers = tierCustomerRepository.findAll();
            if (tiers.isEmpty()) {
                model.addAttribute("error", "Hệ thống chưa có hạng khách hàng mặc định.");
                return "common/Register";
            }

            String otpCode = generateOtpCode();
            LocalDateTime expireAt = LocalDateTime.now().plusMinutes(REGISTER_OTP_EXPIRE_MINUTES);

            session.setAttribute(PENDING_REGISTER_FIRST_NAME, trimmedFirstName);
            session.setAttribute(PENDING_REGISTER_LAST_NAME, trimmedLastName);
            session.setAttribute(PENDING_REGISTER_EMAIL, trimmedEmail);
            session.setAttribute(PENDING_REGISTER_PASSWORD, password);
            session.setAttribute(PENDING_REGISTER_PID, trimmedPid);
            session.setAttribute(PENDING_REGISTER_PHONE, trimmedPhoneNumber);
            session.setAttribute(PENDING_REGISTER_NATIONALITY, trimmedNationality);
            session.setAttribute(PENDING_REGISTER_SEX, gender.name());
            session.setAttribute(PENDING_REGISTER_DOB, dob.toString());
            session.setAttribute(PENDING_REGISTER_OTP, otpCode);
            session.setAttribute(PENDING_REGISTER_OTP_EXPIRE_AT, expireAt);
            session.setAttribute(PENDING_REGISTER_OTP_ATTEMPTS, 0);

            emailService.sendRegistrationOtpEmail(
                    trimmedEmail,
                    buildFullName(trimmedLastName, trimmedFirstName),
                    otpCode,
                    REGISTER_OTP_EXPIRE_MINUTES
            );

            model.addAttribute("pendingEmail", trimmedEmail);
            model.addAttribute("otpExpireMinutes", REGISTER_OTP_EXPIRE_MINUTES);
            model.addAttribute("message", "Mã xác thực đã được gửi tới email của bạn.");
            return "common/VerifyRegisterOtp";

        } catch (Exception e) {
            e.printStackTrace();
            model.addAttribute("error", "Không thể gửi mã xác thực. Vui lòng thử lại. Chi tiết: " + e.getMessage());
            return "common/Register";
        }
    }

    @PostMapping("/register/verify")
    @Transactional
    public String verifyRegisterOtp(@RequestParam String otp,
                                    HttpSession session,
                                    Model model) {

        if (!hasPendingRegistration(session)) {
            model.addAttribute("error", "Phiên xác thực đã hết hạn. Vui lòng đăng ký lại.");
            return "common/Register";
        }

        String enteredOtp = safeTrim(otp);
        String storedOtp = (String) session.getAttribute(PENDING_REGISTER_OTP);
        LocalDateTime expireAt = (LocalDateTime) session.getAttribute(PENDING_REGISTER_OTP_EXPIRE_AT);
        Integer attempts = (Integer) session.getAttribute(PENDING_REGISTER_OTP_ATTEMPTS);

        String pendingEmail = (String) session.getAttribute(PENDING_REGISTER_EMAIL);
        model.addAttribute("pendingEmail", pendingEmail);
        model.addAttribute("otpExpireMinutes", REGISTER_OTP_EXPIRE_MINUTES);

        if (enteredOtp.isBlank() || !enteredOtp.matches("^\\d{6}$")) {
            model.addAttribute("error", "Mã OTP phải gồm đúng 6 chữ số.");
            return "common/VerifyRegisterOtp";
        }

        if (expireAt == null || LocalDateTime.now().isAfter(expireAt)) {
            clearPendingRegistration(session);
            model.addAttribute("error", "Mã xác thực đã hết hạn. Vui lòng đăng ký lại hoặc gửi lại mã.");
            return "common/Register";
        }

        int nextAttempts = attempts == null ? 1 : attempts + 1;
        session.setAttribute(PENDING_REGISTER_OTP_ATTEMPTS, nextAttempts);

        if (!enteredOtp.equals(storedOtp)) {
            if (nextAttempts >= REGISTER_OTP_MAX_ATTEMPTS) {
                clearPendingRegistration(session);
                model.addAttribute("error", "Bạn đã nhập sai mã xác thực quá số lần cho phép. Vui lòng đăng ký lại.");
                return "common/Register";
            }

            model.addAttribute("error", "Mã xác thực không đúng. Bạn còn " + (REGISTER_OTP_MAX_ATTEMPTS - nextAttempts) + " lần thử.");
            return "common/VerifyRegisterOtp";
        }

        String firstName = (String) session.getAttribute(PENDING_REGISTER_FIRST_NAME);
        String lastName = (String) session.getAttribute(PENDING_REGISTER_LAST_NAME);
        String email = (String) session.getAttribute(PENDING_REGISTER_EMAIL);
        String rawPassword = (String) session.getAttribute(PENDING_REGISTER_PASSWORD);
        String pid = (String) session.getAttribute(PENDING_REGISTER_PID);
        String phoneNumber = (String) session.getAttribute(PENDING_REGISTER_PHONE);
        String nationality = (String) session.getAttribute(PENDING_REGISTER_NATIONALITY);
        String sex = (String) session.getAttribute(PENDING_REGISTER_SEX);
        String dobText = (String) session.getAttribute(PENDING_REGISTER_DOB);

        if (usersRepository.findByEmail(email).isPresent()) {
            clearPendingRegistration(session);
            model.addAttribute("error", "Email này đã tồn tại. Vui lòng đăng ký lại.");
            return "common/Register";
        }

        if (usersRepository.findByPid(pid).isPresent()) {
            clearPendingRegistration(session);
            model.addAttribute("error", "Số CCCD/CMND/Hộ chiếu này đã được đăng ký. Vui lòng đăng ký lại.");
            return "common/Register";
        }

        if (usersRepository.findByPhoneNumber(phoneNumber).isPresent()) {
            clearPendingRegistration(session);
            model.addAttribute("error", "Số điện thoại này đã được đăng ký. Vui lòng đăng ký lại.");
            return "common/Register";
        }

        Gender gender = Gender.valueOf(sex);
        LocalDate dob = LocalDate.parse(dobText);

        List<TierCustomer> tiers = tierCustomerRepository.findAll();
        if (tiers.isEmpty()) {
            clearPendingRegistration(session);
            model.addAttribute("error", "Hệ thống chưa có hạng khách hàng mặc định.");
            return "common/Register";
        }

        TierCustomer defaultTier = tiers.stream()
                .filter(t -> t.getCondition() != null)
                .min(Comparator.comparing(TierCustomer::getCondition))
                .orElse(tiers.get(0));

        LocalDateTime now = LocalDateTime.now();

        Users newUser = new Users();
        newUser.setId(generateId("USR", 10));
        newUser.setFirstName(firstName);
        newUser.setLastName(lastName);
        newUser.setEmail(email);
        newUser.setPid(pid);
        newUser.setPhoneNumber(phoneNumber);
        newUser.setNationality(nationality);
        newUser.setSex(gender);
        newUser.setDateOfBirth(dob);
        newUser.setRole(Role.CUSTOMER);
        newUser.setCreateDate(now);
        newUser.setUpdateDate(now);
        newUser.setAvatar("default_avatar_customer.jpg");
        usersRepository.saveAndFlush(newUser);

        Users managedUser = usersRepository.findById(newUser.getId())
                .orElseThrow(() -> new RuntimeException("Không thể tải lại người dùng vừa tạo."));

        Account newAccount = new Account();
        newAccount.setUser(managedUser);
        newAccount.setPassword(PasswordUtils.hashPassword(rawPassword));
        accountRepository.saveAndFlush(newAccount);

        Customer newCustomer = new Customer();
        newCustomer.setUser(managedUser);
        customerRepository.saveAndFlush(newCustomer);

        Account managedAccount = accountRepository.findById(managedUser.getId())
                .orElseThrow(() -> new RuntimeException("Không thể tải lại tài khoản vừa tạo."));

        Customer managedCustomer = customerRepository.findById(managedUser.getId())
                .orElseThrow(() -> new RuntimeException("Không thể tải lại khách hàng vừa tạo."));

        AccountStatus accountStatus = new AccountStatus();
        accountStatus.setId(generateId("AST", 10));
        accountStatus.setName(AccountState.ACTIVE);
        accountStatus.setStartTime(now);
        accountStatus.setEndTime(null);
        accountStatus.setReason("Tài khoản được tạo mới sau khi xác thực email");
        accountStatus.setAccount(managedAccount);
        accountStatusRepository.saveAndFlush(accountStatus);

        TierHistory tierHistory = new TierHistory();
        tierHistory.setId(generateId("THI", 10));
        tierHistory.setStartDate(now);
        tierHistory.setEndDate(null);
        tierHistory.setTotalSpending(0.0);
        tierHistory.setReason("Khởi tạo hạng mặc định khi đăng ký");
        tierHistory.setCustomer(managedCustomer);
        tierHistory.setTierCustomer(defaultTier);
        tierHistoryRepository.saveAndFlush(tierHistory);

        clearPendingRegistration(session);
        model.addAttribute("message", "Đăng ký thành công! Vui lòng đăng nhập.");
        model.addAttribute("enteredEmail", email);
        return "common/Login";
    }

    @PostMapping("/register/resend-otp")
    public String resendRegisterOtp(HttpSession session, Model model) {
        if (!hasPendingRegistration(session)) {
            model.addAttribute("error", "Phiên xác thực đã hết hạn. Vui lòng đăng ký lại.");
            return "common/Register";
        }

        String email = (String) session.getAttribute(PENDING_REGISTER_EMAIL);
        String firstName = (String) session.getAttribute(PENDING_REGISTER_FIRST_NAME);
        String lastName = (String) session.getAttribute(PENDING_REGISTER_LAST_NAME);

        String otpCode = generateOtpCode();
        LocalDateTime expireAt = LocalDateTime.now().plusMinutes(REGISTER_OTP_EXPIRE_MINUTES);

        session.setAttribute(PENDING_REGISTER_OTP, otpCode);
        session.setAttribute(PENDING_REGISTER_OTP_EXPIRE_AT, expireAt);
        session.setAttribute(PENDING_REGISTER_OTP_ATTEMPTS, 0);

        try {
            emailService.sendRegistrationOtpEmail(
                    email,
                    buildFullName(lastName, firstName),
                    otpCode,
                    REGISTER_OTP_EXPIRE_MINUTES
            );

            model.addAttribute("message", "Đã gửi lại mã xác thực mới tới email của bạn.");
        } catch (Exception ex) {
            model.addAttribute("error", "Không thể gửi lại mã xác thực. Vui lòng thử lại.");
        }

        model.addAttribute("pendingEmail", email);
        model.addAttribute("otpExpireMinutes", REGISTER_OTP_EXPIRE_MINUTES);
        return "common/VerifyRegisterOtp";
    }

    @PostMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/customer/home";
    }

    private void applyNoCacheHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);
    }

    private void restoreRegisterFormFromSession(HttpSession session, Model model) {
        model.addAttribute("firstName", session.getAttribute(PENDING_REGISTER_FIRST_NAME));
        model.addAttribute("lastName", session.getAttribute(PENDING_REGISTER_LAST_NAME));
        model.addAttribute("email", session.getAttribute(PENDING_REGISTER_EMAIL));
        model.addAttribute("pid", session.getAttribute(PENDING_REGISTER_PID));
        model.addAttribute("phoneNumber", session.getAttribute(PENDING_REGISTER_PHONE));
        model.addAttribute("nationality", session.getAttribute(PENDING_REGISTER_NATIONALITY));
        model.addAttribute("sex", session.getAttribute(PENDING_REGISTER_SEX));
        model.addAttribute("dateOfBirth", session.getAttribute(PENDING_REGISTER_DOB));
    }

    private boolean hasPendingRegistration(HttpSession session) {
        return session.getAttribute(PENDING_REGISTER_EMAIL) != null
                && session.getAttribute(PENDING_REGISTER_PASSWORD) != null
                && session.getAttribute(PENDING_REGISTER_OTP) != null
                && session.getAttribute(PENDING_REGISTER_OTP_EXPIRE_AT) != null;
    }

    private void clearPendingRegistration(HttpSession session) {
        session.removeAttribute(PENDING_REGISTER_FIRST_NAME);
        session.removeAttribute(PENDING_REGISTER_LAST_NAME);
        session.removeAttribute(PENDING_REGISTER_EMAIL);
        session.removeAttribute(PENDING_REGISTER_PASSWORD);
        session.removeAttribute(PENDING_REGISTER_PID);
        session.removeAttribute(PENDING_REGISTER_PHONE);
        session.removeAttribute(PENDING_REGISTER_NATIONALITY);
        session.removeAttribute(PENDING_REGISTER_SEX);
        session.removeAttribute(PENDING_REGISTER_DOB);
        session.removeAttribute(PENDING_REGISTER_OTP);
        session.removeAttribute(PENDING_REGISTER_OTP_EXPIRE_AT);
        session.removeAttribute(PENDING_REGISTER_OTP_ATTEMPTS);
    }

    private String buildFullName(String lastName, String firstName) {
        String fullName = (safeTrim(lastName) + " " + safeTrim(firstName)).trim();
        return fullName.isBlank() ? "quý khách" : fullName;
    }

    private String generateOtpCode() {
        int value = (int) (Math.random() * 900000) + 100000;
        return String.valueOf(value);
    }

    private String generateId(String prefix, int totalLength) {
        int randomLength = totalLength - prefix.length();
        if (randomLength <= 0) {
            throw new IllegalArgumentException("Độ dài totalLength phải lớn hơn prefix length");
        }

        String randomPart = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        return prefix + randomPart.substring(0, randomLength);
    }

    private boolean isValidDateOfBirth(LocalDate dob) {
        if (dob == null) {
            return false;
        }

        LocalDate minDate = LocalDate.of(1900, 1, 1);
        LocalDate today = LocalDate.now();

        return !dob.isBefore(minDate) && !dob.isAfter(today);
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
