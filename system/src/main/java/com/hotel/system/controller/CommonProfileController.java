package com.hotel.system.controller;

import com.hotel.system.entity.Account;
import com.hotel.system.entity.Users;
import com.hotel.system.entity.enums.Gender;
import com.hotel.system.entity.enums.Role;
import com.hotel.system.repository.AccountRepository;
import com.hotel.system.repository.UsersRepository;
import com.hotel.system.repository.MediaStorageDirectory;
import com.hotel.system.service.MediaStorageService;
import com.hotel.system.util.StoredMedia;
import com.hotel.system.util.PasswordUtils;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

@Controller
public class CommonProfileController {

    private final UsersRepository usersRepository;
    private final AccountRepository accountRepository;
    private final MediaStorageService mediaStorageService;

    public CommonProfileController(UsersRepository usersRepository,
                                   AccountRepository accountRepository,
                                   MediaStorageService mediaStorageService) {
        this.usersRepository = usersRepository;
        this.accountRepository = accountRepository;
        this.mediaStorageService = mediaStorageService;
    }

    @PostMapping("/profile/update")
    public String updateProfile(@RequestParam String lastName,
                                @RequestParam String firstName,
                                @RequestParam String phoneNumber,
                                @RequestParam String pid,
                                @RequestParam String nationality,
                                @RequestParam String sex,
                                @RequestParam
                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateOfBirth,
                                @RequestParam(value = "avatarFile", required = false) MultipartFile avatarFile,
                                HttpSession session,
                                RedirectAttributes redirectAttributes) {

        Users currentUser = getLoggedInInternalUser(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập để cập nhật hồ sơ.");
            return "redirect:/login";
        }

        String profileRedirect = getProfileRedirectPath(currentUser);

        Optional<Users> userOpt = usersRepository.findById(currentUser.getId());
        if (userOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy người dùng.");
            return "redirect:" + profileRedirect;
        }

        String trimmedLastName = safeTrim(lastName);
        String trimmedFirstName = safeTrim(firstName);
        String trimmedPhoneNumber = safeTrim(phoneNumber);
        String trimmedPid = safeTrim(pid);
        String trimmedNationality = safeTrim(nationality);

        if (trimmedLastName.isBlank() || trimmedFirstName.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Họ tên không được để trống.");
            return "redirect:" + profileRedirect;
        }

        if (!trimmedFirstName.matches("^[\\p{L}\\s]{1,30}$") || !trimmedLastName.matches("^[\\p{L}\\s]{1,30}$")) {
            redirectAttributes.addFlashAttribute("error", "Họ tên chỉ được chứa chữ cái và tối đa 30 ký tự.");
            return "redirect:" + profileRedirect;
        }

        if (!trimmedPhoneNumber.matches("^\\d{9,11}$")) {
            redirectAttributes.addFlashAttribute("error", "Số điện thoại phải từ 9 đến 11 chữ số.");
            return "redirect:" + profileRedirect;
        }

        if (trimmedPid.isBlank() || trimmedPid.length() < 9 || trimmedPid.length() > 20) {
            redirectAttributes.addFlashAttribute("error", "CCCD/CMND/Hộ chiếu phải từ 9 đến 20 ký tự.");
            return "redirect:" + profileRedirect;
        }

        if (trimmedNationality.isBlank() || trimmedNationality.length() > 100) {
            redirectAttributes.addFlashAttribute("error", "Quốc tịch không hợp lệ.");
            return "redirect:" + profileRedirect;
        }

        if (!isValidDateOfBirth(dateOfBirth)) {
            redirectAttributes.addFlashAttribute("error", "Ngày sinh phải từ năm 1900 đến hiện tại.");
            return "redirect:" + profileRedirect;
        }

        Gender gender;
        try {
            gender = Gender.valueOf(safeTrim(sex).toUpperCase());
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("error", "Giới tính không hợp lệ.");
            return "redirect:" + profileRedirect;
        }

        Optional<Users> existingPid = usersRepository.findByPid(trimmedPid);
        if (existingPid.isPresent() && !existingPid.get().getId().equals(currentUser.getId())) {
            redirectAttributes.addFlashAttribute("error", "CCCD/CMND/Hộ chiếu đã được dùng bởi tài khoản khác.");
            return "redirect:" + profileRedirect;
        }

        Users user = userOpt.get();
        user.setLastName(trimmedLastName);
        user.setFirstName(trimmedFirstName);
        user.setPhoneNumber(trimmedPhoneNumber);
        user.setPid(trimmedPid);
        user.setNationality(trimmedNationality);
        user.setSex(gender);
        user.setDateOfBirth(dateOfBirth);

        if (avatarFile != null && !avatarFile.isEmpty()) {
            try {
                String previousAvatar = user.getAvatar();
                StoredMedia storedMedia = mediaStorageService.storeImage(avatarFile, MediaStorageDirectory.AVATARS, user.getId());
                user.setAvatar(storedMedia.publicPath());
                if (previousAvatar != null && !previousAvatar.equals(storedMedia.publicPath())) {
                    mediaStorageService.deleteByPublicPath(previousAvatar);
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                redirectAttributes.addFlashAttribute("error", e.getMessage());
                return "redirect:" + profileRedirect;
            }
        }

        user.setUpdateDate(LocalDateTime.now());
        usersRepository.save(user);
        session.setAttribute("loggedInUser", user);

        redirectAttributes.addFlashAttribute("message", "Cập nhật hồ sơ thành công.");
        return "redirect:" + profileRedirect;
    }

    @PostMapping("/profile/change-password")
    public String changePassword(@RequestParam String oldPass,
                                 @RequestParam String newPass,
                                 @RequestParam String confirmPass,
                                 HttpSession session,
                                 RedirectAttributes redirectAttributes) {

        Users currentUser = getLoggedInInternalUser(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập để đổi mật khẩu.");
            return "redirect:/login";
        }

        String profileRedirect = getProfileRedirectPath(currentUser);

        Optional<Account> accountOpt = accountRepository.findById(currentUser.getId());
        if (accountOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy tài khoản.");
            return "redirect:" + profileRedirect;
        }

        Account account = accountOpt.get();

        if (!PasswordUtils.matches(oldPass, account.getPassword())) {
            redirectAttributes.addFlashAttribute("error", "Mật khẩu hiện tại không đúng.");
            return "redirect:" + profileRedirect;
        }

        if (newPass == null || newPass.length() < 8 || newPass.length() > 255) {
            redirectAttributes.addFlashAttribute("error", "Mật khẩu mới phải từ 8 đến 255 ký tự.");
            return "redirect:" + profileRedirect;
        }

        if (!newPass.equals(confirmPass)) {
            redirectAttributes.addFlashAttribute("error", "Xác nhận mật khẩu mới không khớp.");
            return "redirect:" + profileRedirect;
        }

        if (PasswordUtils.matches(newPass, account.getPassword())) {
            redirectAttributes.addFlashAttribute("error", "Mật khẩu mới không được trùng với mật khẩu hiện tại.");
            return "redirect:" + profileRedirect;
        }

        account.setPassword(PasswordUtils.hashPassword(newPass));
        accountRepository.save(account);

        redirectAttributes.addFlashAttribute("message", "Đổi mật khẩu thành công.");
        return "redirect:" + profileRedirect;
    }

    private Users getLoggedInInternalUser(HttpSession session) {
        Object userObj = session.getAttribute("loggedInUser");
        if (!(userObj instanceof Users user)) {
            return null;
        }

        if (user.getRole() == null || user.getRole() == Role.CUSTOMER) {
            return null;
        }

        return user;
    }

    private String getProfileRedirectPath(Users user) {
        if (user == null || user.getRole() == null) {
            return "/login";
        }

        return switch (user.getRole()) {
            case ADMIN -> "/admin/profile";
            case MANAGER -> "/manager/profile";
            case STAFF -> "/staff/profile";
            default -> "/login";
        };
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

@ControllerAdvice
class CommonProfileExceptionAdvice {

    @ExceptionHandler({MaxUploadSizeExceededException.class, MultipartException.class})
    public String handleUploadSizeException(HttpSession session, org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("error", "Ảnh đại diện không được vượt quá 2MB.");

        Object userObj = session.getAttribute("loggedInUser");
        if (userObj instanceof Users user) {
            if (user.getRole() == Role.CUSTOMER) {
                return "redirect:/customer/profile";
            }
            return switch (user.getRole()) {
                case ADMIN -> "redirect:/admin/profile";
                case MANAGER -> "redirect:/manager/profile";
                case STAFF -> "redirect:/staff/profile";
                default -> "redirect:/login";
            };
        }

        return "redirect:/login";
    }
}

@ControllerAdvice
class CommonProfileModelAdvice {

    @ModelAttribute("currentUser")
    public Users currentUser(HttpSession session) {
        Object userObj = session.getAttribute("loggedInUser");
        if (userObj instanceof Users user) {
            return user;
        }
        return null;
    }

    @ModelAttribute
    public void exposeCurrentUserAlias(Model model, HttpSession session) {
        Object userObj = session.getAttribute("loggedInUser");
        if (userObj instanceof Users user) {
            model.addAttribute("user", user);
        }
    }
}