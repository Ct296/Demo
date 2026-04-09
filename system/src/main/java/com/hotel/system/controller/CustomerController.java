package com.hotel.system.controller;

import com.hotel.system.entity.Account;
import com.hotel.system.entity.AppliedPeriod;
import com.hotel.system.entity.Bill;
import com.hotel.system.entity.Customer;
import com.hotel.system.entity.Payment;
import com.hotel.system.entity.Policy;
import com.hotel.system.entity.Rental;
import com.hotel.system.entity.Review;
import com.hotel.system.entity.Room;
import com.hotel.system.entity.RoomType;
import com.hotel.system.entity.RoomImage;
import com.hotel.system.entity.Service;
import com.hotel.system.entity.TierCustomer;
import com.hotel.system.entity.TierHistory;
import com.hotel.system.entity.Users;
import com.hotel.system.entity.enums.*;
import com.hotel.system.repository.AccountRepository;
import com.hotel.system.repository.AppliedPeriodRepository;
import com.hotel.system.repository.BillRepository;
import com.hotel.system.repository.CustomerRepository;
import com.hotel.system.repository.PaymentRepository;
import com.hotel.system.repository.PolicyRepository;
import com.hotel.system.repository.RentalRepository;
import com.hotel.system.repository.ReviewRepository;
import com.hotel.system.repository.RoomRepository;
import com.hotel.system.repository.RoomTypeRepository;
import com.hotel.system.repository.RoomImageRepository;
import com.hotel.system.repository.ServiceRepository;
import com.hotel.system.repository.TierHistoryRepository;
import com.hotel.system.repository.UsersRepository;
import com.hotel.system.util.PasswordUtils;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.HtmlUtils;
import com.hotel.system.dto.VnpayPaymentResult;
import java.util.Map;

import com.hotel.system.service.VnpayService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/customer")
public class CustomerController {

    private static final Set<RentalStatus> BLOCKING_BOOKING_STATUSES = EnumSet.of(
            RentalStatus.PENDING,
            RentalStatus.CONFIRMED,
            RentalStatus.CHECKED_IN,
            RentalStatus.OVERDUE
    );

    private static final long CLEANING_BUFFER_MINUTES = 30;
    private static final long BOOKING_HOLD_MINUTES = 30;
    private static final int MINIMUM_STAY_HOURS = 1;
    private static final int SHORT_STAY_SURCHARGE_THRESHOLD_HOURS = 6;
    private static final double SHORT_STAY_SURCHARGE_PERCENT = 10.0;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final RoomTypeRepository roomTypeRepository;
    private final RoomRepository roomRepository;
    private final RoomImageRepository roomImageRepository;
    private final PolicyRepository policyRepository;
    private final CustomerRepository customerRepository;
    private final UsersRepository usersRepository;
    private final AccountRepository accountRepository;
    private final RentalRepository rentalRepository;
    private final TierHistoryRepository tierHistoryRepository;
    private final ReviewRepository reviewRepository;
    private final BillRepository billRepository;
    private final PaymentRepository paymentRepository;
    private final ServiceRepository serviceRepository;
    private final AppliedPeriodRepository appliedPeriodRepository;
    private final VnpayService vnpayService;

    private record RoomCardView(Room room, double displayPrice) {}

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    public CustomerController(RoomTypeRepository roomTypeRepository,
                              RoomRepository roomRepository,
                              RoomImageRepository roomImageRepository,
                              PolicyRepository policyRepository,
                              CustomerRepository customerRepository,
                              UsersRepository usersRepository,
                              AccountRepository accountRepository,
                              RentalRepository rentalRepository,
                              TierHistoryRepository tierHistoryRepository,
                              ReviewRepository reviewRepository,
                              BillRepository billRepository,
                              PaymentRepository paymentRepository,
                              ServiceRepository serviceRepository,
                              AppliedPeriodRepository appliedPeriodRepository,
                              VnpayService vnpayService) {
        this.roomTypeRepository = roomTypeRepository;
        this.roomRepository = roomRepository;
        this.roomImageRepository = roomImageRepository;
        this.policyRepository = policyRepository;
        this.customerRepository = customerRepository;
        this.usersRepository = usersRepository;
        this.accountRepository = accountRepository;
        this.rentalRepository = rentalRepository;
        this.tierHistoryRepository = tierHistoryRepository;
        this.reviewRepository = reviewRepository;
        this.billRepository = billRepository;
        this.paymentRepository = paymentRepository;
        this.serviceRepository = serviceRepository;
        this.appliedPeriodRepository = appliedPeriodRepository;
        this.vnpayService = vnpayService;
    }

    @GetMapping("/home")
    public String home(Model model, HttpSession session) {
        Users currentUser = getLoggedInUser(session);

        List<RoomType> roomTypes = roomTypeRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(RoomType::getName))
                .toList();

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("roomTypes", roomTypes);
        return "customer/Home";
    }

    @GetMapping("/rooms")
    public String rooms(@RequestParam(required = false) String typeId,
                        @RequestParam(required = false)
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime checkIn,
                        @RequestParam(required = false)
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime checkOut,
                        Model model,
                        HttpSession session) {

        Users currentUser = getLoggedInUser(session);
        expirePendingBookingsWithoutDeposit();

        LocalDateTime minimumBookingStartTime = getMinimumBookingStartTime();
        LocalDateTime adjustedCheckIn = checkIn != null ? checkIn : minimumBookingStartTime;
        LocalDateTime adjustedCheckOut = checkOut != null ? checkOut : adjustedCheckIn.plusHours(1);

        if (adjustedCheckIn.isBefore(minimumBookingStartTime)) {
            adjustedCheckIn = minimumBookingStartTime;
        }

        if (!adjustedCheckOut.isAfter(adjustedCheckIn)) {
            adjustedCheckOut = adjustedCheckIn.plusHours(1);
        }

        final LocalDateTime searchCheckIn = adjustedCheckIn;
        final LocalDateTime searchCheckOut = adjustedCheckOut;

        List<RoomType> roomTypes = roomTypeRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(RoomType::getName))
                .toList();

        List<Room> rooms = roomRepository.findAll()
                .stream()
                .filter(room -> room.getStatus() == RoomStatus.AVAILABLE)
                .filter(room -> typeId == null || typeId.isBlank()
                        || (room.getRoomType() != null && typeId.equals(room.getRoomType().getId())))
                .filter(room -> isRoomAvailable(room, searchCheckIn, searchCheckOut))
                .sorted(Comparator.comparing(Room::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();

        List<RoomCardView> roomCards = rooms.stream()
                .map(room -> new RoomCardView(room, resolveRoomUnitPrice(room, searchCheckIn)))
                .toList();

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("roomTypes", roomTypes);
        model.addAttribute("rooms", rooms);
        model.addAttribute("roomCards", roomCards);
        model.addAttribute("selectedTypeId", typeId);
        model.addAttribute("checkIn", searchCheckIn);
        model.addAttribute("checkOut", searchCheckOut);

        return "customer/Rooms";
    }

    @GetMapping("/booking")
    public String booking(@RequestParam String roomId,
                          @RequestParam(required = false)
                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime checkIn,
                          @RequestParam(required = false)
                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime checkOut,
                          @RequestParam(required = false, defaultValue = "1") Integer guestCount,
                          @RequestParam(required = false) String note,
                          Model model,
                          RedirectAttributes redirectAttributes,
                          HttpSession session) {

        Users currentUser = getLoggedInCustomer(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập để đặt phòng.");
            return "redirect:/login";
        }

        expirePendingBookingsWithoutDeposit();

        Optional<Room> roomOpt = roomRepository.findById(roomId);
        if (roomOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy phòng.");
            return "redirect:/customer/rooms";
        }

        Room room = roomOpt.get();
        LocalDateTime minimumBookingStartTime = getMinimumBookingStartTime();
        LocalDateTime finalCheckIn = checkIn != null ? checkIn : minimumBookingStartTime;
        LocalDateTime finalCheckOut = checkOut != null ? checkOut : finalCheckIn.plusHours(1);

        if (finalCheckIn.isBefore(minimumBookingStartTime)) {
            finalCheckIn = minimumBookingStartTime;
        }

        if (!finalCheckOut.isAfter(finalCheckIn)) {
            finalCheckOut = finalCheckIn.plusHours(1);
        }

        Integer maxCustomers = room.getRoomType() != null ? room.getRoomType().getMaxCustomers() : null;
        if (guestCount == null || guestCount < 1) {
            guestCount = 1;
        }
        if (maxCustomers != null && guestCount > maxCustomers) {
            guestCount = maxCustomers;
        }

        Rental conflictRental = findBlockingRentalConflict(room, finalCheckIn, finalCheckOut);
        boolean bookingAvailable = conflictRental == null;
        String bookingConflictMessage = null;

        if (!bookingAvailable) {
            bookingConflictMessage = "Phòng đang bị trùng lịch trong khung: " + buildConflictRange(conflictRental)
                    + ". Vui lòng chỉnh lại thời gian rồi kiểm tra lại.";
        }

        int stayHours = calculateStayHours(finalCheckIn, finalCheckOut);
        double roomUnitPrice = resolveRoomUnitPrice(room, finalCheckIn);
        double roomSubTotal = calculateRoomSubTotal(roomUnitPrice, stayHours);
        double shortStaySurchargeAmount = calculateShortStaySurcharge(roomSubTotal, stayHours);
        double totalPrice = roomSubTotal + shortStaySurchargeAmount;
        double depositPercent = resolveDepositPercent(room);
        double depositAmount = calculateDepositAmount(totalPrice, depositPercent);
        double remainingAmount = totalPrice - depositAmount;

        String trimmedNote = safeTrim(note);
        if (trimmedNote.length() > 255) {
            trimmedNote = trimmedNote.substring(0, 255);
        }

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("user", currentUser);
        model.addAttribute("room", room);
        model.addAttribute("checkIn", finalCheckIn);
        model.addAttribute("checkOut", finalCheckOut);
        model.addAttribute("stayHours", stayHours);
        model.addAttribute("roomUnitPrice", roomUnitPrice);
        model.addAttribute("roomSubTotal", roomSubTotal);
        model.addAttribute("shortStaySurchargeAmount", shortStaySurchargeAmount);
        model.addAttribute("totalPrice", totalPrice);
        model.addAttribute("depositPercent", depositPercent);
        model.addAttribute("depositAmount", depositAmount);
        model.addAttribute("remainingAmount", remainingAmount);
        model.addAttribute("guestCount", guestCount);
        model.addAttribute("note", trimmedNote);
        model.addAttribute("bookingAvailable", bookingAvailable);
        model.addAttribute("bookingConflictMessage", bookingConflictMessage);

        return "customer/Booking";
    }

    @PostMapping("/booking/confirm")
    public String confirmBooking(@RequestParam String roomId,
                                 @RequestParam
                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime checkIn,
                                 @RequestParam
                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime checkOut,
                                 @RequestParam(defaultValue = "1") Integer guestCount,
                                 @RequestParam(required = false) String note,
                                 HttpSession session,
                                 RedirectAttributes redirectAttributes) {

        Users currentUser = getLoggedInCustomer(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập để đặt phòng.");
            return "redirect:/login";
        }

        expirePendingBookingsWithoutDeposit();

        Optional<Customer> customerOpt = customerRepository.findById(currentUser.getId());
        Optional<Room> roomOpt = roomRepository.findById(roomId);

        if (customerOpt.isEmpty() || roomOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Dữ liệu đặt phòng không hợp lệ.");
            return "redirect:/customer/rooms";
        }

        LocalDateTime minimumBookingStartTime = getMinimumBookingStartTime();
        if (checkIn.isBefore(minimumBookingStartTime)) {
            redirectAttributes.addFlashAttribute("error", "Giờ nhận phòng phải từ thời điểm hiện tại trở đi.");
            return "redirect:/customer/booking?roomId=" + roomId
                    + "&checkIn=" + formatDateTimeParam(checkIn)
                    + "&checkOut=" + formatDateTimeParam(checkOut)
                    + "&guestCount=" + (guestCount != null ? guestCount : 1);
        }

        if (!checkOut.isAfter(checkIn)) {
            redirectAttributes.addFlashAttribute("error", "Giờ trả phòng phải sau giờ nhận phòng.");
            return "redirect:/customer/booking?roomId=" + roomId
                    + "&checkIn=" + formatDateTimeParam(checkIn)
                    + "&checkOut=" + formatDateTimeParam(checkOut)
                    + "&guestCount=" + (guestCount != null ? guestCount : 1);
        }

        Room room = roomOpt.get();

        Rental conflictRental = findBlockingRentalConflict(room, checkIn, checkOut);
        if (conflictRental != null) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    "Phòng đã được giữ trước. Bị trùng trong khung: " + buildConflictRange(conflictRental)
            );
            return "redirect:/customer/booking?roomId=" + roomId
                    + "&checkIn=" + formatDateTimeParam(checkIn)
                    + "&checkOut=" + formatDateTimeParam(checkOut)
                    + "&guestCount=" + (guestCount != null ? guestCount : 1);
        }

        Integer maxCustomers = room.getRoomType() != null ? room.getRoomType().getMaxCustomers() : null;
        if (guestCount == null || guestCount < 1) {
            guestCount = 1;
        }
        if (maxCustomers != null && guestCount > maxCustomers) {
            redirectAttributes.addFlashAttribute("error", "Số khách vượt quá sức chứa của phòng.");
            return "redirect:/customer/booking?roomId=" + roomId
                    + "&checkIn=" + formatDateTimeParam(checkIn)
                    + "&checkOut=" + formatDateTimeParam(checkOut)
                    + "&guestCount=" + guestCount;
        }

        int stayHours = calculateStayHours(checkIn, checkOut);

        Rental rental = new Rental();
        rental.setId(generateId("REN", 10));
        rental.setCheckinDate(checkIn);
        rental.setRentDate(LocalDateTime.now());
        rental.setLengthOfStay(stayHours);
        rental.setGuestCount(guestCount);
        rental.setRoomUnitPrice(resolveRoomUnitPrice(room, checkIn));

        String trimmedNote = safeTrim(note);
        if (trimmedNote.length() > 255) {
            redirectAttributes.addFlashAttribute("error", "Ghi chú không được vượt quá 255 ký tự.");
            return "redirect:/customer/booking?roomId=" + roomId
                    + "&checkIn=" + formatDateTimeParam(checkIn)
                    + "&checkOut=" + formatDateTimeParam(checkOut)
                    + "&guestCount=" + guestCount;
        }
        rental.setNote(trimmedNote.isBlank() ? null : trimmedNote);

        rental.setIsBooking(true);
        rental.setStatus(RentalStatus.PENDING);
        rental.setCustomer(customerOpt.get());
        rental.setRoom(room);

        rentalRepository.save(rental);

        redirectAttributes.addFlashAttribute(
                "message",
                "Đã tạo booking tạm. Vui lòng chuyển khoản tiền cọc trong 30 phút."
        );
        return "redirect:/customer/booking/payment?rentalId=" + rental.getId();
    }

    @GetMapping("/booking/payment")
    public String bookingPayment(@RequestParam String rentalId,
                                 Model model,
                                 HttpSession session,
                                 RedirectAttributes redirectAttributes) {

        Users currentUser = getLoggedInCustomer(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập để tiếp tục thanh toán.");
            return "redirect:/login";
        }

        expirePendingBookingsWithoutDeposit();

        Rental rental = rentalRepository.findById(rentalId).orElse(null);
        if (rental == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy booking.");
            return "redirect:/customer/profile";
        }

        if (rental.getCustomer() == null || rental.getCustomer().getId() == null
                || !rental.getCustomer().getId().equals(currentUser.getId())) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền xem booking này.");
            return "redirect:/customer/profile";
        }

        if (!Boolean.TRUE.equals(rental.getIsBooking())) {
            redirectAttributes.addFlashAttribute("error", "Đây không phải booking đặt trước.");
            return "redirect:/customer/profile";
        }

        if (rental.getStatus() != RentalStatus.PENDING) {
            redirectAttributes.addFlashAttribute("error", "Booking này không còn ở trạng thái chờ thanh toán.");
            return "redirect:/customer/profile";
        }

        Room room = rental.getRoom();
        if (room == null) {
            redirectAttributes.addFlashAttribute("error", "Booking không có thông tin phòng hợp lệ.");
            return "redirect:/customer/profile";
        }

        int stayHours = rental.getLengthOfStay() == null ? MINIMUM_STAY_HOURS : Math.max(rental.getLengthOfStay(), MINIMUM_STAY_HOURS);
        double roomUnitPrice = rental.getRoomUnitPrice() == null ? 0.0 : rental.getRoomUnitPrice();
        double roomSubTotal = calculateRoomSubTotal(roomUnitPrice, stayHours);
        double shortStaySurchargeAmount = calculateShortStaySurcharge(roomSubTotal, stayHours);
        double totalPrice = roomSubTotal + shortStaySurchargeAmount;
        double depositPercent = resolveDepositPercent(room);
        double depositAmount = calculateDepositAmount(totalPrice, depositPercent);
        double remainingAmount = totalPrice - depositAmount;
        LocalDateTime expiresAt = rental.getRentDate() == null ? null : rental.getRentDate().plusMinutes(BOOKING_HOLD_MINUTES);

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("user", currentUser);
        model.addAttribute("rental", rental);
        model.addAttribute("room", room);
        model.addAttribute("checkIn", rental.getCheckinDate());
        model.addAttribute("checkOut", calculateExpectedCheckoutWithoutBuffer(rental));
        model.addAttribute("stayHours", stayHours);
        model.addAttribute("roomUnitPrice", roomUnitPrice);
        model.addAttribute("roomSubTotal", roomSubTotal);
        model.addAttribute("shortStaySurchargeAmount", shortStaySurchargeAmount);
        model.addAttribute("totalPrice", totalPrice);
        model.addAttribute("depositPercent", depositPercent);
        model.addAttribute("depositAmount", depositAmount);
        model.addAttribute("remainingAmount", remainingAmount);
        model.addAttribute("expiresAt", expiresAt);
        model.addAttribute("vnpayEnabled", vnpayService.isReady());
        return "customer/BookingPayment";
    }


    @PostMapping("/booking/payment/vnpay")
    public String redirectToVnpay(@RequestParam String rentalId,
                                  HttpSession session,
                                  RedirectAttributes redirectAttributes) {

        Users currentUser = getLoggedInCustomer(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập để tiếp tục thanh toán.");
            return "redirect:/login";
        }

        expirePendingBookingsWithoutDeposit();

        Rental rental = rentalRepository.findById(rentalId).orElse(null);
        if (rental == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy booking.");
            return "redirect:/customer/profile";
        }

        if (rental.getCustomer() == null || rental.getCustomer().getId() == null
                || !rental.getCustomer().getId().equals(currentUser.getId())) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền thanh toán booking này.");
            return "redirect:/customer/profile";
        }

        if (!Boolean.TRUE.equals(rental.getIsBooking())) {
            redirectAttributes.addFlashAttribute("error", "Đây không phải booking đặt trước.");
            return "redirect:/customer/profile";
        }

        if (rental.getStatus() != RentalStatus.PENDING) {
            redirectAttributes.addFlashAttribute("error", "Booking này không còn ở trạng thái chờ thanh toán.");
            return "redirect:/customer/profile";
        }

        if (!vnpayService.isReady()) {
            redirectAttributes.addFlashAttribute("error", "Hệ thống VNPay chưa sẵn sàng.");
            return "redirect:/customer/booking/payment?rentalId=" + rentalId;
        }

        Room room = rental.getRoom();
        if (room == null) {
            redirectAttributes.addFlashAttribute("error", "Booking không có thông tin phòng hợp lệ.");
            return "redirect:/customer/profile";
        }

        int stayHours = rental.getLengthOfStay() == null
                ? MINIMUM_STAY_HOURS
                : Math.max(rental.getLengthOfStay(), MINIMUM_STAY_HOURS);

        double roomUnitPrice = rental.getRoomUnitPrice() == null ? 0.0 : rental.getRoomUnitPrice();
        double roomSubTotal = calculateRoomSubTotal(roomUnitPrice, stayHours);
        double shortStaySurchargeAmount = calculateShortStaySurcharge(roomSubTotal, stayHours);
        double totalPrice = roomSubTotal + shortStaySurchargeAmount;
        double depositPercent = resolveDepositPercent(room);
        double depositAmount = calculateDepositAmount(totalPrice, depositPercent);

        try {
            String paymentUrl = vnpayService.buildPaymentUrl(
                    com.hotel.system.dto.VnpayCreatePaymentRequest.builder()
                            .txnRef(rental.getId())
                            .rentalId(rental.getId())
                            .billType(BillType.DEPOSIT)
                            .amount((long) Math.round(depositAmount))
                            .orderInfo("Thanh toan tien coc booking " + rental.getId())
                            .returnUrl("https://ct296.id.vn/customer/booking/payment/vnpay-return")
                            .ipAddress("127.0.0.1")
                            .createdAt(LocalDateTime.now())
                            .expiredAt(rental.getRentDate() == null
                                    ? LocalDateTime.now().plusMinutes(30)
                                    : rental.getRentDate().plusMinutes(30))
                            .build()
            );

            return "redirect:" + paymentUrl;
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("error", "Không thể tạo liên kết thanh toán VNPay: " + ex.getMessage());
            return "redirect:/customer/booking/payment?rentalId=" + rentalId;
        }
    }

    @GetMapping("/booking/payment/vnpay-return")
    public String handleVnpayReturn(@RequestParam Map<String, String> params,
                                    HttpSession session,
                                    RedirectAttributes redirectAttributes) {

        Users currentUser = getLoggedInCustomer(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập để xem kết quả thanh toán.");
            return "redirect:/login";
        }

        VnpayPaymentResult paymentResult = vnpayService.parsePaymentResult(params);
        String rentalId = paymentResult.getTxnRef();

        if (rentalId == null || rentalId.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Không xác định được booking từ kết quả VNPay.");
            return "redirect:/customer/profile";
        }

        Rental rental = rentalRepository.findById(rentalId).orElse(null);
        if (rental == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy booking tương ứng.");
            return "redirect:/customer/profile";
        }

        if (rental.getCustomer() == null || rental.getCustomer().getId() == null
                || !rental.getCustomer().getId().equals(currentUser.getId())) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền xem kết quả thanh toán booking này.");
            return "redirect:/customer/profile";
        }

        if (!paymentResult.isValidSignature()) {
            redirectAttributes.addFlashAttribute("error", "Chữ ký VNPay không hợp lệ.");
            return "redirect:/customer/booking/payment?rentalId=" + rentalId;
        }

        if (!paymentResult.isSuccess()) {
            redirectAttributes.addFlashAttribute("error", "Thanh toán chưa thành công hoặc đã bị hủy.");
            return "redirect:/customer/booking/payment?rentalId=" + rentalId;
        }

        if (billRepository.existsByRentalIdAndType(rentalId, BillType.DEPOSIT)) {
            redirectAttributes.addFlashAttribute("message", "Booking này đã được ghi nhận tiền cọc trước đó.");
            return "redirect:/customer/profile";
        }

        int stayHours = rental.getLengthOfStay() == null ? MINIMUM_STAY_HOURS : Math.max(rental.getLengthOfStay(), MINIMUM_STAY_HOURS);
        double roomUnitPrice = rental.getRoomUnitPrice() == null ? 0.0 : rental.getRoomUnitPrice();
        double roomSubTotal = calculateRoomSubTotal(roomUnitPrice, stayHours);
        double shortStaySurchargeAmount = calculateShortStaySurcharge(roomSubTotal, stayHours);
        double totalPrice = roomSubTotal + shortStaySurchargeAmount;
        double depositPercent = resolveDepositPercent(rental.getRoom());
        double depositAmount = calculateDepositAmount(totalPrice, depositPercent);

        Bill depositBill = new Bill();
        depositBill.setId(generateId("BIL", 10));
        depositBill.setCreateDate(LocalDateTime.now());
        depositBill.setTotalAmount(depositAmount);
        depositBill.setType(BillType.DEPOSIT);
        depositBill.setRental(rental);
        billRepository.save(depositBill);

        Payment payment = new Payment();
        payment.setId(generateId("PAY", 10));
        payment.setMethod(PaymentMethod.BANK);
        payment.setDate(LocalDateTime.now());
        payment.setTransaction(
                paymentResult.getTransactionNo() != null && !paymentResult.getTransactionNo().isBlank()
                        ? paymentResult.getTransactionNo()
                        : "VNPAY-" + rentalId
        );
        payment.setBill(depositBill);
        paymentRepository.save(payment);

        rental.setStatus(RentalStatus.CONFIRMED);
        rentalRepository.save(rental);

        redirectAttributes.addFlashAttribute("message", "Thanh toán cọc thành công. Booking đã được xác nhận.");
        return "redirect:/customer/profile";
    }

    @PostMapping("/rentals/cancel")
    public String cancelOwnBooking(@RequestParam String rentalId,
                                   HttpSession session,
                                   RedirectAttributes redirectAttributes) {

        Users currentUser = getLoggedInCustomer(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập để thực hiện chức năng này.");
            return "redirect:/login";
        }

        Rental rental = rentalRepository.findById(rentalId).orElse(null);
        if (rental == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy booking.");
            return "redirect:/customer/profile";
        }

        if (rental.getCustomer() == null || rental.getCustomer().getId() == null
                || !rental.getCustomer().getId().equals(currentUser.getId())) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền thao tác booking này.");
            return "redirect:/customer/profile";
        }

        if (!Boolean.TRUE.equals(rental.getIsBooking())) {
            redirectAttributes.addFlashAttribute("error", "Chỉ có thể hủy booking đặt trước.");
            return "redirect:/customer/profile";
        }

        if (rental.getStatus() != RentalStatus.PENDING && rental.getStatus() != RentalStatus.CONFIRMED) {
            redirectAttributes.addFlashAttribute("error", "Booking này không còn có thể hủy.");
            return "redirect:/customer/profile";
        }

        if (rental.getCheckinDate() != null && !rental.getCheckinDate().isAfter(LocalDateTime.now())) {
            redirectAttributes.addFlashAttribute("error", "Không thể hủy booking khi đã tới hoặc qua giờ nhận phòng.");
            return "redirect:/customer/profile";
        }

        boolean wasConfirmed = rental.getStatus() == RentalStatus.CONFIRMED;
        double depositPaid = calculateDepositPaid(rental.getId());

        rental.setStatus(RentalStatus.CANCELLED);
        rentalRepository.save(rental);

        if (wasConfirmed && depositPaid > 0) {
            redirectAttributes.addFlashAttribute(
                    "message",
                    "Đã hủy booking. Booking đã xác nhận sẽ mất toàn bộ tiền cọc: " + formatMoney(depositPaid)
            );
        } else {
            redirectAttributes.addFlashAttribute("message", "Đã hủy booking thành công.");
        }

        return "redirect:/customer/profile";
    }

    @GetMapping("/profile")
    public String profile(Model model, HttpSession session, RedirectAttributes redirectAttributes) {
        Users currentUser = getLoggedInCustomer(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập để xem hồ sơ.");
            return "redirect:/login";
        }

        expirePendingBookingsWithoutDeposit();
        markCheckedInRentalsAsOverdueForCustomer(currentUser.getId());

        Customer customerDetail = customerRepository.findById(currentUser.getId()).orElse(null);

        TierHistory currentTierHistory = findTierHistoryEffectiveAt(currentUser.getId(), LocalDateTime.now());
        TierCustomer currentTier = currentTierHistory == null ? null : currentTierHistory.getTierCustomer();

        List<Rental> rentals = rentalRepository.findByCustomerId(currentUser.getId())
                .stream()
                .sorted(Comparator.comparing(Rental::getRentDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        Set<String> rentalIds = rentals.stream()
                .map(Rental::getId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());

        List<Bill> bills = billRepository.findAllByOrderByCreateDateDesc()
                .stream()
                .filter(bill -> bill.getRental() != null && bill.getRental().getId() != null)
                .filter(bill -> rentalIds.contains(bill.getRental().getId()))
                .toList();

        Set<String> billIds = bills.stream()
                .map(Bill::getId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());

        List<Payment> payments = paymentRepository.findAllByOrderByDateDesc()
                .stream()
                .filter(payment -> payment.getBill() != null && payment.getBill().getId() != null)
                .filter(payment -> billIds.contains(payment.getBill().getId()))
                .toList();

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("customerDetail", customerDetail);
        model.addAttribute("currentTier", currentTier);
        model.addAttribute("rentals", rentals);
        model.addAttribute("bills", bills);
        model.addAttribute("payments", payments);

        return "customer/CustomerProfile";
    }

    public LocalDateTime calculateExpectedCheckout(Rental rental) {
        if (rental == null || rental.getCheckinDate() == null || rental.getLengthOfStay() == null || rental.getLengthOfStay() <= 0) {
            return null;
        }
        return rental.getCheckinDate().plusHours(rental.getLengthOfStay());
    }

    private LocalDateTime calculateExpectedCheckoutWithoutBuffer(Rental rental) {
        if (rental == null || rental.getCheckinDate() == null || rental.getLengthOfStay() == null || rental.getLengthOfStay() <= 0) {
            return null;
        }
        return rental.getCheckinDate().plusHours(rental.getLengthOfStay());
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
        if (expectedCheckout == null) {
            return -1;
        }
        return ChronoUnit.MINUTES.between(LocalDateTime.now(), expectedCheckout);
    }

    public long calculateOverdueMinutes(Rental rental) {
        LocalDateTime expectedCheckout = calculateExpectedCheckout(rental);
        if (expectedCheckout == null) {
            return 0;
        }
        long overdueMinutes = ChronoUnit.MINUTES.between(expectedCheckout, LocalDateTime.now());
        return Math.max(overdueMinutes, 0);
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

    private void markCheckedInRentalsAsOverdueForCustomer(String customerId) {
        List<Rental> overdueRentals = rentalRepository.findByCustomerId(customerId)
                .stream()
                .filter(rental -> rental.getStatus() == RentalStatus.CHECKED_IN)
                .filter(rental -> calculateExpectedCheckout(rental) != null)
                .filter(rental -> LocalDateTime.now().isAfter(calculateExpectedCheckout(rental)))
                .toList();

        for (Rental rental : overdueRentals) {
            rental.setStatus(RentalStatus.OVERDUE);
        }

        if (!overdueRentals.isEmpty()) {
            rentalRepository.saveAll(overdueRentals);
        }
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

        Users currentUser = getLoggedInCustomer(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập để cập nhật hồ sơ.");
            return "redirect:/login";
        }

        Optional<Users> userOpt = usersRepository.findById(currentUser.getId());
        if (userOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy người dùng.");
            return "redirect:/customer/profile";
        }

        String trimmedLastName = safeTrim(lastName);
        String trimmedFirstName = safeTrim(firstName);
        String trimmedPhoneNumber = safeTrim(phoneNumber);
        String trimmedPid = safeTrim(pid);
        String trimmedNationality = safeTrim(nationality);

        if (trimmedLastName.isBlank() || trimmedFirstName.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Họ tên không được để trống.");
            return "redirect:/customer/profile";
        }

        if (!trimmedFirstName.matches("^[\\p{L}\\s]{1,30}$") || !trimmedLastName.matches("^[\\p{L}\\s]{1,30}$")) {
            redirectAttributes.addFlashAttribute("error", "Họ tên chỉ được chứa chữ cái và tối đa 30 ký tự.");
            return "redirect:/customer/profile";
        }

        if (!trimmedPhoneNumber.matches("^\\d{9,11}$")) {
            redirectAttributes.addFlashAttribute("error", "Số điện thoại phải từ 9 đến 11 chữ số.");
            return "redirect:/customer/profile";
        }

        if (trimmedPid.isBlank() || trimmedPid.length() < 9 || trimmedPid.length() > 20) {
            redirectAttributes.addFlashAttribute("error", "CCCD/CMND/Hộ chiếu phải từ 9 đến 20 ký tự.");
            return "redirect:/customer/profile";
        }

        if (trimmedNationality.isBlank() || trimmedNationality.length() > 100) {
            redirectAttributes.addFlashAttribute("error", "Quốc tịch không hợp lệ.");
            return "redirect:/customer/profile";
        }

        if (!isValidDateOfBirth(dateOfBirth)) {
            redirectAttributes.addFlashAttribute("error", "Ngày sinh phải từ năm 1900 đến hiện tại.");
            return "redirect:/customer/profile";
        }

        Gender gender;
        try {
            gender = Gender.valueOf(safeTrim(sex).toUpperCase());
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("error", "Giới tính không hợp lệ.");
            return "redirect:/customer/profile";
        }

        Optional<Users> existingPid = usersRepository.findByPid(trimmedPid);
        if (existingPid.isPresent() && !existingPid.get().getId().equals(currentUser.getId())) {
            redirectAttributes.addFlashAttribute("error", "CCCD/CMND/Hộ chiếu đã được dùng bởi tài khoản khác.");
            return "redirect:/customer/profile";
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
                String avatarPath = storeAvatarFile(avatarFile, user.getId());
                user.setAvatar(avatarPath);
            } catch (IllegalArgumentException e) {
                redirectAttributes.addFlashAttribute("error", e.getMessage());
                return "redirect:/customer/profile";
            } catch (IOException e) {
                redirectAttributes.addFlashAttribute("error", "Không thể lưu ảnh đại diện.");
                return "redirect:/customer/profile";
            }
        }

        user.setUpdateDate(LocalDateTime.now());
        usersRepository.save(user);
        session.setAttribute("loggedInUser", user);

        redirectAttributes.addFlashAttribute("message", "Cập nhật hồ sơ thành công.");
        return "redirect:/customer/profile";
    }

    @PostMapping("/profile/change-password")
    public String changePassword(@RequestParam String oldPass,
                                 @RequestParam String newPass,
                                 @RequestParam String confirmPass,
                                 HttpSession session,
                                 RedirectAttributes redirectAttributes) {

        Users currentUser = getLoggedInCustomer(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập để đổi mật khẩu.");
            return "redirect:/login";
        }

        Optional<Account> accountOpt = accountRepository.findById(currentUser.getId());
        if (accountOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy tài khoản.");
            return "redirect:/customer/profile";
        }

        Account account = accountOpt.get();

        if (!PasswordUtils.matches(oldPass, account.getPassword())) {
            redirectAttributes.addFlashAttribute("error", "Mật khẩu hiện tại không đúng.");
            return "redirect:/customer/profile";
        }

        if (newPass == null || newPass.length() < 8 || newPass.length() > 255) {
            redirectAttributes.addFlashAttribute("error", "Mật khẩu mới phải từ 8 đến 255 ký tự.");
            return "redirect:/customer/profile";
        }

        if (!newPass.equals(confirmPass)) {
            redirectAttributes.addFlashAttribute("error", "Xác nhận mật khẩu mới không khớp.");
            return "redirect:/customer/profile";
        }

        account.setPassword(PasswordUtils.hashPassword(newPass));
        accountRepository.save(account);

        redirectAttributes.addFlashAttribute("message", "Đổi mật khẩu thành công.");
        return "redirect:/customer/profile";
    }

    @GetMapping("/services")
    public String services(Model model, HttpSession session) {
        Users currentUser = getLoggedInUser(session);

        List<Service> services = serviceRepository.findByStatusOrderByCreateDateDesc(ServiceStatus.ACTIVE);

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("services", services);

        return "customer/Services";
    }

    @GetMapping("/policy")
    public String policy(Model model, HttpSession session) {
        Users currentUser = getLoggedInUser(session);

        List<Policy> policies = policyRepository.findAllByOrderByCreateDateDesc()
                .stream()
                .map(this::buildSafePolicyView)
                .toList();

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("policies", policies);

        return "customer/Policy";
    }

    @GetMapping("/feedback")
    public String feedback(Model model,
                           HttpSession session) {
        Users currentUser = getLoggedInUser(session);
        List<Review> reviews = reviewRepository.findAllByOrderByUpdateDateDesc();

        Review myReview = null;
        boolean canManageReview = false;

        if (currentUser != null && currentUser.getRole() == Role.CUSTOMER) {
            canManageReview = true;
            myReview = reviewRepository.findByCustomerId(currentUser.getId()).orElse(null);
        }

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("reviews", reviews);
        model.addAttribute("myReview", myReview);
        model.addAttribute("canManageReview", canManageReview);

        return "customer/Feedback";
    }

    @PostMapping("/feedback")
    public String submitFeedback(@RequestParam Integer rate,
                                 @RequestParam(required = false) String description,
                                 HttpSession session,
                                 RedirectAttributes redirectAttributes) {

        Users currentUser = getLoggedInCustomer(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập bằng tài khoản khách hàng để gửi đánh giá.");
            return "redirect:/login";
        }

        Optional<Customer> customerOpt = customerRepository.findById(currentUser.getId());
        if (customerOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy khách hàng.");
            return "redirect:/customer/feedback";
        }

        if (reviewRepository.existsByCustomerId(currentUser.getId())) {
            redirectAttributes.addFlashAttribute("error", "Bạn đã có đánh giá rồi. Vui lòng sửa hoặc xóa đánh giá hiện tại.");
            return "redirect:/customer/feedback";
        }

        String trimmedDescription = safeTrim(description);

        if (rate == null || rate < 1 || rate > 5) {
            redirectAttributes.addFlashAttribute("error", "Điểm đánh giá phải từ 1 đến 5.");
            return "redirect:/customer/feedback";
        }

        if (trimmedDescription.length() > 255) {
            redirectAttributes.addFlashAttribute("error", "Nội dung đánh giá tối đa 255 ký tự.");
            return "redirect:/customer/feedback";
        }

        Review review = new Review();
        review.setId(generateId("REV", 10));
        review.setRate(rate);
        review.setDescription(trimmedDescription);
        review.setUpdateDate(LocalDateTime.now());
        review.setCustomer(customerOpt.get());

        reviewRepository.save(review);

        redirectAttributes.addFlashAttribute("message", "Gửi đánh giá thành công.");
        return "redirect:/customer/feedback";
    }

    @PostMapping("/feedback/update")
    public String updateFeedback(@RequestParam Integer rate,
                                 @RequestParam(required = false) String description,
                                 HttpSession session,
                                 RedirectAttributes redirectAttributes) {

        Users currentUser = getLoggedInCustomer(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập bằng tài khoản khách hàng để sửa đánh giá.");
            return "redirect:/login";
        }

        Review existingReview = reviewRepository.findByCustomerId(currentUser.getId()).orElse(null);
        if (existingReview == null) {
            redirectAttributes.addFlashAttribute("error", "Bạn chưa có đánh giá để chỉnh sửa.");
            return "redirect:/customer/feedback";
        }

        String trimmedDescription = safeTrim(description);

        if (rate == null || rate < 1 || rate > 5) {
            redirectAttributes.addFlashAttribute("error", "Điểm đánh giá phải từ 1 đến 5.");
            return "redirect:/customer/feedback";
        }

        if (trimmedDescription.length() > 255) {
            redirectAttributes.addFlashAttribute("error", "Nội dung đánh giá tối đa 255 ký tự.");
            return "redirect:/customer/feedback";
        }

        existingReview.setRate(rate);
        existingReview.setDescription(trimmedDescription);
        existingReview.setUpdateDate(LocalDateTime.now());
        reviewRepository.save(existingReview);

        redirectAttributes.addFlashAttribute("message", "Cập nhật đánh giá thành công.");
        return "redirect:/customer/feedback";
    }

    @PostMapping("/feedback/delete")
    public String deleteFeedback(HttpSession session,
                                 RedirectAttributes redirectAttributes) {

        Users currentUser = getLoggedInCustomer(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập bằng tài khoản khách hàng để xóa đánh giá.");
            return "redirect:/login";
        }

        Review existingReview = reviewRepository.findByCustomerId(currentUser.getId()).orElse(null);
        if (existingReview == null) {
            redirectAttributes.addFlashAttribute("error", "Bạn chưa có đánh giá để xóa.");
            return "redirect:/customer/feedback";
        }

        reviewRepository.delete(existingReview);
        redirectAttributes.addFlashAttribute("message", "Xóa đánh giá thành công.");
        return "redirect:/customer/feedback";
    }

    @GetMapping("/about")
    public String about(Model model, HttpSession session) {
        Users currentUser = getLoggedInUser(session);
        model.addAttribute("currentUser", currentUser);
        return "customer/About";
    }

    public String getPrimaryRoomImagePath(Room room) {
        if (room == null) {
            return getDefaultRoomImagePath();
        }

        return roomImageRepository.findFirstByRoomAndIsPrimaryTrue(room)
                .map(RoomImage::getImagePath)
                .filter(StringUtils::hasText)
                .orElseGet(() -> roomImageRepository.findAllByRoomOrderByCreateDateAsc(room).stream()
                        .map(RoomImage::getImagePath)
                        .filter(StringUtils::hasText)
                        .findFirst()
                        .orElse(getDefaultRoomImagePath()));
    }

    public List<String> getRoomGalleryPaths(Room room) {
        if (room == null) {
            return List.of(getDefaultRoomImagePath());
        }

        List<String> gallery = roomImageRepository.findAllByRoomOrderByCreateDateAsc(room).stream()
                .map(RoomImage::getImagePath)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();

        if (gallery.isEmpty()) {
            return List.of(getDefaultRoomImagePath());
        }

        return gallery;
    }

    public String getRepresentativeRoomImagePath(RoomType roomType) {
        List<String> representativeGalleryPaths = getRepresentativeRoomGalleryPaths(roomType);
        if (!representativeGalleryPaths.isEmpty()) {
            return representativeGalleryPaths.get(0);
        }
        return getDefaultRoomImagePath();
    }

    public List<String> getRepresentativeRoomGalleryPaths(RoomType roomType) {
        if (roomType == null || !StringUtils.hasText(roomType.getId())) {
            return List.of(getDefaultRoomImagePath());
        }

        List<Room> rooms = roomRepository.findByRoomTypeId(roomType.getId()).stream()
                .sorted(Comparator.comparing(Room::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();

        for (Room room : rooms) {
            List<String> galleryPaths = getRoomGalleryPaths(room);
            if (!galleryPaths.isEmpty()) {
                return galleryPaths;
            }
        }

        return List.of(getDefaultRoomImagePath());
    }

    public String getDefaultRoomImagePath() {
        return "https://images.unsplash.com/photo-1611892440504-42a792e24d32?auto=format&fit=crop&w=900&q=80";
    }

    private Policy buildSafePolicyView(Policy source) {
        Policy safePolicy = new Policy();
        safePolicy.setPolicyNumber(source.getPolicyNumber());
        safePolicy.setName(source.getName());
        safePolicy.setSubject(source.getSubject());
        safePolicy.setCreateDate(source.getCreateDate());
        safePolicy.setUpdateDate(source.getUpdateDate());
        safePolicy.setAdmin(source.getAdmin());
        safePolicy.setContent(sanitizeHtmlToSafeDisplay(source.getContent()));
        return safePolicy;
    }

    private String sanitizeHtmlToSafeDisplay(String raw) {
        String escaped = HtmlUtils.htmlEscape(raw == null ? "" : raw);
        return escaped
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\n", "<br/>");
    }

    private Users getLoggedInUser(HttpSession session) {
        Object userObj = session.getAttribute("loggedInUser");
        if (userObj instanceof Users user) {
            return user;
        }
        return null;
    }

    private Users getLoggedInCustomer(HttpSession session) {
        Users user = getLoggedInUser(session);
        if (user == null) {
            return null;
        }

        if (user.getRole() != Role.CUSTOMER) {
            return null;
        }

        return user;
    }

    private boolean isValidDateOfBirth(LocalDate dob) {
        if (dob == null) {
            return false;
        }

        LocalDate minDate = LocalDate.of(1900, 1, 1);
        LocalDate today = LocalDate.now();

        return !dob.isBefore(minDate) && !dob.isAfter(today);
    }

    private boolean isRoomAvailable(Room room, LocalDateTime checkIn, LocalDateTime checkOut) {
        return findBlockingRentalConflict(room, checkIn, checkOut) == null;
    }

    private Rental findBlockingRentalConflict(Room room, LocalDateTime checkIn, LocalDateTime checkOut) {
        if (room == null) {
            return new Rental();
        }

        if (room.getStatus() != RoomStatus.AVAILABLE) {
            return new Rental();
        }

        if (checkIn == null || checkOut == null) {
            return null;
        }

        LocalDateTime requestedStart = checkIn;
        LocalDateTime requestedEndWithBuffer = checkOut.plusMinutes(CLEANING_BUFFER_MINUTES);

        return rentalRepository.findAll()
                .stream()
                .filter(rental -> rental.getRoom() != null && rental.getRoom().getId() != null)
                .filter(rental -> rental.getRoom().getId().equals(room.getId()))
                .filter(rental -> rental.getStatus() != null && BLOCKING_BOOKING_STATUSES.contains(rental.getStatus()))
                .filter(rental -> rental.getCheckinDate() != null)
                .filter(rental -> rental.getLengthOfStay() != null && rental.getLengthOfStay() > 0)
                .filter(rental -> {
                    LocalDateTime existingStart = rental.getCheckinDate();
                    LocalDateTime existingEndWithBuffer = calculateRentalEndWithBuffer(rental);
                    return existingEndWithBuffer != null
                            && existingStart.isBefore(requestedEndWithBuffer)
                            && existingEndWithBuffer.isAfter(requestedStart);
                })
                .min(Comparator.comparing(Rental::getCheckinDate))
                .orElse(null);
    }

    private String buildConflictRange(Rental rental) {
        if (rental == null || rental.getCheckinDate() == null || rental.getLengthOfStay() == null) {
            return "không xác định";
        }

        LocalDateTime start = rental.getCheckinDate();
        LocalDateTime end = calculateRentalEndWithBuffer(rental);
        if (end == null) {
            return "không xác định";
        }

        return start.format(DATE_TIME_FORMATTER) + " đến " + end.format(DATE_TIME_FORMATTER);
    }

    private LocalDateTime calculateRentalEndWithBuffer(Rental rental) {
        if (rental == null || rental.getCheckinDate() == null || rental.getLengthOfStay() == null || rental.getLengthOfStay() <= 0) {
            return null;
        }

        LocalDateTime end = rental.getCheckinDate().plusHours(rental.getLengthOfStay());

        return end.plusMinutes(CLEANING_BUFFER_MINUTES);
    }

    private int calculateStayHours(LocalDateTime checkIn, LocalDateTime checkOut) {
        if (checkIn == null || checkOut == null) {
            return MINIMUM_STAY_HOURS;
        }

        long stayHours = ChronoUnit.HOURS.between(checkIn, checkOut);
        if (stayHours < MINIMUM_STAY_HOURS) {
            return MINIMUM_STAY_HOURS;
        }

        if (stayHours > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }

        return (int) stayHours;
    }

    private double calculateRoomSubTotal(double roomUnitPrice, int stayHours) {
        int normalizedStayHours = Math.max(stayHours, MINIMUM_STAY_HOURS);
        return Math.max(roomUnitPrice, 0.0) * normalizedStayHours;
    }

    private double calculateShortStaySurcharge(double roomSubTotal, int stayHours) {
        if (stayHours < SHORT_STAY_SURCHARGE_THRESHOLD_HOURS) {
            return roomSubTotal * SHORT_STAY_SURCHARGE_PERCENT / 100.0;
        }
        return 0.0;
    }

    private double resolveDepositPercent(Room room) {
        if (room == null || room.getRoomType() == null || room.getRoomType().getDepositPercent() == null) {
            return 0.0;
        }

        double depositPercent = room.getRoomType().getDepositPercent();
        if (depositPercent < 0) {
            return 0.0;
        }
        if (depositPercent > 100) {
            return 100.0;
        }
        return depositPercent;
    }

    private double calculateDepositAmount(double totalAmount, double depositPercent) {
        return Math.max(totalAmount, 0.0) * resolveDepositPercentValue(depositPercent) / 100.0;
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

    private double resolveRoomUnitPrice(Room room, LocalDateTime checkInDate) {
        if (room == null || room.getRoomType() == null) {
            return 0.0;
        }

        RoomType roomType = room.getRoomType();
        double basePrice = roomType.getBasePrice() == null ? 0.0 : roomType.getBasePrice();

        if (roomType.getId() == null || checkInDate == null) {
            return basePrice;
        }

        LocalDateTime effectiveMoment = checkInDate;

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

    private double calculateDepositPaid(String rentalId) {
        return billRepository.findByRentalIdOrderByCreateDateDesc(rentalId)
                .stream()
                .filter(item -> item.getType() == BillType.DEPOSIT)
                .mapToDouble(item -> item.getTotalAmount() == null ? 0.0 : item.getTotalAmount())
                .sum();
    }

    private void expirePendingBookingsWithoutDeposit() {
        LocalDateTime expiryThreshold = LocalDateTime.now().minusMinutes(BOOKING_HOLD_MINUTES);

        List<Rental> expiredBookings = rentalRepository.findAll()
                .stream()
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

    private LocalDateTime getMinimumBookingStartTime() {
        LocalDateTime now = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        if (LocalDateTime.now().isAfter(now)) {
            return now.plusHours(1);
        }
        return now;
    }

    private String formatDateTimeParam(LocalDateTime dateTime) {
        return dateTime == null ? "" : dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private String formatMoney(double amount) {
        long rounded = Math.round(amount);
        return String.format("%,d đ", rounded).replace(',', '.');
    }

    private String storeAvatarFile(MultipartFile file, String userId) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File avatar trống.");
        }

        if (file.getSize() > 2 * 1024 * 1024) {
            throw new IllegalArgumentException("Ảnh đại diện không được vượt quá 2MB.");
        }

        String contentType = file.getContentType();
        if (contentType == null ||
                (!contentType.equalsIgnoreCase("image/jpeg")
                        && !contentType.equalsIgnoreCase("image/png")
                        && !contentType.equalsIgnoreCase("image/jpg")
                        && !contentType.equalsIgnoreCase("image/webp"))) {
            throw new IllegalArgumentException("Chỉ chấp nhận ảnh JPG, PNG hoặc WEBP.");
        }

        String originalFilename = StringUtils.cleanPath(file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
        String extension = getFileExtension(originalFilename);
        if (extension.isBlank()) {
            extension = "png";
        }

        String safeExtension = extension.toLowerCase();
        if (!safeExtension.equals("jpg") && !safeExtension.equals("jpeg") && !safeExtension.equals("png") && !safeExtension.equals("webp")) {
            throw new IllegalArgumentException("Định dạng file không hợp lệ.");
        }

        if (!hasValidImageSignature(file)) {
            throw new IllegalArgumentException("Nội dung file không phải ảnh hợp lệ.");
        }

        Path avatarDir = Paths.get(uploadDir, "avatars").toAbsolutePath().normalize();
        Files.createDirectories(avatarDir);

        String fileName = userId + "_" + UUID.randomUUID().toString().replace("-", "") + "." + safeExtension;
        Path targetPath = avatarDir.resolve(fileName).normalize();

        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }

        return "/uploads/avatars/" + fileName;
    }

    private boolean hasValidImageSignature(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            byte[] header = inputStream.readNBytes(12);
            if (header.length < 4) {
                return false;
            }

            return isJpeg(header) || isPng(header) || isWebp(header);
        }
    }

    private boolean isJpeg(byte[] header) {
        return header.length >= 3
                && (header[0] & 0xFF) == 0xFF
                && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF;
    }

    private boolean isPng(byte[] header) {
        return header.length >= 8
                && (header[0] & 0xFF) == 0x89
                && header[1] == 0x50
                && header[2] == 0x4E
                && header[3] == 0x47
                && (header[4] & 0xFF) == 0x0D
                && (header[5] & 0xFF) == 0x0A
                && (header[6] & 0xFF) == 0x1A
                && (header[7] & 0xFF) == 0x0A;
    }

    private boolean isWebp(byte[] header) {
        return header.length >= 12
                && header[0] == 0x52
                && header[1] == 0x49
                && header[2] == 0x46
                && header[3] == 0x46
                && header[8] == 0x57
                && header[9] == 0x45
                && header[10] == 0x42
                && header[11] == 0x50;
    }

    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastDot + 1);
    }

    private String generateId(String prefix, int totalLength) {
        int randomLength = totalLength - prefix.length();
        if (randomLength <= 0) {
            throw new IllegalArgumentException("Độ dài totalLength phải lớn hơn prefix length");
        }

        String randomPart = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        return prefix + randomPart.substring(0, randomLength);
    }
}
