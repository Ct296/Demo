package com.hotel.system.controller;

import com.hotel.system.entity.Account;
import com.hotel.system.entity.AccountStatus;
import com.hotel.system.entity.AppliedPeriod;
import com.hotel.system.entity.Bill;
import com.hotel.system.entity.Customer;
import com.hotel.system.entity.Payment;
import com.hotel.system.entity.Rental;
import com.hotel.system.entity.Room;
import com.hotel.system.entity.RoomType;
import com.hotel.system.entity.Service;
import com.hotel.system.entity.ServiceUsage;
import com.hotel.system.entity.Staff;
import com.hotel.system.entity.TierCustomer;
import com.hotel.system.entity.TierHistory;
import com.hotel.system.entity.Users;
import com.hotel.system.entity.enums.AccountState;
import com.hotel.system.entity.enums.BillType;
import com.hotel.system.entity.enums.Gender;
import com.hotel.system.entity.enums.PaymentMethod;
import com.hotel.system.entity.enums.RentalStatus;
import com.hotel.system.entity.enums.Role;
import com.hotel.system.entity.enums.RoomStatus;
import com.hotel.system.entity.enums.ServiceStatus;
import com.hotel.system.repository.AccountRepository;
import com.hotel.system.repository.AccountStatusRepository;
import com.hotel.system.repository.AppliedPeriodRepository;
import com.hotel.system.repository.BillRepository;
import com.hotel.system.repository.CustomerRepository;
import com.hotel.system.repository.PaymentRepository;
import com.hotel.system.repository.RentalRepository;
import com.hotel.system.repository.RoomRepository;
import com.hotel.system.repository.ServiceRepository;
import com.hotel.system.repository.ServiceUsageRepository;
import com.hotel.system.repository.StaffRepository;
import com.hotel.system.repository.TierCustomerRepository;
import com.hotel.system.repository.TierHistoryRepository;
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
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Controller
@RequestMapping("/staff")
public class StaffController {

    private static final Set<RentalStatus> BLOCKING_BOOKING_STATUSES = Set.of(
            RentalStatus.PENDING,
            RentalStatus.CONFIRMED,
            RentalStatus.CHECKED_IN,
            RentalStatus.OVERDUE
    );

    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789@#$%";
    private static final int DEFAULT_PASSWORD_LENGTH = 10;
    private static final long CLEANING_BUFFER_MINUTES = 30;
    private static final long BOOKING_HOLD_MINUTES = 30;
    private static final int MINIMUM_WALK_IN_HOURS = 1;
    private static final int SHORT_STAY_SURCHARGE_THRESHOLD_HOURS = 6;
    private static final double SHORT_STAY_SURCHARGE_PERCENT = 10.0;
    private static final double EARLY_CHECKOUT_THRESHOLD_RATIO = 0.8;
    private static final double EARLY_CHECKOUT_PENALTY_PERCENT = 10.0;

    private final StaffRepository staffRepository;
    private final RentalRepository rentalRepository;
    private final RoomRepository roomRepository;
    private final ServiceRepository serviceRepository;
    private final ServiceUsageRepository serviceUsageRepository;
    private final BillRepository billRepository;
    private final PaymentRepository paymentRepository;
    private final UsersRepository usersRepository;
    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final AccountStatusRepository accountStatusRepository;
    private final TierCustomerRepository tierCustomerRepository;
    private final TierHistoryRepository tierHistoryRepository;
    private final AppliedPeriodRepository appliedPeriodRepository;
    private final EmailService emailService;

    public StaffController(StaffRepository staffRepository,
                           RentalRepository rentalRepository,
                           RoomRepository roomRepository,
                           ServiceRepository serviceRepository,
                           ServiceUsageRepository serviceUsageRepository,
                           BillRepository billRepository,
                           PaymentRepository paymentRepository,
                           UsersRepository usersRepository,
                           CustomerRepository customerRepository,
                           AccountRepository accountRepository,
                           AccountStatusRepository accountStatusRepository,
                           TierCustomerRepository tierCustomerRepository,
                           TierHistoryRepository tierHistoryRepository,
                           AppliedPeriodRepository appliedPeriodRepository,
                           EmailService emailService) {
        this.staffRepository = staffRepository;
        this.rentalRepository = rentalRepository;
        this.roomRepository = roomRepository;
        this.serviceRepository = serviceRepository;
        this.serviceUsageRepository = serviceUsageRepository;
        this.billRepository = billRepository;
        this.paymentRepository = paymentRepository;
        this.usersRepository = usersRepository;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.accountStatusRepository = accountStatusRepository;
        this.tierCustomerRepository = tierCustomerRepository;
        this.tierHistoryRepository = tierHistoryRepository;
        this.appliedPeriodRepository = appliedPeriodRepository;
        this.emailService = emailService;
    }

    @GetMapping("/dashboard")
    public String staffDashboard(@RequestParam(name = "timelineOffsetHours", defaultValue = "0") Integer timelineOffsetHours,
                                 @RequestParam(name = "walkInCheckin", required = false) String walkInCheckin,
                                 @RequestParam(name = "walkInLengthOfStay", defaultValue = "1") Integer walkInLengthOfStay,
                                 HttpSession session,
                                 Model model,
                                 RedirectAttributes redirectAttributes) {
        Users currentUser = getLoggedInStaff(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập bằng tài khoản nhân viên.");
            return "redirect:/login";
        }

        Staff currentStaff = staffRepository.findById(currentUser.getId()).orElse(null);

        expirePendingBookingsWithoutDeposit();
        markCheckedInRentalsAsOverdue();
        autoReleaseCleaningRooms();

        List<Rental> rentals = rentalRepository.findAll().stream()
                .sorted(Comparator.comparing(Rental::getRentDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        List<Rental> pendingBookings = rentals.stream()
                .filter(rental -> Boolean.TRUE.equals(rental.getIsBooking()))
                .filter(rental -> rental.getStatus() == RentalStatus.PENDING)
                .toList();

        List<Rental> confirmedBookings = rentals.stream()
                .filter(rental -> Boolean.TRUE.equals(rental.getIsBooking()))
                .filter(rental -> rental.getStatus() == RentalStatus.CONFIRMED)
                .toList();

        List<Rental> checkedInRentals = rentals.stream()
                .filter(rental -> rental.getStatus() == RentalStatus.CHECKED_IN)
                .toList();

        List<Rental> nearCheckoutRentals = checkedInRentals.stream()
                .filter(rental -> isNearCheckout(rental, 15))
                .toList();

        List<Rental> overdueRentals = rentals.stream()
                .filter(rental -> rental.getStatus() == RentalStatus.OVERDUE)
                .toList();

        List<Rental> checkedOutRentals = rentals.stream()
                .filter(rental -> rental.getStatus() == RentalStatus.CHECKED_OUT)
                .toList();

        List<Rental> todayArrivals = rentals.stream()
                .filter(rental -> rental.getCheckinDate() != null)
                .filter(rental -> rental.getCheckinDate().toLocalDate().isEqual(LocalDate.now()))
                .filter(rental -> rental.getStatus() == RentalStatus.PENDING || rental.getStatus() == RentalStatus.CONFIRMED)
                .toList();

        List<Service> activeServices = serviceRepository.findByStatusOrderByCreateDateDesc(ServiceStatus.ACTIVE);
        List<ServiceUsage> serviceUsages = serviceUsageRepository.findAllByOrderByTimeDesc();
        List<Bill> bills = billRepository.findAllByOrderByCreateDateDesc();
        List<Payment> payments = paymentRepository.findAllByOrderByDateDesc();

        List<Room> allRooms = roomRepository.findAll().stream()
                .sorted(Comparator.comparing(Room::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();

        int safeTimelineOffsetHours = normalizeTimelineOffsetHours(timelineOffsetHours);
        LocalDateTime baseTimelineStart = floorToHalfHour(LocalDateTime.now());
        LocalDateTime timelineStart = baseTimelineStart.plusHours(safeTimelineOffsetHours);
        LocalDateTime timelineEnd = timelineStart.plusHours(24);

        int safeWalkInLengthOfStay = walkInLengthOfStay == null || walkInLengthOfStay < MINIMUM_WALK_IN_HOURS
                ? MINIMUM_WALK_IN_HOURS
                : walkInLengthOfStay;
        LocalDateTime parsedStart = parseDashboardDateTimeOrDefault(walkInCheckin, baseTimelineStart);
        LocalDateTime walkInSearchStart = parsedStart.isBefore(baseTimelineStart) ? baseTimelineStart : parsedStart;
        LocalDateTime walkInSearchEnd = walkInSearchStart.plusHours(safeWalkInLengthOfStay).plusMinutes(CLEANING_BUFFER_MINUTES);

        List<Room> walkInRooms = allRooms.stream()
                .filter(room -> room.getStatus() == RoomStatus.AVAILABLE)
                .filter(room -> !hasBlockingConflict(room.getId(), walkInSearchStart, walkInSearchEnd, null))
                .toList();

        List<TimelineSlotView> roomTimelineSlots = buildRoomTimelineSlots(timelineStart, 48);
        List<RoomTimelineRowView> roomTimelineRows = buildRoomTimelineRows(allRooms, rentals, timelineStart, timelineEnd);

        long availableNowRoomCount = roomTimelineRows.stream().filter(RoomTimelineRowView::isAvailableNow).count();
        long busyNowRoomCount = roomTimelineRows.stream()
                .filter(row -> !row.isAvailableNow() && !row.isMaintenanceRoom())
                .count();
        long maintenanceRoomCount = roomTimelineRows.stream().filter(RoomTimelineRowView::isMaintenanceRoom).count();

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("currentStaff", currentStaff);
        model.addAttribute("rentals", rentals);
        model.addAttribute("pendingBookings", pendingBookings);
        model.addAttribute("confirmedBookings", confirmedBookings);
        model.addAttribute("checkedInRentals", checkedInRentals);
        model.addAttribute("nearCheckoutRentals", nearCheckoutRentals);
        model.addAttribute("overdueRentals", overdueRentals);
        model.addAttribute("checkedOutRentals", checkedOutRentals);
        model.addAttribute("todayArrivals", todayArrivals);
        model.addAttribute("activeServices", activeServices);
        model.addAttribute("serviceUsages", serviceUsages);
        model.addAttribute("bills", bills);
        model.addAttribute("payments", payments);
        model.addAttribute("paymentMethods", PaymentMethod.values());
        model.addAttribute("walkInRooms", walkInRooms);
        model.addAttribute("walkInSearchCheckinValue", formatForDateTimeLocal(walkInSearchStart));
        model.addAttribute("walkInSearchLengthOfStay", safeWalkInLengthOfStay);
        model.addAttribute("walkInSearchRoomCount", walkInRooms.size());
        model.addAttribute("genders", Gender.values());
        model.addAttribute("roomTimelineSlots", roomTimelineSlots);
        model.addAttribute("roomTimelineRows", roomTimelineRows);
        model.addAttribute("timelineStart", timelineStart);
        model.addAttribute("timelineEnd", timelineEnd);
        model.addAttribute("timelineOffsetHours", safeTimelineOffsetHours);
        model.addAttribute("previousTimelineOffsetHours", safeTimelineOffsetHours - 24);
        model.addAttribute("nextTimelineOffsetHours", safeTimelineOffsetHours + 24);
        model.addAttribute("availableNowRoomCount", availableNowRoomCount);
        model.addAttribute("busyNowRoomCount", busyNowRoomCount);
        model.addAttribute("maintenanceRoomCount", maintenanceRoomCount);
        model.addAttribute("pendingBookingCount", pendingBookings.size());
        model.addAttribute("confirmedBookingCount", confirmedBookings.size());
        model.addAttribute("checkedInCount", checkedInRentals.size());
        model.addAttribute("nearCheckoutCount", nearCheckoutRentals.size());
        model.addAttribute("overdueCount", overdueRentals.size());
        model.addAttribute("checkedOutCount", checkedOutRentals.size());

        return "staff/staff";
    }

    @GetMapping("/profile")
    public String staffProfile(HttpSession session,
                               Model model,
                               RedirectAttributes redirectAttributes) {
        Users currentUser = getLoggedInStaff(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập bằng tài khoản nhân viên.");
            return "redirect:/login";
        }

        Staff currentStaff = staffRepository.findById(currentUser.getId()).orElse(null);
        model.addAttribute("currentUser", currentUser);
        model.addAttribute("currentStaff", currentStaff);
        return "common/OtherProfile";
    }

    @PostMapping("/walk-in/create")
    @Transactional
    public String createWalkInRental(@RequestParam String roomId,
                                     @RequestParam Integer guestCount,
                                     @RequestParam Integer lengthOfStay,
                                     @RequestParam(required = false) String existingCustomerPid,
                                     @RequestParam(required = false) String firstName,
                                     @RequestParam(required = false) String lastName,
                                     @RequestParam(required = false) String email,
                                     @RequestParam(required = false) String pid,
                                     @RequestParam(required = false) String phoneNumber,
                                     @RequestParam(required = false) String nationality,
                                     @RequestParam(required = false) String sex,
                                     @RequestParam(required = false) String dateOfBirth,
                                     HttpSession session,
                                     RedirectAttributes redirectAttributes) {

        Users currentUser = getLoggedInStaff(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập để thực hiện chức năng này.");
            return "redirect:/login";
        }

        expirePendingBookingsWithoutDeposit();
        autoReleaseCleaningRooms();

        Room room = roomRepository.findById(roomId).orElse(null);
        if (room == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy phòng.");
            return "redirect:/staff/dashboard";
        }
        if (room.getStatus() != RoomStatus.AVAILABLE) {
            redirectAttributes.addFlashAttribute("error", "Phòng hiện không sẵn sàng để cho thuê.");
            return "redirect:/staff/dashboard";
        }
        if (guestCount == null || guestCount < 1) {
            redirectAttributes.addFlashAttribute("error", "Số khách phải lớn hơn 0.");
            return "redirect:/staff/dashboard";
        }
        if (lengthOfStay == null || lengthOfStay < MINIMUM_WALK_IN_HOURS) {
            redirectAttributes.addFlashAttribute("error", "Thời lượng thuê trực tiếp tối thiểu là " + MINIMUM_WALK_IN_HOURS + " giờ.");
            return "redirect:/staff/dashboard";
        }
        if (room.getRoomType() != null && room.getRoomType().getMaxCustomers() != null
                && guestCount > room.getRoomType().getMaxCustomers()) {
            redirectAttributes.addFlashAttribute("error", "Số khách vượt quá sức chứa của phòng.");
            return "redirect:/staff/dashboard";
        }

        LocalDateTime now = LocalDateTime.now();
        int walkInHours = lengthOfStay;
        LocalDateTime expectedCheckout = now.plusHours(walkInHours);

        if (hasBlockingConflict(room.getId(), now, expectedCheckout.plusMinutes(CLEANING_BUFFER_MINUTES), null)) {
            redirectAttributes.addFlashAttribute("error", "Phòng này đang bị giữ lịch trong khoảng thời gian hiện tại.");
            return "redirect:/staff/dashboard";
        }

        Customer customer;
        String generatedPasswordMessage = "";
        String generatedPasswordError = "";

        String trimmedExistingPid = safeTrim(existingCustomerPid);
        if (!trimmedExistingPid.isBlank()) {
            Users existingUser = usersRepository.findByPid(trimmedExistingPid).orElse(null);
            if (existingUser == null || existingUser.getRole() != Role.CUSTOMER) {
                redirectAttributes.addFlashAttribute("error", "Không tìm thấy khách hàng theo CCCD / CMND / Hộ chiếu đã nhập.");
                return "redirect:/staff/dashboard";
            }
            customer = customerRepository.findById(existingUser.getId()).orElse(null);
            if (customer == null) {
                redirectAttributes.addFlashAttribute("error", "Không tìm thấy hồ sơ khách hàng.");
                return "redirect:/staff/dashboard";
            }
        } else {
            String trimmedFirstName = safeTrim(firstName);
            String trimmedLastName = safeTrim(lastName);
            String trimmedEmail = safeTrim(email).toLowerCase();
            String trimmedPid = safeTrim(pid);
            String trimmedPhone = safeTrim(phoneNumber);
            String trimmedNationality = safeTrim(nationality);

            if (trimmedFirstName.isBlank() || trimmedLastName.isBlank() || trimmedEmail.isBlank()
                    || trimmedPid.isBlank() || trimmedPhone.isBlank() || trimmedNationality.isBlank()
                    || safeTrim(sex).isBlank() || safeTrim(dateOfBirth).isBlank()) {
                redirectAttributes.addFlashAttribute("error", "Nếu không dùng khách hàng cũ, phải nhập đầy đủ thông tin khách hàng mới.");
                return "redirect:/staff/dashboard";
            }
            if (!trimmedFirstName.matches("^[\\p{L}\\s]{1,30}$") || !trimmedLastName.matches("^[\\p{L}\\s]{1,30}$")) {
                redirectAttributes.addFlashAttribute("error", "Họ tên khách hàng không hợp lệ.");
                return "redirect:/staff/dashboard";
            }
            if (!trimmedEmail.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$") || trimmedEmail.length() > 50) {
                redirectAttributes.addFlashAttribute("error", "Email khách hàng không hợp lệ.");
                return "redirect:/staff/dashboard";
            }
            if (usersRepository.findByEmail(trimmedEmail).isPresent()) {
                redirectAttributes.addFlashAttribute("error", "Email khách hàng đã tồn tại, hãy dùng ô khách hàng cũ.");
                return "redirect:/staff/dashboard";
            }
            if (trimmedPid.length() < 9 || trimmedPid.length() > 20) {
                redirectAttributes.addFlashAttribute("error", "CCCD/CMND/Hộ chiếu của khách hàng không hợp lệ.");
                return "redirect:/staff/dashboard";
            }
            if (usersRepository.findByPid(trimmedPid).isPresent()) {
                redirectAttributes.addFlashAttribute("error", "CCCD/CMND/Hộ chiếu đã tồn tại, hãy dùng tài khoản khách hàng cũ.");
                return "redirect:/staff/dashboard";
            }
            if (!trimmedPhone.matches("^\\d{9,11}$")) {
                redirectAttributes.addFlashAttribute("error", "Số điện thoại khách hàng không hợp lệ.");
                return "redirect:/staff/dashboard";
            }

            Gender gender;
            try {
                gender = Gender.valueOf(safeTrim(sex).toUpperCase());
            } catch (Exception ex) {
                redirectAttributes.addFlashAttribute("error", "Giới tính khách hàng không hợp lệ.");
                return "redirect:/staff/dashboard";
            }

            LocalDate dob;
            try {
                dob = LocalDate.parse(dateOfBirth);
            } catch (Exception ex) {
                redirectAttributes.addFlashAttribute("error", "Ngày sinh khách hàng không hợp lệ.");
                return "redirect:/staff/dashboard";
            }

            List<TierCustomer> tiers = tierCustomerRepository.findAll();
            if (tiers.isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "Hệ thống chưa có hạng khách hàng mặc định.");
                return "redirect:/staff/dashboard";
            }

            TierCustomer defaultTier = tiers.stream()
                    .filter(t -> t.getCondition() != null)
                    .min(Comparator.comparing(TierCustomer::getCondition))
                    .orElse(tiers.get(0));

            Users newUser = new Users();
            newUser.setId(generateId("USR", 10));
            newUser.setFirstName(trimmedFirstName);
            newUser.setLastName(trimmedLastName);
            newUser.setEmail(trimmedEmail);
            newUser.setPid(trimmedPid);
            newUser.setPhoneNumber(trimmedPhone);
            newUser.setNationality(trimmedNationality);
            newUser.setSex(gender);
            newUser.setDateOfBirth(dob);
            newUser.setRole(Role.CUSTOMER);
            newUser.setCreateDate(now);
            newUser.setUpdateDate(now);
            newUser.setAvatar("default_customer_avatar.jpg");
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

            Customer newCustomer = new Customer();
            newCustomer.setUser(managedUser);
            customerRepository.saveAndFlush(newCustomer);

            customer = customerRepository.findById(managedUser.getId())
                    .orElseThrow(() -> new RuntimeException("Không thể tải lại khách hàng vừa tạo."));

            AccountStatus accountStatus = new AccountStatus();
            accountStatus.setId(generateId("AST", 10));
            accountStatus.setName(AccountState.ACTIVE);
            accountStatus.setStartTime(now);
            accountStatus.setEndTime(null);
            accountStatus.setReason("Tài khoản khách hàng được tạo tự động từ lễ tân");
            accountStatus.setAccount(managedAccount);
            accountStatusRepository.saveAndFlush(accountStatus);

            TierHistory tierHistory = new TierHistory();
            tierHistory.setId(generateId("THI", 10));
            tierHistory.setStartDate(now);
            tierHistory.setEndDate(null);
            tierHistory.setTotalSpending(0.0);
            tierHistory.setReason("Khởi tạo hạng mặc định khi lễ tân tạo tài khoản");
            tierHistory.setCustomer(customer);
            tierHistory.setTierCustomer(defaultTier);
            tierHistoryRepository.saveAndFlush(tierHistory);

            try {
                emailService.sendAutoCreatedCustomerAccountEmail(
                        trimmedEmail,
                        buildFullName(trimmedFirstName, trimmedLastName),
                        rawPassword
                );
                generatedPasswordMessage = " | Đã gửi email thông tin tài khoản cho khách mới: " + trimmedEmail;
            } catch (Exception ex) {
                generatedPasswordError = "Tài khoản khách mới đã được tạo nhưng gửi email thông tin đăng nhập tới "
                        + trimmedEmail + " thất bại: " + ex.getMessage();
            }
        }

        Rental rental = new Rental();
        rental.setId(generateId("REN", 10));
        rental.setCheckinDate(now);
        rental.setRentDate(now);
        rental.setLengthOfStay(walkInHours);
        rental.setGuestCount(guestCount);
        rental.setRoomUnitPrice(resolveRoomUnitPrice(room, now));
        rental.setIsBooking(false);
        rental.setStatus(RentalStatus.CHECKED_IN);
        rental.setCustomer(customer);
        rental.setRoom(room);
        rentalRepository.save(rental);

        boolean shortStaySurchargeApplied = walkInHours < SHORT_STAY_SURCHARGE_THRESHOLD_HOURS;
        redirectAttributes.addFlashAttribute(
                "message",
                "Đã tạo lượt thuê trực tiếp thành công cho phòng " + room.getName()
                        + " | Thời lượng: " + walkInHours + " giờ"
                        + (shortStaySurchargeApplied
                        ? " | Thuê ngắn hạn dưới " + SHORT_STAY_SURCHARGE_THRESHOLD_HOURS + " giờ, phụ thu "
                        + formatMoney(resolveRoomUnitPrice(rental) * SHORT_STAY_SURCHARGE_PERCENT / 100.0)
                        : "")
                        + generatedPasswordMessage
        );

        if (!generatedPasswordError.isBlank()) {
            redirectAttributes.addFlashAttribute("error", generatedPasswordError);
        }
        return "redirect:/staff/dashboard";
    }

    @PostMapping("/bookings/{rentalId}/confirm")
    @Transactional
    public String confirmBooking(@PathVariable String rentalId,
                                 HttpSession session,
                                 RedirectAttributes redirectAttributes) {
        Users currentUser = getLoggedInStaff(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập để thực hiện chức năng này.");
            return "redirect:/login";
        }

        expirePendingBookingsWithoutDeposit();
        autoReleaseCleaningRooms();

        Rental rental = rentalRepository.findById(rentalId).orElse(null);
        if (rental == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy booking.");
            return "redirect:/staff/dashboard";
        }
        if (!Boolean.TRUE.equals(rental.getIsBooking())) {
            redirectAttributes.addFlashAttribute("error", "Chỉ có thể xác nhận booking đặt trước.");
            return "redirect:/staff/dashboard";
        }
        if (rental.getStatus() != RentalStatus.PENDING) {
            redirectAttributes.addFlashAttribute("error", "Chỉ có thể xác nhận booking đang ở trạng thái chờ.");
            return "redirect:/staff/dashboard";
        }
        if (rental.getRoom() == null || rental.getRoom().getId() == null) {
            redirectAttributes.addFlashAttribute("error", "Booking không có thông tin phòng.");
            return "redirect:/staff/dashboard";
        }
        if (rental.getCheckinDate() == null || rental.getLengthOfStay() == null || rental.getLengthOfStay() <= 0) {
            redirectAttributes.addFlashAttribute("error", "Booking không có khoảng thời gian hợp lệ.");
            return "redirect:/staff/dashboard";
        }

        double requiredDeposit = calculateDepositAmount(rental);
        double paidDeposit = calculateDepositPaid(rental.getId());
        if (requiredDeposit > 0 && paidDeposit < requiredDeposit) {
            redirectAttributes.addFlashAttribute("error", "Chưa thể xác nhận booking. Cần ghi nhận tiền cọc trước: " + formatMoney(requiredDeposit));
            return "redirect:/staff/dashboard";
        }
        if (requiredDeposit > 0 && !hasPaymentForBillType(rental.getId(), BillType.DEPOSIT)) {
            redirectAttributes.addFlashAttribute("error", "Chưa thể xác nhận booking. Cần tạo payment tiền cọc cho booking này trước khi xác nhận.");
            return "redirect:/staff/dashboard";
        }

        LocalDateTime requestedCheckin = rental.getCheckinDate();
        LocalDateTime requestedCheckoutWithBuffer = calculateRentalEndWithBuffer(rental);
        if (hasBlockingConflict(rental.getRoom().getId(), requestedCheckin, requestedCheckoutWithBuffer, rental.getId())) {
            rental.setStatus(RentalStatus.CANCELLED);
            rentalRepository.save(rental);
            redirectAttributes.addFlashAttribute("error", "Booking bị hủy vì phòng đã bị giữ trước trong cùng khoảng thời gian hoặc đang trong thời gian dọn dẹp.");
            return "redirect:/staff/dashboard";
        }

        rental.setStatus(RentalStatus.CONFIRMED);
        rentalRepository.save(rental);
        redirectAttributes.addFlashAttribute("message", "Đã xác nhận booking thành công sau khi ghi nhận thanh toán cọc.");
        return "redirect:/staff/dashboard";
    }

    @PostMapping("/bookings/{rentalId}/deposit")
    @Transactional
    public String collectDeposit(@PathVariable String rentalId,
                                 @RequestParam PaymentMethod paymentMethod,
                                 @RequestParam(required = false) String transactionCode,
                                 HttpSession session,
                                 RedirectAttributes redirectAttributes) {
        Users currentUser = getLoggedInStaff(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập để thực hiện chức năng này.");
            return "redirect:/login";
        }

        Rental rental = rentalRepository.findById(rentalId).orElse(null);
        if (rental == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy booking.");
            return "redirect:/staff/dashboard";
        }
        if (!Boolean.TRUE.equals(rental.getIsBooking())) {
            redirectAttributes.addFlashAttribute("error", "Chỉ có thể thu cọc cho booking đặt trước.");
            return "redirect:/staff/dashboard";
        }
        if (rental.getStatus() != RentalStatus.PENDING && rental.getStatus() != RentalStatus.CONFIRMED) {
            redirectAttributes.addFlashAttribute("error", "Chỉ có thể thu cọc cho booking đang chờ hoặc đã xác nhận.");
            return "redirect:/staff/dashboard";
        }
        if (billRepository.existsByRentalIdAndType(rentalId, BillType.DEPOSIT)) {
            redirectAttributes.addFlashAttribute("error", "Booking này đã được ghi nhận tiền cọc.");
            return "redirect:/staff/dashboard";
        }

        BillingSummary billingSummary = buildBillingSummary(rental);
        double depositAmount = billingSummary.depositAmount();
        if (depositAmount <= 0) {
            redirectAttributes.addFlashAttribute("error", "Loại phòng này không yêu cầu tiền cọc.");
            return "redirect:/staff/dashboard";
        }

        Bill depositBill = new Bill();
        depositBill.setId(generateId("BIL", 10));
        depositBill.setCreateDate(LocalDateTime.now());
        depositBill.setTotalAmount(depositAmount);
        depositBill.setType(BillType.DEPOSIT);
        depositBill.setRental(rental);
        billRepository.save(depositBill);

        Payment payment = new Payment();
        payment.setId(generateId("PAY", 10));
        payment.setMethod(paymentMethod);
        payment.setDate(LocalDateTime.now());
        payment.setTransaction(buildTransactionCode(paymentMethod, transactionCode));
        payment.setBill(depositBill);
        paymentRepository.save(payment);

        syncCustomerTierByBills(rental.getCustomer());

        redirectAttributes.addFlashAttribute(
                "message",
                "Đã ghi nhận thanh toán tiền cọc: " + formatMoney(depositAmount)
                        + " | Tổng tiền phòng tạm tính: " + formatMoney(billingSummary.roomAmount())
        );
        return "redirect:/staff/dashboard";
    }

    @PostMapping("/bookings/{rentalId}/cancel")
    @Transactional
    public String cancelBooking(@PathVariable String rentalId,
                                HttpSession session,
                                RedirectAttributes redirectAttributes) {
        Users currentUser = getLoggedInStaff(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập để thực hiện chức năng này.");
            return "redirect:/login";
        }

        Rental rental = rentalRepository.findById(rentalId).orElse(null);
        if (rental == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy booking.");
            return "redirect:/staff/dashboard";
        }
        if (!Boolean.TRUE.equals(rental.getIsBooking())) {
            redirectAttributes.addFlashAttribute("error", "Chỉ có thể hủy booking đặt trước, không thể hủy lượt thuê đang ở.");
            return "redirect:/staff/dashboard";
        }
        if (rental.getStatus() != RentalStatus.PENDING && rental.getStatus() != RentalStatus.CONFIRMED) {
            redirectAttributes.addFlashAttribute("error", "Chỉ có thể hủy booking đang chờ hoặc đã xác nhận.");
            return "redirect:/staff/dashboard";
        }

        boolean wasConfirmed = rental.getStatus() == RentalStatus.CONFIRMED;
        double depositPaid = calculateDepositPaid(rental.getId());
        rental.setStatus(RentalStatus.CANCELLED);
        rentalRepository.save(rental);

        if (wasConfirmed && depositPaid > 0) {
            redirectAttributes.addFlashAttribute("message", "Đã hủy booking. Booking này mất toàn bộ tiền cọc: " + formatMoney(depositPaid));
        } else {
            redirectAttributes.addFlashAttribute("message", "Đã hủy booking thành công.");
        }
        return "redirect:/staff/dashboard";
    }

    @PostMapping("/rentals/{rentalId}/check-in")
    @Transactional
    public String checkIn(@PathVariable String rentalId,
                          HttpSession session,
                          RedirectAttributes redirectAttributes) {
        Users currentUser = getLoggedInStaff(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập để thực hiện chức năng này.");
            return "redirect:/login";
        }

        expirePendingBookingsWithoutDeposit();
        Rental rental = rentalRepository.findById(rentalId).orElse(null);
        if (rental == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy lượt thuê.");
            return "redirect:/staff/dashboard";
        }
        if (!Boolean.TRUE.equals(rental.getIsBooking())) {
            redirectAttributes.addFlashAttribute("error", "Chỉ có thể check-in đối với booking đặt trước.");
            return "redirect:/staff/dashboard";
        }
        if (rental.getStatus() != RentalStatus.CONFIRMED) {
            redirectAttributes.addFlashAttribute("error", "Chỉ có thể check-in booking đã xác nhận.");
            return "redirect:/staff/dashboard";
        }
        if (rental.getRoom() == null) {
            redirectAttributes.addFlashAttribute("error", "Không có thông tin phòng.");
            return "redirect:/staff/dashboard";
        }

        autoReleaseCleaningRooms();
        rental = rentalRepository.findById(rentalId).orElse(rental);
        if (rental.getRoom().getStatus() != RoomStatus.AVAILABLE) {
            redirectAttributes.addFlashAttribute("error", "Phòng hiện chưa sẵn sàng để nhận khách.");
            return "redirect:/staff/dashboard";
        }

        double requiredDeposit = calculateDepositAmount(rental);
        double paidDeposit = calculateDepositPaid(rental.getId());
        if (requiredDeposit > 0 && paidDeposit < requiredDeposit) {
            redirectAttributes.addFlashAttribute("error", "Booking này chưa đủ tiền cọc để check-in. Cần thu: " + formatMoney(requiredDeposit - paidDeposit));
            return "redirect:/staff/dashboard";
        }

        rental.setStatus(RentalStatus.CHECKED_IN);
        rentalRepository.save(rental);
        redirectAttributes.addFlashAttribute("message", "Check-in thành công.");
        return "redirect:/staff/dashboard";
    }

    @PostMapping("/rentals/{rentalId}/services/add")
    @Transactional
    public String addServiceToRental(@PathVariable String rentalId,
                                     @RequestParam String serviceId,
                                     @RequestParam Integer quantity,
                                     HttpSession session,
                                     RedirectAttributes redirectAttributes) {
        Users currentUser = getLoggedInStaff(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập để thực hiện chức năng này.");
            return "redirect:/login";
        }

        Rental rental = rentalRepository.findById(rentalId).orElse(null);
        if (rental == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy lượt thuê.");
            return "redirect:/staff/dashboard";
        }
        Service service = serviceRepository.findById(serviceId).orElse(null);
        if (service == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy dịch vụ.");
            return "redirect:/staff/dashboard";
        }
        if (rental.getStatus() != RentalStatus.CHECKED_IN && rental.getStatus() != RentalStatus.OVERDUE) {
            redirectAttributes.addFlashAttribute("error", "Chỉ có thể thêm dịch vụ cho khách đang ở hoặc đã quá hạn nhưng chưa check-out.");
            return "redirect:/staff/dashboard";
        }
        if (service.getStatus() != ServiceStatus.ACTIVE) {
            redirectAttributes.addFlashAttribute("error", "Dịch vụ này hiện không hoạt động.");
            return "redirect:/staff/dashboard";
        }
        if (quantity == null || quantity < 1) {
            redirectAttributes.addFlashAttribute("error", "Số lượng dịch vụ phải lớn hơn 0.");
            return "redirect:/staff/dashboard";
        }

        double serviceUnitPrice = service.getBasePrice() == null ? 0.0 : service.getBasePrice();
        ServiceUsage usage = new ServiceUsage();
        usage.setId(generateId("SVG", 10));
        usage.setCount(quantity);
        usage.setTime(LocalDateTime.now());
        usage.setUnitPrice(serviceUnitPrice);
        usage.setRental(rental);
        usage.setService(service);
        serviceUsageRepository.save(usage);

        redirectAttributes.addFlashAttribute("message", "Đã thêm dịch vụ thành công. Đơn giá áp dụng: " + formatMoney(serviceUnitPrice));
        return "redirect:/staff/dashboard";
    }

    @PostMapping("/rentals/{rentalId}/final-payment")
    @Transactional
    public String collectFinalPayment(@PathVariable String rentalId,
                                      @RequestParam PaymentMethod paymentMethod,
                                      @RequestParam(required = false) String transactionCode,
                                      HttpSession session,
                                      RedirectAttributes redirectAttributes) {
        Users currentUser = getLoggedInStaff(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập để thực hiện chức năng này.");
            return "redirect:/login";
        }

        Staff currentStaff = staffRepository.findById(currentUser.getId()).orElse(null);
        if (currentStaff == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy hồ sơ nhân viên.");
            return "redirect:/login";
        }

        Rental rental = rentalRepository.findById(rentalId).orElse(null);
        if (rental == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy lượt thuê.");
            return "redirect:/staff/dashboard";
        }
        if (rental.getStatus() != RentalStatus.CHECKED_IN && rental.getStatus() != RentalStatus.OVERDUE) {
            redirectAttributes.addFlashAttribute("error", "Chỉ có thể ghi nhận thanh toán cuối cho khách đang ở hoặc đã quá hạn.");
            return "redirect:/staff/dashboard";
        }
        if (hasSettlementBill(rentalId)) {
            redirectAttributes.addFlashAttribute("error", "Lượt thuê này đã được ghi nhận thanh toán cuối.");
            return "redirect:/staff/dashboard";
        }

        BillingSummary billingSummary = buildBillingSummary(rental);
        double roomAmount = billingSummary.roomAmount();
        double serviceAmount = billingSummary.serviceAmount();
        double depositPaid = billingSummary.depositPaid();
        double finalAmount = billingSummary.finalAmount();

        Bill finalBill = new Bill();
        finalBill.setId(generateId("BIL", 10));
        finalBill.setCreateDate(LocalDateTime.now());
        finalBill.setTotalAmount(finalAmount);
        finalBill.setType(billingSummary.billType());
        finalBill.setActualStayHours(billingSummary.actualStayHours());
        finalBill.setActualRoomAmount(roomAmount);
        finalBill.setEarlyCheckoutPenaltyPercent(billingSummary.earlyCheckoutPenaltyPercent());
        finalBill.setRental(rental);
        billRepository.save(finalBill);

        Payment payment = new Payment();
        payment.setId(generateId("PAY", 10));
        payment.setMethod(paymentMethod);
        payment.setDate(LocalDateTime.now());
        payment.setTransaction(buildTransactionCode(paymentMethod, transactionCode));
        payment.setBill(finalBill);
        paymentRepository.save(payment);

        syncCustomerTierByBills(rental.getCustomer());

        redirectAttributes.addFlashAttribute(
                "message",
                "Đã ghi nhận thanh toán cuối. Tiền phòng: " + formatMoney(roomAmount)
                        + " | Dịch vụ: " + formatMoney(serviceAmount)
                        + " | Cọc đã thu: " + formatMoney(depositPaid)
                        + " | Giờ thực tế: " + billingSummary.actualStayHours() + " giờ"
                        + (billingSummary.earlyCheckoutPenaltyPercent() > 0
                        ? " | Phạt trả sớm: " + formatPercent(billingSummary.earlyCheckoutPenaltyPercent())
                        : "")
                        + " | Đã thanh toán: " + formatMoney(finalAmount)
        );
        return "redirect:/staff/dashboard";
    }

    @PostMapping("/rentals/{rentalId}/check-out")
    @Transactional
    public String checkOut(@PathVariable String rentalId,
                           HttpSession session,
                           RedirectAttributes redirectAttributes) {
        Users currentUser = getLoggedInStaff(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập để thực hiện chức năng này.");
            return "redirect:/login";
        }

        Rental rental = rentalRepository.findById(rentalId).orElse(null);
        if (rental == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy lượt thuê.");
            return "redirect:/staff/dashboard";
        }
        if (rental.getStatus() != RentalStatus.CHECKED_IN && rental.getStatus() != RentalStatus.OVERDUE) {
            redirectAttributes.addFlashAttribute("error", "Chỉ có thể check-out khách đang ở hoặc đã quá hạn.");
            return "redirect:/staff/dashboard";
        }
        if (!hasSettlementBill(rentalId)) {
            redirectAttributes.addFlashAttribute("error", "Chưa thể hoàn tất check-out. Cần ghi nhận payment thanh toán cuối trước.");
            return "redirect:/staff/dashboard";
        }
        if (!hasSettlementPayment(rentalId)) {
            redirectAttributes.addFlashAttribute("error", "Chưa thể hoàn tất check-out. Hóa đơn cuối chưa có payment hợp lệ.");
            return "redirect:/staff/dashboard";
        }

        BillingSummary billingSummary = buildBillingSummary(rental);
        double roomAmount = billingSummary.roomAmount();
        double serviceAmount = billingSummary.serviceAmount();
        double depositPaid = billingSummary.depositPaid();
        double finalAmount = calculateFinalPaid(rentalId);

        rental.setStatus(RentalStatus.CHECKED_OUT);
        rentalRepository.save(rental);

        if (rental.getRoom() != null) {
            Room room = rental.getRoom();
            room.setStatus(RoomStatus.CLEANING);
            roomRepository.save(room);
        }

        redirectAttributes.addFlashAttribute(
                "message",
                "Đã hoàn tất check-out. Tiền phòng: " + formatMoney(roomAmount)
                        + " | Dịch vụ: " + formatMoney(serviceAmount)
                        + " | Cọc đã thu: " + formatMoney(depositPaid)
                        + " | Giờ thực tế: " + billingSummary.actualStayHours() + " giờ"
                        + (billingSummary.earlyCheckoutPenaltyPercent() > 0
                        ? " | Phạt trả sớm: " + formatPercent(billingSummary.earlyCheckoutPenaltyPercent())
                        : "")
                        + " | Thanh toán cuối: " + formatMoney(finalAmount)
                        + " | Phòng đã chuyển sang trạng thái đang dọn dẹp."
        );
        return "redirect:/staff/dashboard";
    }

    @PostMapping("/rooms/{roomId}/mark-available")
    @Transactional
    public String markRoomAvailable(@PathVariable String roomId,
                                    HttpSession session,
                                    RedirectAttributes redirectAttributes) {
        Users currentUser = getLoggedInStaff(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập để thực hiện chức năng này.");
            return "redirect:/login";
        }

        Room room = roomRepository.findById(roomId).orElse(null);
        if (room == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy phòng.");
            return "redirect:/staff/dashboard";
        }
        if (room.getStatus() != RoomStatus.CLEANING) {
            redirectAttributes.addFlashAttribute("error", "Chỉ có thể chuyển sang sẵn sàng cho phòng đang ở trạng thái dọn dẹp.");
            return "redirect:/staff/dashboard";
        }

        room.setStatus(RoomStatus.AVAILABLE);
        roomRepository.save(room);
        redirectAttributes.addFlashAttribute("message", "Đã chuyển phòng " + room.getName() + " sang trạng thái sẵn sàng.");
        return "redirect:/staff/dashboard";
    }

    private void syncCustomerTierByBills(Customer customer) {
        if (customer == null || customer.getId() == null) {
            return;
        }

        List<TierCustomer> tiers = tierCustomerRepository.findAllByOrderByConditionAsc();
        if (tiers.isEmpty()) {
            return;
        }

        double totalSpending = resolveCustomerTotalSpending(customer.getId());
        TierCustomer eligibleTier = resolveEligibleTier(tiers, totalSpending);
        if (eligibleTier == null) {
            eligibleTier = tiers.get(0);
        }

        LocalDateTime now = LocalDateTime.now();
        TierHistory currentHistory = findTierHistoryEffectiveAt(customer.getId(), now);

        if (currentHistory == null) {
            TierHistory newHistory = new TierHistory();
            newHistory.setId(generateId("THI", 10));
            newHistory.setStartDate(now);
            newHistory.setEndDate(null);
            newHistory.setTotalSpending(totalSpending);
            newHistory.setReason(buildTierReason(eligibleTier, totalSpending, false));
            newHistory.setCustomer(customer);
            newHistory.setTierCustomer(eligibleTier);
            tierHistoryRepository.save(newHistory);
            return;
        }

        currentHistory.setTotalSpending(totalSpending);
        String currentTierId = currentHistory.getTierCustomer() == null ? null : currentHistory.getTierCustomer().getId();
        if (currentTierId != null && currentTierId.equals(eligibleTier.getId())) {
            currentHistory.setReason(buildTierReason(eligibleTier, totalSpending, false));
            tierHistoryRepository.save(currentHistory);
            return;
        }

        currentHistory.setEndDate(now);
        currentHistory.setReason(buildTierReason(currentHistory.getTierCustomer(), totalSpending, true));
        tierHistoryRepository.save(currentHistory);

        TierHistory newHistory = new TierHistory();
        newHistory.setId(generateId("THI", 10));
        newHistory.setStartDate(now);
        newHistory.setEndDate(null);
        newHistory.setTotalSpending(totalSpending);
        newHistory.setReason(buildTierReason(eligibleTier, totalSpending, false));
        newHistory.setCustomer(customer);
        newHistory.setTierCustomer(eligibleTier);
        tierHistoryRepository.save(newHistory);
    }

    private double resolveCustomerTotalSpending(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            return 0.0;
        }
        Double total = billRepository.sumTotalAmountByCustomerId(customerId);
        if (total == null || total < 0) {
            return 0.0;
        }
        return total;
    }

    private TierCustomer resolveEligibleTier(List<TierCustomer> tiers, double totalSpending) {
        TierCustomer eligibleTier = null;
        for (TierCustomer tier : tiers) {
            double condition = tier.getCondition() == null ? 0.0 : tier.getCondition();
            if (totalSpending >= condition) {
                eligibleTier = tier;
            }
        }
        return eligibleTier;
    }

    private TierHistory findTierHistoryEffectiveAt(String customerId, LocalDateTime targetTime) {
        if (customerId == null || customerId.isBlank() || targetTime == null) {
            return null;
        }

        TierHistory currentOpenHistory = tierHistoryRepository
                .findTopByCustomerIdAndStartDateLessThanEqualAndEndDateIsNullOrderByStartDateDesc(customerId, targetTime)
                .orElse(null);

        TierHistory currentClosedHistory = tierHistoryRepository
                .findTopByCustomerIdAndStartDateLessThanEqualAndEndDateGreaterThanOrderByStartDateDesc(customerId, targetTime, targetTime)
                .orElse(null);

        if (currentOpenHistory == null) {
            return currentClosedHistory;
        }
        if (currentClosedHistory == null) {
            return currentOpenHistory;
        }

        LocalDateTime openStart = currentOpenHistory.getStartDate();
        LocalDateTime closedStart = currentClosedHistory.getStartDate();
        if (openStart == null) {
            return currentClosedHistory;
        }
        if (closedStart == null) {
            return currentOpenHistory;
        }
        return closedStart.isAfter(openStart) ? currentClosedHistory : currentOpenHistory;
    }

    private String buildTierReason(TierCustomer tierCustomer, double totalSpending, boolean closedHistory) {
        String tierName = tierCustomer == null || tierCustomer.getName() == null || tierCustomer.getName().isBlank()
                ? "không xác định"
                : tierCustomer.getName().trim();
        String prefix = closedHistory
                ? "Đóng hạng do hệ thống tự rà bill: "
                : "Hệ thống tự rà bill, tổng chi: ";
        String message = closedHistory
                ? prefix + formatMoney(totalSpending)
                : prefix + formatMoney(totalSpending) + " -> " + tierName;
        return message.length() > 100 ? message.substring(0, 100) : message;
    }

    private Users getLoggedInUser(HttpSession session) {
        Object userObj = session.getAttribute("loggedInUser");
        if (userObj instanceof Users user) {
            return user;
        }
        return null;
    }

    private Users getLoggedInStaff(HttpSession session) {
        Users user = getLoggedInUser(session);
        if (user == null || user.getRole() != Role.STAFF) {
            return null;
        }
        return user;
    }

    private String buildFullName(String firstName, String lastName) {
        return (safeTrim(lastName) + " " + safeTrim(firstName)).trim();
    }

    private double calculateRoomAmount(Rental rental) {
        if (rental == null || rental.getRoom() == null || rental.getRoom().getRoomType() == null) {
            return 0.0;
        }
        int stayLength = normalizeStayLength(rental.getLengthOfStay());
        double roomUnitPrice = resolveRoomUnitPrice(rental);
        double roomSubTotal = calculateRoomSubTotal(roomUnitPrice, stayLength);
        return roomSubTotal + calculateShortStaySurcharge(roomSubTotal, stayLength);
    }

    private double resolveRoomUnitPrice(Rental rental) {
        if (rental == null) {
            return 0.0;
        }
        if (rental.getRoomUnitPrice() != null && rental.getRoomUnitPrice() > 0) {
            return rental.getRoomUnitPrice();
        }
        return resolveRoomUnitPrice(rental.getRoom(), rental.getCheckinDate());
    }

    private double resolveRoomUnitPrice(Room room, LocalDateTime effectiveMoment) {
        if (room == null || room.getRoomType() == null) {
            return 0.0;
        }

        RoomType roomType = room.getRoomType();
        double basePrice = roomType.getBasePrice() == null ? 0.0 : roomType.getBasePrice();
        if (effectiveMoment == null || roomType.getId() == null) {
            return basePrice;
        }

        AppliedPeriod matchedPeriod = appliedPeriodRepository.findByRoomTypeIdOrderByStartDateDesc(roomType.getId())
                .stream()
                .filter(period -> period.getStartDate() != null && period.getEndDate() != null)
                .filter(period -> !effectiveMoment.isBefore(period.getStartDate()) && !effectiveMoment.isAfter(period.getEndDate()))
                .findFirst()
                .orElse(null);

        if (matchedPeriod == null || matchedPeriod.getPriceRate() == null || matchedPeriod.getPriceRate().getSurchargeAmount() == null) {
            return basePrice;
        }
        return basePrice + matchedPeriod.getPriceRate().getSurchargeAmount();
    }

    public double calculateDepositAmount(Rental rental) {
        if (rental == null || rental.getRoom() == null) {
            return 0.0;
        }
        double totalRoomAmount = calculateRoomAmount(rental);
        double depositPercent = resolveDepositPercent(rental.getRoom().getRoomType());
        return calculateDepositAmount(totalRoomAmount, depositPercent);
    }

    private BillingSummary buildBillingSummary(Rental rental) {
        if (rental == null || rental.getId() == null) {
            return new BillingSummary(0.0, 0.0, 0.0, 0.0, 0.0, MINIMUM_WALK_IN_HOURS, 0.0, 1.0, BillType.FINAL);
        }

        int plannedStayHours = normalizeStayLength(rental.getLengthOfStay());
        int actualStayHours = resolveActualStayHours(rental);
        double roomUnitPrice = resolveRoomUnitPrice(rental);
        double roomSubTotal = calculateRoomSubTotal(roomUnitPrice, actualStayHours);
        double shortStaySurcharge = calculateShortStaySurcharge(roomSubTotal, actualStayHours);
        double roomAmount = roomSubTotal + shortStaySurcharge;

        double completionRatio = plannedStayHours <= 0 ? 1.0 : (double) actualStayHours / plannedStayHours;
        double earlyCheckoutPenaltyPercent = 0.0;
        BillType billType = BillType.FINAL;

        if (isEarlyCheckout(rental, actualStayHours)) {
            billType = BillType.EARLY_CHECKOUT;
            if (completionRatio < EARLY_CHECKOUT_THRESHOLD_RATIO) {
                earlyCheckoutPenaltyPercent = EARLY_CHECKOUT_PENALTY_PERCENT;
                roomAmount += roomAmount * earlyCheckoutPenaltyPercent / 100.0;
            }
        }

        double serviceAmount = calculateServiceAmount(rental.getId());
        double depositPaid = calculateDepositPaid(rental.getId());
        double finalAmount = roomAmount + serviceAmount - depositPaid;
        if (finalAmount < 0) {
            finalAmount = 0.0;
        }

        double depositAmount = calculateDepositAmount(rental);
        return new BillingSummary(roomAmount, serviceAmount, depositPaid, depositAmount, finalAmount,
                actualStayHours, earlyCheckoutPenaltyPercent, completionRatio, billType);
    }

    private int normalizeStayLength(Integer stayLength) {
        if (stayLength == null || stayLength < MINIMUM_WALK_IN_HOURS) {
            return MINIMUM_WALK_IN_HOURS;
        }
        return stayLength;
    }

    private double calculateRoomSubTotal(double roomUnitPrice, int stayLength) {
        return Math.max(roomUnitPrice, 0.0) * normalizeStayLength(stayLength);
    }

    private double calculateShortStaySurcharge(double roomSubTotal, int stayLength) {
        if (normalizeStayLength(stayLength) < SHORT_STAY_SURCHARGE_THRESHOLD_HOURS) {
            return roomSubTotal * SHORT_STAY_SURCHARGE_PERCENT / 100.0;
        }
        return 0.0;
    }

    private double resolveDepositPercent(RoomType roomType) {
        if (roomType == null || roomType.getDepositPercent() == null) {
            return 0.0;
        }
        double depositPercent = roomType.getDepositPercent();
        if (depositPercent < 0) {
            return 0.0;
        }
        if (depositPercent > 100) {
            return 100.0;
        }
        return depositPercent;
    }

    private double calculateDepositAmount(double totalRoomAmount, double depositPercent) {
        return Math.max(totalRoomAmount, 0.0) * resolveDepositPercentValue(depositPercent) / 100.0;
    }

    private double resolveDepositPercentValue(double depositPercent) {
        if (depositPercent < 0) {
            return 0.0;
        }
        if (depositPercent > 100) {
            return 100.0;
        }
        return depositPercent;
    }

    private record BillingSummary(double roomAmount,
                                  double serviceAmount,
                                  double depositPaid,
                                  double depositAmount,
                                  double finalAmount,
                                  int actualStayHours,
                                  double earlyCheckoutPenaltyPercent,
                                  double completionRatio,
                                  BillType billType) {
    }

    private double calculateServiceAmount(String rentalId) {
        return serviceUsageRepository.findAllByOrderByTimeDesc().stream()
                .filter(item -> item.getRental() != null && item.getRental().getId() != null)
                .filter(item -> item.getRental().getId().equals(rentalId))
                .mapToDouble(item -> {
                    int count = item.getCount() == null ? 0 : item.getCount();
                    double unitPrice = item.getUnitPrice() != null
                            ? item.getUnitPrice()
                            : (item.getService() == null || item.getService().getBasePrice() == null ? 0.0 : item.getService().getBasePrice());
                    return unitPrice * count;
                })
                .sum();
    }

    public double calculateDepositPaid(String rentalId) {
        return billRepository.findByRentalIdOrderByCreateDateDesc(rentalId).stream()
                .filter(item -> item.getType() == BillType.DEPOSIT)
                .mapToDouble(item -> item.getTotalAmount() == null ? 0.0 : item.getTotalAmount())
                .sum();
    }

    public double calculateFinalPaid(String rentalId) {
        return billRepository.findByRentalIdOrderByCreateDateDesc(rentalId).stream()
                .filter(item -> item.getType() == BillType.FINAL || item.getType() == BillType.EARLY_CHECKOUT)
                .mapToDouble(item -> item.getTotalAmount() == null ? 0.0 : item.getTotalAmount())
                .sum();
    }

    public boolean hasPaymentForBillType(String rentalId, BillType billType) {
        return paymentRepository.findByBillRentalIdOrderByDateDesc(rentalId).stream()
                .anyMatch(payment -> payment.getBill() != null && payment.getBill().getType() == billType);
    }

    public boolean hasSettlementBill(String rentalId) {
        return billRepository.existsByRentalIdAndType(rentalId, BillType.FINAL)
                || billRepository.existsByRentalIdAndType(rentalId, BillType.EARLY_CHECKOUT);
    }

    public boolean hasSettlementPayment(String rentalId) {
        return hasPaymentForBillType(rentalId, BillType.FINAL)
                || hasPaymentForBillType(rentalId, BillType.EARLY_CHECKOUT);
    }

    public int resolveActualStayHours(Rental rental) {
        if (rental == null || rental.getCheckinDate() == null) {
            return MINIMUM_WALK_IN_HOURS;
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(rental.getCheckinDate())) {
            return normalizeStayLength(rental.getLengthOfStay());
        }
        long totalMinutes = ChronoUnit.MINUTES.between(rental.getCheckinDate(), now);
        if (totalMinutes <= 0) {
            return MINIMUM_WALK_IN_HOURS;
        }
        long fullHours = totalMinutes / 60;
        long remainingMinutes = totalMinutes % 60;
        int roundedHours = (int) fullHours + (remainingMinutes > 30 ? 1 : 0);
        return Math.max(MINIMUM_WALK_IN_HOURS, roundedHours);
    }

    public boolean isEarlyCheckout(Rental rental, int actualStayHours) {
        return rental != null && actualStayHours < normalizeStayLength(rental.getLengthOfStay());
    }

    public double resolveCompletionRatio(Rental rental) {
        if (rental == null) {
            return 1.0;
        }
        int plannedStayHours = normalizeStayLength(rental.getLengthOfStay());
        return plannedStayHours <= 0 ? 1.0 : (double) resolveActualStayHours(rental) / plannedStayHours;
    }

    public double resolveEarlyCheckoutPenaltyPercent(Rental rental) {
        int actualStayHours = resolveActualStayHours(rental);
        if (!isEarlyCheckout(rental, actualStayHours)) {
            return 0.0;
        }
        return resolveCompletionRatio(rental) < EARLY_CHECKOUT_THRESHOLD_RATIO ? EARLY_CHECKOUT_PENALTY_PERCENT : 0.0;
    }

    public LocalDateTime calculateExpectedCheckout(Rental rental) {
        return calculateRentalEndWithoutBuffer(rental);
    }

    public boolean isNearCheckout(Rental rental, long thresholdMinutes) {
        if (rental == null || rental.getStatus() != RentalStatus.CHECKED_IN) {
            return false;
        }
        LocalDateTime expectedCheckout = calculateExpectedCheckout(rental);
        if (expectedCheckout == null) {
            return false;
        }
        long remainingMinutes = ChronoUnit.MINUTES.between(LocalDateTime.now(), expectedCheckout);
        return remainingMinutes >= 0 && remainingMinutes <= thresholdMinutes;
    }

    public long calculateRemainingMinutes(Rental rental) {
        LocalDateTime expectedCheckout = calculateExpectedCheckout(rental);
        return expectedCheckout == null ? -1 : ChronoUnit.MINUTES.between(LocalDateTime.now(), expectedCheckout);
    }

    public long calculateOverdueMinutes(Rental rental) {
        LocalDateTime expectedCheckout = calculateExpectedCheckout(rental);
        if (expectedCheckout == null) {
            return 0;
        }
        long overdueMinutes = ChronoUnit.MINUTES.between(expectedCheckout, LocalDateTime.now());
        return Math.max(overdueMinutes, 0);
    }

    public LocalDateTime findNextBlockingCheckin(String rentalId) {
        Rental currentRental = rentalRepository.findById(rentalId).orElse(null);
        if (currentRental == null || currentRental.getRoom() == null || currentRental.getRoom().getId() == null) {
            return null;
        }

        return rentalRepository.findByRoomIdAndStatusIn(currentRental.getRoom().getId(), BLOCKING_BOOKING_STATUSES).stream()
                .filter(rental -> rental.getId() != null && !rental.getId().equals(rentalId))
                .filter(rental -> rental.getCheckinDate() != null)
                .filter(rental -> !rental.getCheckinDate().isBefore(LocalDateTime.now()))
                .map(Rental::getCheckinDate)
                .min(LocalDateTime::compareTo)
                .orElse(null);
    }

    private List<TimelineSlotView> buildRoomTimelineSlots(LocalDateTime timelineStart, int slotCount) {
        List<TimelineSlotView> slots = new ArrayList<>();
        if (timelineStart == null || slotCount <= 0) {
            return slots;
        }
        for (int i = 0; i < slotCount; i++) {
            LocalDateTime slotStart = timelineStart.plusMinutes(i * 30L);
            slots.add(new TimelineSlotView(
                    slotStart,
                    slotStart.plusMinutes(30),
                    slotStart.format(DateTimeFormatter.ofPattern("HH:mm"))
            ));
        }
        return slots;
    }

    private List<RoomTimelineRowView> buildRoomTimelineRows(List<Room> rooms,
                                                            List<Rental> rentals,
                                                            LocalDateTime timelineStart,
                                                            LocalDateTime timelineEnd) {
        List<RoomTimelineRowView> rows = new ArrayList<>();
        if (rooms == null || rooms.isEmpty() || timelineStart == null || timelineEnd == null || !timelineEnd.isAfter(timelineStart)) {
            return rows;
        }
        for (Room room : rooms) {
            rows.add(buildRoomTimelineRow(room, rentals, timelineStart, timelineEnd));
        }
        return rows;
    }

    private RoomTimelineRowView buildRoomTimelineRow(Room room,
                                                     List<Rental> rentals,
                                                     LocalDateTime timelineStart,
                                                     LocalDateTime timelineEnd) {
        List<RoomTimelineSegmentView> segments = new ArrayList<>();
        if (room == null) {
            return new RoomTimelineRowView(null, "N/A", "N/A", false, false, segments);
        }

        List<Rental> roomBlockingRentals = rentals == null ? List.of() : rentals.stream()
                .filter(rental -> rental != null && rental.getRoom() != null && room.getId() != null && room.getId().equals(rental.getRoom().getId()))
                .filter(rental -> rental.getStatus() != null && BLOCKING_BOOKING_STATUSES.contains(rental.getStatus()))
                .filter(rental -> rental.getCheckinDate() != null)
                .filter(rental -> calculateRentalEndWithBuffer(rental) != null)
                .filter(rental -> rental.getCheckinDate().isBefore(timelineEnd) && calculateRentalEndWithBuffer(rental).isAfter(timelineStart))
                .sorted(Comparator.comparing(Rental::getCheckinDate))
                .toList();

        LocalDateTime cleaningUntil = resolveCleaningUntil(room);
        LocalDateTime cursor = timelineStart;
        while (cursor.isBefore(timelineEnd)) {
            SegmentDescriptor descriptor = resolveSegmentDescriptor(room, roomBlockingRentals, cursor, timelineEnd, cleaningUntil);
            LocalDateTime nextCursor = descriptor.end() == null || !descriptor.end().isAfter(cursor)
                    ? cursor.plusMinutes(30)
                    : descriptor.end();
            if (nextCursor.isAfter(timelineEnd)) {
                nextCursor = timelineEnd;
            }
            int colspan = (int) Math.max(1, ChronoUnit.MINUTES.between(cursor, nextCursor) / 30);
            segments.add(new RoomTimelineSegmentView(descriptor.label(), descriptor.tooltip(), descriptor.cssClass(), colspan));
            cursor = nextCursor;
        }

        LocalDateTime availableAt = resolveNextAvailableAt(room, roomBlockingRentals, cleaningUntil, timelineStart);
        boolean availableNow = availableAt == null || !availableAt.isAfter(LocalDateTime.now());
        boolean maintenanceRoom = room.getStatus() == RoomStatus.MAINTENANCE;

        return new RoomTimelineRowView(
                room,
                resolveCurrentRoomStateLabel(room, roomBlockingRentals, cleaningUntil),
                formatAvailableAtLabel(availableAt, maintenanceRoom),
                availableNow,
                maintenanceRoom,
                segments
        );
    }

    private SegmentDescriptor resolveSegmentDescriptor(Room room,
                                                       List<Rental> roomBlockingRentals,
                                                       LocalDateTime slotStart,
                                                       LocalDateTime timelineEnd,
                                                       LocalDateTime cleaningUntil) {
        if (room != null && room.getStatus() == RoomStatus.MAINTENANCE) {
            return new SegmentDescriptor("Bảo trì", "Phòng đang bảo trì, tạm thời không thể nhận khách.", "maintenance", timelineEnd);
        }
        if (room != null && room.getStatus() == RoomStatus.CLEANING && cleaningUntil != null && slotStart.isBefore(cleaningUntil)) {
            return new SegmentDescriptor("Dọn dẹp", "Phòng đang trong buffer dọn dẹp tới " + formatTimelineDateTime(cleaningUntil) + ".", "cleaning", cleaningUntil);
        }

        Rental blockingRental = roomBlockingRentals.stream()
                .filter(rental -> rental.getCheckinDate() != null)
                .filter(rental -> {
                    LocalDateTime rentalEndWithBuffer = calculateRentalEndWithBuffer(rental);
                    return rentalEndWithBuffer != null
                            && !slotStart.isBefore(rental.getCheckinDate())
                            && slotStart.isBefore(rentalEndWithBuffer);
                })
                .findFirst()
                .orElse(null);

        if (blockingRental != null) {
            LocalDateTime rentalCheckout = calculateExpectedCheckout(blockingRental);
            LocalDateTime rentalEndWithBuffer = calculateRentalEndWithBuffer(blockingRental);
            LocalDateTime segmentEnd = rentalEndWithBuffer == null ? slotStart.plusMinutes(30) : rentalEndWithBuffer;

            if (rentalCheckout != null && !slotStart.isBefore(rentalCheckout) && slotStart.isBefore(segmentEnd)) {
                return new SegmentDescriptor("Dọn dẹp", buildCleaningTooltip(blockingRental, segmentEnd), "cleaning", segmentEnd);
            }

            return new SegmentDescriptor(
                    buildRentalTimelineLabel(blockingRental),
                    buildRentalTimelineTooltip(blockingRental, rentalCheckout, rentalEndWithBuffer),
                    mapTimelineCssClass(blockingRental.getStatus()),
                    rentalCheckout == null ? segmentEnd : rentalCheckout
            );
        }

        Rental nextRental = roomBlockingRentals.stream()
                .filter(rental -> rental.getCheckinDate() != null && rental.getCheckinDate().isAfter(slotStart))
                .findFirst()
                .orElse(null);

        LocalDateTime availableUntil = nextRental != null ? nextRental.getCheckinDate() : timelineEnd;
        return new SegmentDescriptor(
                "Trống",
                nextRental != null ? "Trống đến " + formatTimelineDateTime(availableUntil) + "." : "Phòng đang trống trong khoảng hiển thị.",
                "available",
                availableUntil
        );
    }

    private LocalDateTime resolveCleaningUntil(Room room) {
        if (room == null || room.getStatus() != RoomStatus.CLEANING || room.getId() == null) {
            return null;
        }
        LocalDateTime latestCheckoutTime = findLatestCheckoutTime(room.getId());
        if (latestCheckoutTime == null) {
            return LocalDateTime.now().plusMinutes(CLEANING_BUFFER_MINUTES);
        }
        return latestCheckoutTime.plusMinutes(CLEANING_BUFFER_MINUTES);
    }

    private LocalDateTime resolveNextAvailableAt(Room room,
                                                 List<Rental> roomBlockingRentals,
                                                 LocalDateTime cleaningUntil,
                                                 LocalDateTime baseTime) {
        if (room == null) {
            return null;
        }
        if (room.getStatus() == RoomStatus.MAINTENANCE) {
            return null;
        }

        LocalDateTime pointer = baseTime == null ? LocalDateTime.now() : baseTime;
        LocalDateTime now = LocalDateTime.now();
        if (pointer.isBefore(now)) {
            pointer = now;
        }
        if (room.getStatus() == RoomStatus.CLEANING && cleaningUntil != null && cleaningUntil.isAfter(pointer)) {
            pointer = cleaningUntil;
        }

        boolean advanced;
        do {
            advanced = false;
            for (Rental rental : roomBlockingRentals) {
                LocalDateTime start = rental.getCheckinDate();
                LocalDateTime end = calculateRentalEndWithBuffer(rental);
                if (start == null || end == null) {
                    continue;
                }
                if (!pointer.isBefore(start) && pointer.isBefore(end)) {
                    pointer = end;
                    advanced = true;
                }
            }
        } while (advanced);

        return pointer;
    }

    private String resolveCurrentRoomStateLabel(Room room,
                                                List<Rental> roomBlockingRentals,
                                                LocalDateTime cleaningUntil) {
        if (room == null) {
            return "N/A";
        }
        if (room.getStatus() == RoomStatus.MAINTENANCE) {
            return "Đang bảo trì";
        }
        if (room.getStatus() == RoomStatus.CLEANING && cleaningUntil != null && cleaningUntil.isAfter(LocalDateTime.now())) {
            return "Đang dọn dẹp";
        }

        Rental activeRental = roomBlockingRentals.stream()
                .filter(rental -> rental.getCheckinDate() != null)
                .filter(rental -> {
                    LocalDateTime end = calculateRentalEndWithBuffer(rental);
                    return end != null
                            && !LocalDateTime.now().isBefore(rental.getCheckinDate())
                            && LocalDateTime.now().isBefore(end);
                })
                .findFirst()
                .orElse(null);

        if (activeRental == null) {
            return "Trống ngay";
        }
        LocalDateTime expectedCheckout = calculateExpectedCheckout(activeRental);
        if (expectedCheckout != null && !LocalDateTime.now().isBefore(expectedCheckout)) {
            return "Đang dọn dẹp";
        }
        return activeRental.getStatus() != null ? activeRental.getStatus().getDisplayName() : "Đang bị chặn";
    }

    private String formatAvailableAtLabel(LocalDateTime availableAt, boolean maintenanceRoom) {
        if (maintenanceRoom) {
            return "Tạm khóa";
        }
        if (availableAt == null) {
            return "Chưa xác định";
        }
        if (!availableAt.isAfter(LocalDateTime.now())) {
            return "Ngay bây giờ";
        }
        return formatTimelineDateTime(availableAt);
    }

    private String buildRentalTimelineLabel(Rental rental) {
        String roomState = rental != null && rental.getStatus() != null ? rental.getStatus().getDisplayName() : "Đang chiếm";
        if (rental == null) {
            return roomState;
        }
        String customerName = resolveCustomerName(rental);
        return "N/A".equals(customerName) ? roomState + " · " + rental.getId() : roomState + " · " + customerName;
    }

    private String buildRentalTimelineTooltip(Rental rental,
                                              LocalDateTime rentalCheckout,
                                              LocalDateTime rentalEndWithBuffer) {
        if (rental == null) {
            return "";
        }
        StringBuilder tooltip = new StringBuilder();
        tooltip.append("Đơn: ").append(rental.getId() == null ? "N/A" : rental.getId());
        tooltip.append(" | Trạng thái: ").append(rental.getStatus() != null ? rental.getStatus().getDisplayName() : "N/A");
        tooltip.append(" | Khách: ").append(resolveCustomerName(rental));
        tooltip.append(" | Từ: ").append(formatTimelineDateTime(rental.getCheckinDate()));
        tooltip.append(" | Đến: ").append(formatTimelineDateTime(rentalCheckout));
        if (rentalEndWithBuffer != null) {
            tooltip.append(" | Chặn tới: ").append(formatTimelineDateTime(rentalEndWithBuffer));
        }
        return tooltip.toString();
    }

    private String buildCleaningTooltip(Rental rental, LocalDateTime cleaningUntil) {
        StringBuilder tooltip = new StringBuilder("Buffer dọn dẹp");
        if (rental != null && rental.getId() != null) {
            tooltip.append(" sau đơn ").append(rental.getId());
        }
        if (cleaningUntil != null) {
            tooltip.append(" tới ").append(formatTimelineDateTime(cleaningUntil));
        }
        return tooltip.toString();
    }

    private String resolveCustomerName(Rental rental) {
        if (rental == null || rental.getCustomer() == null || rental.getCustomer().getUser() == null) {
            return "N/A";
        }
        Users user = rental.getCustomer().getUser();
        String fullName = (safeTrim(user.getLastName()) + " " + safeTrim(user.getFirstName())).trim();
        return fullName.isBlank() ? "N/A" : fullName;
    }

    private String mapTimelineCssClass(RentalStatus status) {
        if (status == null) {
            return "pending";
        }
        return switch (status) {
            case PENDING -> "pending";
            case CONFIRMED -> "confirmed";
            case CHECKED_IN -> "checkedin";
            case OVERDUE -> "overdue";
            default -> "pending";
        };
    }

    private int normalizeTimelineOffsetHours(Integer timelineOffsetHours) {
        if (timelineOffsetHours == null) {
            return 0;
        }
        int remainder = timelineOffsetHours % 24;
        return remainder == 0 ? timelineOffsetHours : timelineOffsetHours - remainder;
    }

    private LocalDateTime parseDashboardDateTimeOrDefault(String value, LocalDateTime defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return LocalDateTime.parse(value.trim());
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private String formatForDateTimeLocal(LocalDateTime value) {
        if (value == null) {
            return "";
        }
        return value.withSecond(0).withNano(0).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
    }

    private LocalDateTime floorToHalfHour(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        int minute = value.getMinute();
        int flooredMinute = minute < 30 ? 0 : 30;
        return value.withMinute(flooredMinute).withSecond(0).withNano(0);
    }

    private String formatTimelineDateTime(LocalDateTime value) {
        return value == null ? "N/A" : value.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"));
    }

    private void markCheckedInRentalsAsOverdue() {
        LocalDateTime now = LocalDateTime.now();
        List<Rental> overdueRentals = rentalRepository.findAll().stream()
                .filter(rental -> rental.getStatus() == RentalStatus.CHECKED_IN)
                .filter(rental -> calculateExpectedCheckout(rental) != null)
                .filter(rental -> now.isAfter(calculateExpectedCheckout(rental)))
                .toList();
        for (Rental rental : overdueRentals) {
            rental.setStatus(RentalStatus.OVERDUE);
        }
        if (!overdueRentals.isEmpty()) {
            rentalRepository.saveAll(overdueRentals);
        }
    }

    private void expirePendingBookingsWithoutDeposit() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiryThreshold = now.minusMinutes(BOOKING_HOLD_MINUTES);
        List<Rental> expiredBookings = rentalRepository.findAll().stream()
                .filter(rental -> Boolean.TRUE.equals(rental.getIsBooking()))
                .filter(rental -> rental.getStatus() == RentalStatus.PENDING)
                .filter(rental -> rental.getRentDate() != null && rental.getRentDate().isBefore(expiryThreshold))
                .filter(rental -> calculateDepositPaid(rental.getId()) <= 0)
                .toList();
        for (Rental rental : expiredBookings) {
            rental.setStatus(RentalStatus.CANCELLED);
        }
        if (!expiredBookings.isEmpty()) {
            rentalRepository.saveAll(expiredBookings);
        }
    }

    private boolean hasBlockingConflict(String roomId,
                                        LocalDateTime requestedStart,
                                        LocalDateTime requestedEndWithBuffer,
                                        String ignoreRentalId) {
        return rentalRepository.findByRoomIdAndStatusIn(roomId, BLOCKING_BOOKING_STATUSES).stream()
                .filter(existing -> ignoreRentalId == null || !existing.getId().equals(ignoreRentalId))
                .filter(existing -> existing.getCheckinDate() != null)
                .filter(existing -> existing.getLengthOfStay() != null && existing.getLengthOfStay() > 0)
                .anyMatch(existing -> {
                    LocalDateTime existingStart = existing.getCheckinDate();
                    LocalDateTime existingEndWithBuffer = calculateRentalEndWithBuffer(existing);
                    return existingEndWithBuffer != null
                            && existingStart.isBefore(requestedEndWithBuffer)
                            && existingEndWithBuffer.isAfter(requestedStart);
                });
    }

    private LocalDateTime calculateRentalEndWithoutBuffer(Rental rental) {
        if (rental == null || rental.getCheckinDate() == null || rental.getLengthOfStay() == null || rental.getLengthOfStay() <= 0) {
            return null;
        }
        return rental.getCheckinDate().plusHours(rental.getLengthOfStay());
    }

    private LocalDateTime calculateRentalEndWithBuffer(Rental rental) {
        LocalDateTime endWithoutBuffer = calculateRentalEndWithoutBuffer(rental);
        return endWithoutBuffer == null ? null : endWithoutBuffer.plusMinutes(CLEANING_BUFFER_MINUTES);
    }

    private void autoReleaseCleaningRooms() {
        LocalDateTime now = LocalDateTime.now();
        List<Room> roomsToRelease = roomRepository.findAll().stream()
                .filter(room -> room.getStatus() == RoomStatus.CLEANING)
                .filter(room -> room.getId() != null)
                .filter(room -> {
                    LocalDateTime latestCheckoutTime = findLatestCheckoutTime(room.getId());
                    return latestCheckoutTime != null && !now.isBefore(latestCheckoutTime.plusMinutes(CLEANING_BUFFER_MINUTES));
                })
                .toList();
        if (roomsToRelease.isEmpty()) {
            return;
        }
        for (Room room : roomsToRelease) {
            room.setStatus(RoomStatus.AVAILABLE);
        }
        roomRepository.saveAll(roomsToRelease);
    }

    private LocalDateTime findLatestCheckoutTime(String roomId) {
        return billRepository.findAllByOrderByCreateDateDesc().stream()
                .filter(bill -> bill.getType() == BillType.FINAL || bill.getType() == BillType.EARLY_CHECKOUT)
                .filter(bill -> bill.getRental() != null)
                .filter(bill -> bill.getRental().getRoom() != null)
                .filter(bill -> roomId.equals(bill.getRental().getRoom().getId()))
                .map(Bill::getCreateDate)
                .filter(time -> time != null)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }

    private String buildTransactionCode(PaymentMethod paymentMethod, String transactionCode) {
        String trimmed = safeTrim(transactionCode);
        if (!trimmed.isBlank()) {
            return trimmed;
        }
        String prefix = paymentMethod == PaymentMethod.CASH ? "CASH" : "BANK";
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    public String formatPercent(double percent) {
        return String.format("%.0f%%", percent);
    }

    private String formatMoney(double amount) {
        long rounded = Math.round(amount);
        return String.format("%,d đ", rounded).replace(',', '.');
    }

    private String generateId(String prefix, int totalLength) {
        int randomLength = totalLength - prefix.length();
        if (randomLength <= 0) {
            throw new IllegalArgumentException("Độ dài totalLength phải lớn hơn prefix length");
        }
        String randomPart = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        return prefix + randomPart.substring(0, randomLength);
    }

    private String generateRandomPassword(int length) {
        SecureRandom secureRandom = new SecureRandom();
        StringBuilder password = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int index = secureRandom.nextInt(PASSWORD_CHARS.length());
            password.append(PASSWORD_CHARS.charAt(index));
        }
        return password.toString();
    }

    private record SegmentDescriptor(String label, String tooltip, String cssClass, LocalDateTime end) {
    }

    public static class TimelineSlotView {
        private final LocalDateTime start;
        private final LocalDateTime end;
        private final String label;

        public TimelineSlotView(LocalDateTime start, LocalDateTime end, String label) {
            this.start = start;
            this.end = end;
            this.label = label;
        }

        public LocalDateTime getStart() {
            return start;
        }

        public LocalDateTime getEnd() {
            return end;
        }

        public String getLabel() {
            return label;
        }
    }

    public static class RoomTimelineSegmentView {
        private final String label;
        private final String tooltip;
        private final String cssClass;
        private final int colspan;

        public RoomTimelineSegmentView(String label, String tooltip, String cssClass, int colspan) {
            this.label = label;
            this.tooltip = tooltip;
            this.cssClass = cssClass;
            this.colspan = colspan;
        }

        public String getLabel() {
            return label;
        }

        public String getTooltip() {
            return tooltip;
        }

        public String getCssClass() {
            return cssClass;
        }

        public int getColspan() {
            return colspan;
        }
    }

    public static class RoomTimelineRowView {
        private final Room room;
        private final String currentStateLabel;
        private final String nextAvailableLabel;
        private final boolean availableNow;
        private final boolean maintenanceRoom;
        private final List<RoomTimelineSegmentView> segments;

        public RoomTimelineRowView(Room room,
                                   String currentStateLabel,
                                   String nextAvailableLabel,
                                   boolean availableNow,
                                   boolean maintenanceRoom,
                                   List<RoomTimelineSegmentView> segments) {
            this.room = room;
            this.currentStateLabel = currentStateLabel;
            this.nextAvailableLabel = nextAvailableLabel;
            this.availableNow = availableNow;
            this.maintenanceRoom = maintenanceRoom;
            this.segments = segments;
        }

        public Room getRoom() {
            return room;
        }

        public String getCurrentStateLabel() {
            return currentStateLabel;
        }

        public String getNextAvailableLabel() {
            return nextAvailableLabel;
        }

        public boolean isAvailableNow() {
            return availableNow;
        }

        public boolean isMaintenanceRoom() {
            return maintenanceRoom;
        }

        public List<RoomTimelineSegmentView> getSegments() {
            return segments;
        }
    }
}
