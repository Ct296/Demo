package com.hotel.system.controller;

import com.hotel.system.entity.HistoryWork;
import com.hotel.system.entity.Manager;
import com.hotel.system.entity.Staff;
import com.hotel.system.entity.Users;
import com.hotel.system.entity.WorkAssignment;
import com.hotel.system.entity.WorkSchedule;
import com.hotel.system.entity.enums.AttendanceStatus;
import com.hotel.system.entity.enums.Shift;
import com.hotel.system.repository.HistoryWorkRepository;
import com.hotel.system.repository.StaffRepository;
import com.hotel.system.repository.WorkAssignmentRepository;
import com.hotel.system.repository.WorkScheduleRepository;
import com.hotel.system.service.ManagerAccessService;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/manager/hr")
public class ManagerHrController {

    private static final Locale VI_LOCALE = Locale.forLanguageTag("vi-VN");
    private static final DateTimeFormatter MONTH_PARAM_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final StaffRepository staffRepository;
    private final WorkScheduleRepository workScheduleRepository;
    private final HistoryWorkRepository historyWorkRepository;
    private final WorkAssignmentRepository workAssignmentRepository;
    private final ManagerAccessService managerAccessService;

    public ManagerHrController(StaffRepository staffRepository,
                               WorkScheduleRepository workScheduleRepository,
                               HistoryWorkRepository historyWorkRepository,
                               WorkAssignmentRepository workAssignmentRepository,
                               ManagerAccessService managerAccessService) {
        this.staffRepository = staffRepository;
        this.workScheduleRepository = workScheduleRepository;
        this.historyWorkRepository = historyWorkRepository;
        this.workAssignmentRepository = workAssignmentRepository;
        this.managerAccessService = managerAccessService;
    }

    @GetMapping("/dashboard")
    @Transactional
    public String dashboard(@RequestParam(required = false) String month,
                            @RequestParam(required = false) String selectedDate,
                            @RequestParam(required = false) String attendanceScheduleId,
                            HttpSession session,
                            Model model,
                            RedirectAttributes redirectAttributes) {

        Users currentUser = managerAccessService.getLoggedInManagerUser(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập bằng tài khoản quản lý.");
            return "redirect:/login";
        }

        if (!managerAccessService.hasAccess(session, ManagerAccessService.ManagerModule.HR)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền truy cập phân hệ quản lý nhân sự.");
            return "redirect:/manager";
        }

        Manager manager = managerAccessService.getLoggedInManager(session);
        if (manager == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy hồ sơ quản lý tương ứng.");
            return "redirect:/login";
        }

        List<Staff> staffs = staffRepository.findAllByOrderByEmploymentTimeDesc();
        List<WorkSchedule> allSchedules = workScheduleRepository.findAll().stream()
                .sorted(Comparator.comparing(WorkSchedule::getDate).thenComparing(ws -> ws.getShift().ordinal()))
                .toList();
        List<WorkAssignment> allAssignments = workAssignmentRepository.findAll().stream()
                .sorted(Comparator.comparing(WorkAssignment::getAssignedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        syncAttendanceRecords(allAssignments);

        List<HistoryWork> allHistoryWorks = historyWorkRepository.findAll().stream()
                .sorted(Comparator.comparing((HistoryWork hw) -> hw.getWorkSchedule().getDate()).reversed()
                        .thenComparing(hw -> hw.getWorkSchedule().getShift().ordinal())
                        .thenComparing(hw -> fullNameOf(hw.getStaff())))
                .toList();

        YearMonth currentMonth = parseMonth(month);
        LocalDate currentSelectedDate = parseSelectedDate(selectedDate, currentMonth);

        Map<String, List<WorkAssignment>> assignmentsByScheduleId = allAssignments.stream()
                .filter(item -> item.getWorkSchedule() != null && item.getWorkSchedule().getId() != null)
                .collect(Collectors.groupingBy(item -> item.getWorkSchedule().getId(), LinkedHashMap::new, Collectors.toList()));

        Map<String, List<HistoryWork>> historyByScheduleId = allHistoryWorks.stream()
                .filter(item -> item.getWorkSchedule() != null && item.getWorkSchedule().getId() != null)
                .collect(Collectors.groupingBy(item -> item.getWorkSchedule().getId(), LinkedHashMap::new, Collectors.toList()));

        List<WorkSchedule> schedulesInMonth = allSchedules.stream()
                .filter(ws -> YearMonth.from(ws.getDate()).equals(currentMonth))
                .toList();

        Map<LocalDate, List<ScheduleSummaryView>> scheduleSummariesByDate = new LinkedHashMap<>();
        for (WorkSchedule schedule : schedulesInMonth) {
            List<WorkAssignment> scheduleAssignments = assignmentsByScheduleId.getOrDefault(schedule.getId(), List.of()).stream()
                    .filter(item -> item.getEndAt() == null)
                    .toList();

            List<StaffMiniView> staffMiniViews = scheduleAssignments.stream()
                    .filter(item -> item.getStaff() != null)
                    .map(item -> new StaffMiniView(
                            item.getId(),
                            item.getStaff().getId(),
                            shortNameOf(item.getStaff()),
                            fullNameOf(item.getStaff()),
                            safeTrim(item.getNote()).isBlank() ? null : safeTrim(item.getNote())
                    ))
                    .toList();

            ScheduleWindow scheduleWindow = resolveWindow(schedule.getDate(), schedule.getShift());
            String badgeClass = staffMiniViews.isEmpty() ? "empty" : "filled";

            scheduleSummariesByDate.computeIfAbsent(schedule.getDate(), k -> new ArrayList<>())
                    .add(new ScheduleSummaryView(
                            schedule.getId(),
                            schedule.getShift(),
                            schedule.getShift().getDisplayName(),
                            scheduleWindow.start().toLocalTime().toString(),
                            formatRange(scheduleWindow.start(), scheduleWindow.end()),
                            staffMiniViews,
                            badgeClass,
                            schedule.getDate().equals(LocalDate.now())
                    ));
        }

        scheduleSummariesByDate.replaceAll((dateKey, value) -> value.stream()
                .sorted(Comparator.comparingInt(v -> v.getShift().ordinal()))
                .toList());

        List<List<CalendarDayView>> calendarWeeks = buildCalendarWeeks(currentMonth, scheduleSummariesByDate, currentSelectedDate);
        List<ScheduleSummaryView> selectedDateSchedules = scheduleSummariesByDate.getOrDefault(currentSelectedDate, List.of());

        String resolvedAttendanceScheduleId = attendanceScheduleId;
        if ((resolvedAttendanceScheduleId == null || resolvedAttendanceScheduleId.isBlank()) && !selectedDateSchedules.isEmpty()) {
            resolvedAttendanceScheduleId = selectedDateSchedules.get(0).getScheduleId();
        }
        final String selectedAttendanceScheduleId = resolvedAttendanceScheduleId;

        List<AttendanceScheduleView> attendanceSchedules = buildAttendanceSchedules(
                allSchedules,
                assignmentsByScheduleId,
                historyByScheduleId
        );

        AttendanceScheduleView selectedAttendanceSchedule = attendanceSchedules.stream()
                .filter(item -> selectedAttendanceScheduleId != null && item.getScheduleId().equals(selectedAttendanceScheduleId))
                .findFirst()
                .orElse(attendanceSchedules.isEmpty() ? null : attendanceSchedules.get(0));

        long missedCount = allHistoryWorks.stream()
                .filter(item -> item.getStatus() == AttendanceStatus.MISSED)
                .count();

        long completedCount = allHistoryWorks.stream()
                .filter(item -> item.getStatus() == AttendanceStatus.COMPLETED)
                .count();

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("currentManager", manager);
        model.addAttribute("staffs", staffs);
        model.addAttribute("shifts", Shift.values());
        model.addAttribute("monthValue", currentMonth.format(MONTH_PARAM_FORMAT));
        model.addAttribute("monthTitle", capitalize(currentMonth.getMonth().getDisplayName(TextStyle.FULL, VI_LOCALE)) + " " + currentMonth.getYear());
        model.addAttribute("calendarWeeks", calendarWeeks);
        model.addAttribute("selectedDateValue", currentSelectedDate.toString());
        model.addAttribute("selectedDateLabel", currentSelectedDate.format(DATE_FORMAT));
        model.addAttribute("selectedDateSchedules", selectedDateSchedules);
        model.addAttribute("attendanceSchedules", attendanceSchedules);
        model.addAttribute("selectedAttendanceSchedule", selectedAttendanceSchedule);
        model.addAttribute("workSchedules", allSchedules);
        model.addAttribute("workAssignments", allAssignments);
        model.addAttribute("historyWorks", allHistoryWorks);
        model.addAttribute("assignmentCountByScheduleId", allAssignments.stream()
                .filter(item -> item.getWorkSchedule() != null && item.getWorkSchedule().getId() != null && item.getEndAt() == null)
                .collect(Collectors.groupingBy(item -> item.getWorkSchedule().getId(), LinkedHashMap::new, Collectors.counting())));
        model.addAttribute("attendanceCountByScheduleId", allHistoryWorks.stream()
                .filter(item -> item.getWorkSchedule() != null && item.getWorkSchedule().getId() != null)
                .collect(Collectors.groupingBy(item -> item.getWorkSchedule().getId(), LinkedHashMap::new, Collectors.counting())));
        model.addAttribute("staffCount", staffs.size());
        model.addAttribute("scheduleCount", allSchedules.size());
        model.addAttribute("activeAssignmentCount", allAssignments.stream().filter(item -> item.getEndAt() == null).count());
        model.addAttribute("completedAttendanceCount", completedCount);
        model.addAttribute("missedAttendanceCount", missedCount);
        model.addAttribute("attendanceStatusCount", buildStatusCountMap(allHistoryWorks));

        return "manager/HrManager";
    }

    @PostMapping("/work-schedules/save")
    @Transactional
    public String saveWorkSchedule(@RequestParam String date,
                                   @RequestParam Shift shift,
                                   HttpSession session,
                                   RedirectAttributes redirectAttributes) {

        if (!managerAccessService.hasAccess(session, ManagerAccessService.ManagerModule.HR)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền thực hiện chức năng này.");
            return "redirect:/manager";
        }

        LocalDate scheduleDate;
        try {
            scheduleDate = LocalDate.parse(date);
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("error", "Ngày làm việc không đúng định dạng.");
            return "redirect:/manager/hr/dashboard#schedule-section";
        }

        ScheduleWindow window = resolveWindow(scheduleDate, shift);
        if (window.end().plusMinutes(15).isBefore(LocalDateTime.now())) {
            redirectAttributes.addFlashAttribute("error", "Không thể tạo ca trong quá khứ.");
            return "redirect:/manager/hr/dashboard?month=" + YearMonth.from(scheduleDate).format(MONTH_PARAM_FORMAT) + "#schedule-section";
        }

        if (workScheduleRepository.existsByDateAndShift(scheduleDate, shift)) {
            redirectAttributes.addFlashAttribute("error", "Ngày này đã có ca " + shift.getDisplayName() + ".");
            return "redirect:/manager/hr/dashboard?month=" + YearMonth.from(scheduleDate).format(MONTH_PARAM_FORMAT) + "#schedule-section";
        }

        WorkSchedule workSchedule = new WorkSchedule();
        workSchedule.setId(generateId("WKS", 10));
        workSchedule.setDate(scheduleDate);
        workSchedule.setShift(shift);
        workScheduleRepository.save(workSchedule);

        redirectAttributes.addFlashAttribute("message", "Đã tạo ca " + shift.getDisplayName() + " cho ngày " + scheduleDate.format(DATE_FORMAT) + ".");
        return "redirect:/manager/hr/dashboard?month=" + YearMonth.from(scheduleDate).format(MONTH_PARAM_FORMAT) + "&selectedDate=" + scheduleDate + "#schedule-section";
    }

    @PostMapping("/work-assignments/save")
    @Transactional
    public String saveWorkAssignment(@RequestParam String staffId,
                                     @RequestParam String workScheduleId,
                                     @RequestParam(required = false) String note,
                                     HttpSession session,
                                     RedirectAttributes redirectAttributes) {
        return assignStaffToWorkSchedule(workScheduleId, staffId, note, session, redirectAttributes);
    }

    @PostMapping("/work-schedules/{workScheduleId}/assign-staff")
    @Transactional
    public String assignStaffToWorkSchedule(@PathVariable String workScheduleId,
                                            @RequestParam String staffId,
                                            @RequestParam(required = false) String note,
                                            HttpSession session,
                                            RedirectAttributes redirectAttributes) {

        if (!managerAccessService.hasAccess(session, ManagerAccessService.ManagerModule.HR)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền thực hiện chức năng này.");
            return "redirect:/manager";
        }

        WorkSchedule workSchedule = workScheduleRepository.findById(workScheduleId).orElse(null);
        Staff staff = staffRepository.findById(staffId).orElse(null);

        if (workSchedule == null || staff == null) {
            redirectAttributes.addFlashAttribute("error", "Dữ liệu phân công ca không hợp lệ.");
            return "redirect:/manager/hr/dashboard#schedule-section";
        }

        ScheduleWindow window = resolveWindow(workSchedule.getDate(), workSchedule.getShift());
        if (window.end().plusMinutes(15).isBefore(LocalDateTime.now())) {
            redirectAttributes.addFlashAttribute("error", "Không thể phân công nhân viên vào ca đã qua.");
            return redirectToSchedule(workSchedule, workScheduleId);
        }

        if (workAssignmentRepository.existsByStaffIdAndWorkScheduleId(staffId, workScheduleId)) {
            redirectAttributes.addFlashAttribute("error", "Nhân viên này đã được phân công vào ca đã chọn.");
            return redirectToSchedule(workSchedule, workScheduleId);
        }

        WorkAssignment workAssignment = new WorkAssignment();
        workAssignment.setId(generateId("WAT", 10));
        workAssignment.setAssignedAt(LocalDateTime.now());
        workAssignment.setEndAt(null);
        workAssignment.setNote(safeTrim(note).isBlank() ? null : safeTrim(note));
        workAssignment.setStaff(staff);
        workAssignment.setWorkSchedule(workSchedule);
        workAssignmentRepository.save(workAssignment);

        ensureHistoryWork(staff, workSchedule);

        redirectAttributes.addFlashAttribute("message", "Đã phân công " + fullNameOf(staff) + " vào ca " + workSchedule.getShift().getDisplayName() + ".");
        return redirectToSchedule(workSchedule, workScheduleId);
    }

    @PostMapping("/work-schedules/{workScheduleId}/checkin/{staffId}")
    @Transactional
    public String checkin(@PathVariable String workScheduleId,
                          @PathVariable String staffId,
                          HttpSession session,
                          RedirectAttributes redirectAttributes) {

        if (!managerAccessService.hasAccess(session, ManagerAccessService.ManagerModule.HR)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền thực hiện chức năng này.");
            return "redirect:/manager";
        }

        WorkSchedule schedule = workScheduleRepository.findById(workScheduleId).orElse(null);
        Staff staff = staffRepository.findById(staffId).orElse(null);
        if (schedule == null || staff == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy dữ liệu ca hoặc nhân viên.");
            return "redirect:/manager/hr/dashboard#attendance-section";
        }

        if (!workAssignmentRepository.existsByStaffIdAndWorkScheduleId(staffId, workScheduleId)) {
            redirectAttributes.addFlashAttribute("error", "Nhân viên chưa được phân công vào ca này.");
            return redirectToAttendance(schedule, workScheduleId);
        }

        ScheduleWindow window = resolveWindow(schedule.getDate(), schedule.getShift());
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(window.start().minusMinutes(15))) {
            redirectAttributes.addFlashAttribute("error", "Chưa tới thời điểm check-in cho ca này.");
            return redirectToAttendance(schedule, workScheduleId);
        }
        if (now.isAfter(window.end().plusMinutes(15))) {
            HistoryWork missed = ensureHistoryWork(staff, schedule);
            missed.setStatus(AttendanceStatus.MISSED);
            historyWorkRepository.save(missed);
            redirectAttributes.addFlashAttribute("error", "Ca đã quá hạn check-in. Hệ thống đã ghi nhận vắng ca.");
            return redirectToAttendance(schedule, workScheduleId);
        }

        HistoryWork historyWork = ensureHistoryWork(staff, schedule);
        if (historyWork.getStatus() == AttendanceStatus.COMPLETED) {
            redirectAttributes.addFlashAttribute("message", "Nhân viên này đã chấm công hoàn tất cho ca đã chọn.");
            return redirectToAttendance(schedule, workScheduleId);
        }
        if (historyWork.getCheckinTime() != null) {
            redirectAttributes.addFlashAttribute("message", "Nhân viên này đã được check-in trước đó.");
            return redirectToAttendance(schedule, workScheduleId);
        }

        historyWork.setCheckinTime(now);
        historyWork.setStatus(AttendanceStatus.CHECKED_IN);
        historyWorkRepository.save(historyWork);

        redirectAttributes.addFlashAttribute("message", "Đã check-in cho " + fullNameOf(staff) + ".");
        return redirectToAttendance(schedule, workScheduleId);
    }

    @PostMapping("/work-schedules/{workScheduleId}/checkout/{staffId}")
    @Transactional
    public String checkout(@PathVariable String workScheduleId,
                           @PathVariable String staffId,
                           HttpSession session,
                           RedirectAttributes redirectAttributes) {

        if (!managerAccessService.hasAccess(session, ManagerAccessService.ManagerModule.HR)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền thực hiện chức năng này.");
            return "redirect:/manager";
        }

        WorkSchedule schedule = workScheduleRepository.findById(workScheduleId).orElse(null);
        Staff staff = staffRepository.findById(staffId).orElse(null);
        if (schedule == null || staff == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy dữ liệu ca hoặc nhân viên.");
            return "redirect:/manager/hr/dashboard#attendance-section";
        }

        ScheduleWindow window = resolveWindow(schedule.getDate(), schedule.getShift());
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(window.end().minusMinutes(15))) {
            redirectAttributes.addFlashAttribute("error", "Chưa tới cửa sổ checkout của ca này.");
            return redirectToAttendance(schedule, workScheduleId);
        }
        if (now.isAfter(window.end().plusMinutes(15))) {
            HistoryWork historyWork = ensureHistoryWork(staff, schedule);
            if (historyWork.getCheckoutTime() == null) {
                historyWork.setStatus(AttendanceStatus.MISSED);
                historyWorkRepository.save(historyWork);
            }
            redirectAttributes.addFlashAttribute("error", "Ca đã quá hạn checkout. Hệ thống đã ghi nhận vắng ca.");
            return redirectToAttendance(schedule, workScheduleId);
        }

        HistoryWork historyWork = ensureHistoryWork(staff, schedule);
        if (historyWork.getCheckinTime() == null) {
            redirectAttributes.addFlashAttribute("error", "Nhân viên chưa được check-in cho ca này.");
            return redirectToAttendance(schedule, workScheduleId);
        }
        if (historyWork.getCheckoutTime() != null) {
            redirectAttributes.addFlashAttribute("message", "Nhân viên này đã được checkout trước đó.");
            return redirectToAttendance(schedule, workScheduleId);
        }

        historyWork.setCheckoutTime(now);
        historyWork.setStatus(AttendanceStatus.COMPLETED);
        historyWorkRepository.save(historyWork);

        Optional<WorkAssignment> assignmentOpt = workAssignmentRepository.findByStaffIdAndWorkScheduleId(staffId, workScheduleId);
        assignmentOpt.ifPresent(assignment -> {
            if (assignment.getEndAt() == null) {
                assignment.setEndAt(now);
                workAssignmentRepository.save(assignment);
            }
        });

        redirectAttributes.addFlashAttribute("message", "Đã checkout cho " + fullNameOf(staff) + ".");
        return redirectToAttendance(schedule, workScheduleId);
    }

    @PostMapping("/work-assignments/{workAssignmentId}/remove")
    @Transactional
    public String removeAssignment(@PathVariable String workAssignmentId,
                                   HttpSession session,
                                   RedirectAttributes redirectAttributes) {

        if (!managerAccessService.hasAccess(session, ManagerAccessService.ManagerModule.HR)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền thực hiện chức năng này.");
            return "redirect:/manager";
        }

        WorkAssignment assignment = workAssignmentRepository.findById(workAssignmentId).orElse(null);
        if (assignment == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy phân công cần gỡ.");
            return "redirect:/manager/hr/dashboard#schedule-section";
        }

        WorkSchedule schedule = assignment.getWorkSchedule();
        if (schedule == null) {
            workAssignmentRepository.deleteById(workAssignmentId);
            redirectAttributes.addFlashAttribute("message", "Đã gỡ phân công.");
            return "redirect:/manager/hr/dashboard#schedule-section";
        }

        HistoryWork historyWork = historyWorkRepository
                .findByStaffIdAndWorkScheduleId(assignment.getStaff().getId(), schedule.getId())
                .orElse(null);

        if (historyWork != null && (historyWork.getCheckinTime() != null || historyWork.getCheckoutTime() != null)) {
            redirectAttributes.addFlashAttribute("error", "Không thể gỡ phân công khi đã có dữ liệu chấm công thực tế.");
            return redirectToSchedule(schedule, schedule.getId());
        }

        if (historyWork != null) {
            historyWorkRepository.delete(historyWork);
        }
        workAssignmentRepository.delete(assignment);
        redirectAttributes.addFlashAttribute("message", "Đã gỡ phân công nhân viên khỏi ca làm.");
        return redirectToSchedule(schedule, schedule.getId());
    }

    @PostMapping("/work-schedules/{workScheduleId}/delete")
    @Transactional
    public String deleteWorkSchedule(@PathVariable String workScheduleId,
                                     HttpSession session,
                                     RedirectAttributes redirectAttributes) {

        if (!managerAccessService.hasAccess(session, ManagerAccessService.ManagerModule.HR)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền thực hiện chức năng này.");
            return "redirect:/manager";
        }

        WorkSchedule schedule = workScheduleRepository.findById(workScheduleId).orElse(null);
        if (schedule == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy ca làm cần xóa.");
            return "redirect:/manager/hr/dashboard#schedule-section";
        }

        List<WorkAssignment> assignments = workAssignmentRepository.findByWorkScheduleIdOrderByAssignedAtDesc(workScheduleId);
        List<HistoryWork> historyWorks = historyWorkRepository.findByWorkScheduleIdOrderByStaffIdAsc(workScheduleId);

        boolean hasActualAttendance = historyWorks.stream()
                .anyMatch(item -> item.getCheckinTime() != null || item.getCheckoutTime() != null);
        if (hasActualAttendance) {
            redirectAttributes.addFlashAttribute("error", "Không thể xóa ca làm khi đã có dữ liệu chấm công thực tế.");
            return redirectToSchedule(schedule, schedule.getId());
        }

        if (!historyWorks.isEmpty()) {
            historyWorkRepository.deleteAll(historyWorks);
        }
        if (!assignments.isEmpty()) {
            workAssignmentRepository.deleteAll(assignments);
        }
        workScheduleRepository.delete(schedule);

        redirectAttributes.addFlashAttribute(
                "message",
                "Đã xóa ca " + schedule.getShift().getDisplayName() + " ngày " + schedule.getDate().format(DATE_FORMAT) + "."
        );
        return "redirect:/manager/hr/dashboard?month=" + YearMonth.from(schedule.getDate()).format(MONTH_PARAM_FORMAT) + "&selectedDate=" + schedule.getDate() + "#schedule-section";
    }

    private void syncAttendanceRecords(List<WorkAssignment> assignments) {
        for (WorkAssignment assignment : assignments) {
            if (assignment.getStaff() == null || assignment.getWorkSchedule() == null || assignment.getEndAt() != null) {
                continue;
            }
            ensureHistoryWork(assignment.getStaff(), assignment.getWorkSchedule());
        }

        List<HistoryWork> allHistoryWorks = historyWorkRepository.findAll();
        boolean dirty = false;
        for (HistoryWork historyWork : allHistoryWorks) {
            if (historyWork.getWorkSchedule() == null
                    || historyWork.getWorkSchedule().getShift() == null
                    || historyWork.getWorkSchedule().getDate() == null) {
                continue;
            }

            AttendanceStatus nextStatus = determineStatus(
                    historyWork.getWorkSchedule(),
                    historyWork.getCheckinTime(),
                    historyWork.getCheckoutTime()
            );

            if (historyWork.getStatus() != nextStatus) {
                historyWork.setStatus(nextStatus);
                dirty = true;
            }
        }

        if (dirty) {
            historyWorkRepository.saveAll(allHistoryWorks);
        }
    }

    private HistoryWork ensureHistoryWork(Staff staff, WorkSchedule schedule) {
        Optional<HistoryWork> existing = historyWorkRepository.findByStaffIdAndWorkScheduleId(staff.getId(), schedule.getId());
        if (existing.isPresent()) {
            HistoryWork historyWork = existing.get();
            AttendanceStatus computedStatus = determineStatus(schedule, historyWork.getCheckinTime(), historyWork.getCheckoutTime());
            if (historyWork.getStatus() != computedStatus) {
                historyWork.setStatus(computedStatus);
                historyWorkRepository.save(historyWork);
            }
            return historyWork;
        }

        HistoryWork historyWork = new HistoryWork();
        historyWork.setId(generateId("HWK", 10));
        historyWork.setCheckinTime(null);
        historyWork.setCheckoutTime(null);
        historyWork.setStatus(determineStatus(schedule, null, null));
        historyWork.setStaff(staff);
        historyWork.setWorkSchedule(schedule);
        return historyWorkRepository.save(historyWork);
    }

    private AttendanceStatus determineStatus(WorkSchedule schedule, LocalDateTime checkinTime, LocalDateTime checkoutTime) {
        ScheduleWindow window = resolveWindow(schedule.getDate(), schedule.getShift());
        LocalDateTime now = LocalDateTime.now();

        if (checkinTime != null && checkoutTime != null) {
            return AttendanceStatus.COMPLETED;
        }
        if (checkinTime != null) {
            return now.isAfter(window.end().plusMinutes(15))
                    ? AttendanceStatus.MISSED
                    : AttendanceStatus.CHECKED_IN;
        }
        if (now.isBefore(window.start().minusMinutes(15))) {
            return AttendanceStatus.NOT_STARTED;
        }
        if (now.isAfter(window.end().plusMinutes(15))) {
            return AttendanceStatus.MISSED;
        }
        return AttendanceStatus.NOT_ATTENDED;
    }

    private List<AttendanceScheduleView> buildAttendanceSchedules(List<WorkSchedule> schedules,
                                                                  Map<String, List<WorkAssignment>> assignmentsByScheduleId,
                                                                  Map<String, List<HistoryWork>> historyByScheduleId) {
        return schedules.stream()
                .sorted(Comparator.comparing(WorkSchedule::getDate).reversed().thenComparing(ws -> ws.getShift().ordinal()))
                .map(schedule -> {
                    ScheduleWindow window = resolveWindow(schedule.getDate(), schedule.getShift());

                    List<WorkAssignment> assignments = assignmentsByScheduleId.getOrDefault(schedule.getId(), List.of()).stream()
                            .filter(item -> item.getEndAt() == null)
                            .sorted(Comparator.comparing(item -> fullNameOf(item.getStaff())))
                            .toList();

                    Map<String, HistoryWork> historyByStaffId = historyByScheduleId.getOrDefault(schedule.getId(), List.of()).stream()
                            .filter(item -> item.getStaff() != null && item.getStaff().getId() != null)
                            .collect(Collectors.toMap(
                                    item -> item.getStaff().getId(),
                                    item -> item,
                                    (left, right) -> left,
                                    LinkedHashMap::new
                            ));

                    List<AttendanceRowView> rows = assignments.stream()
                            .map(assignment -> {
                                Staff staff = assignment.getStaff();
                                HistoryWork history = staff == null ? null : historyByStaffId.get(staff.getId());
                                AttendanceStatus status = history != null
                                        ? history.getStatus()
                                        : determineStatus(schedule, null, null);

                                return new AttendanceRowView(
                                        staff != null ? staff.getId() : "",
                                        fullNameOf(staff),
                                        staff != null && staff.getUser() != null ? nullToDash(staff.getUser().getEmail()) : "-",
                                        status,
                                        status.getDisplayName(),
                                        formatDateTime(history != null ? history.getCheckinTime() : null),
                                        formatDateTime(history != null ? history.getCheckoutTime() : null),
                                        canCheckin(schedule, history),
                                        canCheckout(schedule, history)
                                );
                            })
                            .toList();

                    long completed = rows.stream()
                            .filter(item -> item.getStatus() == AttendanceStatus.COMPLETED)
                            .count();

                    long missed = rows.stream()
                            .filter(item -> item.getStatus() == AttendanceStatus.MISSED)
                            .count();

                    return new AttendanceScheduleView(
                            schedule.getId(),
                            schedule.getDate().format(DATE_FORMAT),
                            schedule.getShift().getDisplayName(),
                            formatRange(window.start(), window.end()),
                            rows,
                            completed,
                            missed,
                            schedule.getDate().isBefore(LocalDate.now())
                                    || LocalDateTime.now().isAfter(window.end().plusMinutes(15))
                    );
                })
                .toList();
    }

    private List<List<CalendarDayView>> buildCalendarWeeks(YearMonth yearMonth,
                                                           Map<LocalDate, List<ScheduleSummaryView>> scheduleSummariesByDate,
                                                           LocalDate selectedDate) {
        LocalDate firstDay = yearMonth.atDay(1);
        LocalDate lastDay = yearMonth.atEndOfMonth();
        LocalDate calendarStart = firstDay.with(DayOfWeek.MONDAY);
        LocalDate calendarEnd = lastDay.with(DayOfWeek.SUNDAY);

        List<List<CalendarDayView>> weeks = new ArrayList<>();
        LocalDate cursor = calendarStart;
        while (!cursor.isAfter(calendarEnd)) {
            List<CalendarDayView> week = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                LocalDate date = cursor;
                List<ScheduleSummaryView> schedules = scheduleSummariesByDate.getOrDefault(date, List.of());
                week.add(new CalendarDayView(
                        date.toString(),
                        String.valueOf(date.getDayOfMonth()),
                        date.equals(LocalDate.now()),
                        date.equals(selectedDate),
                        date.getMonth().equals(yearMonth.getMonth()),
                        !schedules.isEmpty(),
                        schedules,
                        capitalize(date.getDayOfWeek().getDisplayName(TextStyle.SHORT, VI_LOCALE))
                ));
                cursor = cursor.plusDays(1);
            }
            weeks.add(week);
        }
        return weeks;
    }

    private Map<String, Long> buildStatusCountMap(List<HistoryWork> historyWorks) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (AttendanceStatus status : AttendanceStatus.values()) {
            result.put(status.getDisplayName(), historyWorks.stream()
                    .filter(item -> item.getStatus() == status)
                    .count());
        }
        return result;
    }

    private boolean canCheckin(WorkSchedule schedule, HistoryWork history) {
        ScheduleWindow window = resolveWindow(schedule.getDate(), schedule.getShift());
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(window.start().minusMinutes(15)) || now.isAfter(window.end().plusMinutes(15))) {
            return false;
        }
        return history == null || history.getCheckinTime() == null;
    }

    private boolean canCheckout(WorkSchedule schedule, HistoryWork history) {
        if (history == null || history.getCheckinTime() == null || history.getCheckoutTime() != null) {
            return false;
        }
        ScheduleWindow window = resolveWindow(schedule.getDate(), schedule.getShift());
        LocalDateTime now = LocalDateTime.now();
        return !now.isBefore(window.end().minusMinutes(15))
                && !now.isAfter(window.end().plusMinutes(15));
    }

    private ScheduleWindow resolveWindow(LocalDate date, Shift shift) {
        LocalDateTime start;
        LocalDateTime end;

        switch (shift) {
            case NIGHT -> {
                start = date.atStartOfDay();
                end = date.atTime(6, 0);
            }
            case MORNING -> {
                start = date.atTime(6, 0);
                end = date.atTime(12, 0);
            }
            case AFTERNOON -> {
                start = date.atTime(12, 0);
                end = date.atTime(18, 0);
            }
            case EVENING -> {
                start = date.atTime(18, 0);
                end = date.plusDays(1).atStartOfDay();
            }
            default -> throw new IllegalStateException("Shift không hợp lệ: " + shift);
        }

        return new ScheduleWindow(start, end);
    }

    private String redirectToSchedule(WorkSchedule schedule, String scheduleId) {
        return "redirect:/manager/hr/dashboard?month=" + YearMonth.from(schedule.getDate()).format(MONTH_PARAM_FORMAT)
                + "&selectedDate=" + schedule.getDate()
                + "&attendanceScheduleId=" + scheduleId
                + "#schedule-section";
    }

    private String redirectToAttendance(WorkSchedule schedule, String scheduleId) {
        return "redirect:/manager/hr/dashboard?month=" + YearMonth.from(schedule.getDate()).format(MONTH_PARAM_FORMAT)
                + "&selectedDate=" + schedule.getDate()
                + "&attendanceScheduleId=" + scheduleId
                + "#attendance-section";
    }

    private YearMonth parseMonth(String month) {
        try {
            return month == null || month.isBlank()
                    ? YearMonth.now()
                    : YearMonth.parse(month, MONTH_PARAM_FORMAT);
        } catch (Exception ex) {
            return YearMonth.now();
        }
    }

    private LocalDate parseSelectedDate(String selectedDate, YearMonth fallbackMonth) {
        try {
            if (selectedDate != null && !selectedDate.isBlank()) {
                return LocalDate.parse(selectedDate);
            }
        } catch (Exception ignored) {
        }
        return fallbackMonth.atDay(1);
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? "-" : value.format(DATETIME_FORMAT);
    }

    private String formatRange(LocalDateTime start, LocalDateTime end) {
        return start.toLocalTime().toString() + " - " + end.toLocalTime().toString();
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private String generateId(String prefix, int length) {
        String source = String.valueOf(System.nanoTime());
        String digits = source.length() >= (length - prefix.length())
                ? source.substring(source.length() - (length - prefix.length()))
                : String.format("%0" + (length - prefix.length()) + "d", Long.parseLong(source));
        return prefix + digits;
    }

    private String fullNameOf(Staff staff) {
        if (staff == null || staff.getUser() == null) {
            return "N/A";
        }
        String lastName = staff.getUser().getLastName() == null ? "" : staff.getUser().getLastName().trim();
        String firstName = staff.getUser().getFirstName() == null ? "" : staff.getUser().getFirstName().trim();
        String fullName = (lastName + " " + firstName).trim();
        return fullName.isBlank() ? "N/A" : fullName;
    }

    private String shortNameOf(Staff staff) {
        String fullName = fullNameOf(staff);
        if ("N/A".equals(fullName)) {
            return fullName;
        }
        String[] parts = fullName.split("\\s+");
        if (parts.length == 1) {
            return parts[0];
        }
        return parts[parts.length - 1];
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.substring(0, 1).toUpperCase(VI_LOCALE) + value.substring(1);
    }

    private record ScheduleWindow(LocalDateTime start, LocalDateTime end) { }

    public static class StaffMiniView {
        private final String assignmentId;
        private final String staffId;
        private final String shortName;
        private final String fullName;
        private final String note;

        public StaffMiniView(String assignmentId, String staffId, String shortName, String fullName, String note) {
            this.assignmentId = assignmentId;
            this.staffId = staffId;
            this.shortName = shortName;
            this.fullName = fullName;
            this.note = note;
        }

        public String getAssignmentId() { return assignmentId; }
        public String getStaffId() { return staffId; }
        public String getShortName() { return shortName; }
        public String getFullName() { return fullName; }
        public String getNote() { return note; }
    }

    public static class ScheduleSummaryView {
        private final String scheduleId;
        private final Shift shift;
        private final String shiftName;
        private final String startTimeText;
        private final String rangeText;
        private final List<StaffMiniView> staffs;
        private final String badgeClass;
        private final boolean today;

        public ScheduleSummaryView(String scheduleId, Shift shift, String shiftName, String startTimeText,
                                   String rangeText, List<StaffMiniView> staffs, String badgeClass, boolean today) {
            this.scheduleId = scheduleId;
            this.shift = shift;
            this.shiftName = shiftName;
            this.startTimeText = startTimeText;
            this.rangeText = rangeText;
            this.staffs = staffs;
            this.badgeClass = badgeClass;
            this.today = today;
        }

        public String getScheduleId() { return scheduleId; }
        public Shift getShift() { return shift; }
        public String getShiftName() { return shiftName; }
        public String getStartTimeText() { return startTimeText; }
        public String getRangeText() { return rangeText; }
        public List<StaffMiniView> getStaffs() { return staffs; }
        public String getBadgeClass() { return badgeClass; }
        public boolean isToday() { return today; }
    }

    public static class CalendarDayView {
        private final String value;
        private final String dayNumber;
        private final boolean today;
        private final boolean selected;
        private final boolean inCurrentMonth;
        private final boolean hasSchedule;
        private final List<ScheduleSummaryView> schedules;
        private final String dayName;

        public CalendarDayView(String value, String dayNumber, boolean today, boolean selected,
                               boolean inCurrentMonth, boolean hasSchedule, List<ScheduleSummaryView> schedules, String dayName) {
            this.value = value;
            this.dayNumber = dayNumber;
            this.today = today;
            this.selected = selected;
            this.inCurrentMonth = inCurrentMonth;
            this.hasSchedule = hasSchedule;
            this.schedules = schedules;
            this.dayName = dayName;
        }

        public String getValue() { return value; }
        public String getDayNumber() { return dayNumber; }
        public boolean isToday() { return today; }
        public boolean isSelected() { return selected; }
        public boolean isInCurrentMonth() { return inCurrentMonth; }
        public boolean isHasSchedule() { return hasSchedule; }
        public List<ScheduleSummaryView> getSchedules() { return schedules; }
        public String getDayName() { return dayName; }
    }

    public static class AttendanceRowView {
        private final String staffId;
        private final String staffName;
        private final String email;
        private final AttendanceStatus status;
        private final String statusText;
        private final String checkinText;
        private final String checkoutText;
        private final boolean canCheckin;
        private final boolean canCheckout;

        public AttendanceRowView(String staffId, String staffName, String email, AttendanceStatus status, String statusText,
                                 String checkinText, String checkoutText, boolean canCheckin, boolean canCheckout) {
            this.staffId = staffId;
            this.staffName = staffName;
            this.email = email;
            this.status = status;
            this.statusText = statusText;
            this.checkinText = checkinText;
            this.checkoutText = checkoutText;
            this.canCheckin = canCheckin;
            this.canCheckout = canCheckout;
        }

        public String getStaffId() { return staffId; }
        public String getStaffName() { return staffName; }
        public String getEmail() { return email; }
        public AttendanceStatus getStatus() { return status; }
        public String getStatusText() { return statusText; }
        public String getCheckinText() { return checkinText; }
        public String getCheckoutText() { return checkoutText; }
        public boolean isCanCheckin() { return canCheckin; }
        public boolean isCanCheckout() { return canCheckout; }
    }

    public static class AttendanceScheduleView {
        private final String scheduleId;
        private final String dateText;
        private final String shiftName;
        private final String rangeText;
        private final List<AttendanceRowView> rows;
        private final long completedCount;
        private final long missedCount;
        private final boolean inPast;

        public AttendanceScheduleView(String scheduleId, String dateText, String shiftName, String rangeText,
                                      List<AttendanceRowView> rows, long completedCount, long missedCount, boolean inPast) {
            this.scheduleId = scheduleId;
            this.dateText = dateText;
            this.shiftName = shiftName;
            this.rangeText = rangeText;
            this.rows = rows;
            this.completedCount = completedCount;
            this.missedCount = missedCount;
            this.inPast = inPast;
        }

        public String getScheduleId() { return scheduleId; }
        public String getDateText() { return dateText; }
        public String getShiftName() { return shiftName; }
        public String getRangeText() { return rangeText; }
        public List<AttendanceRowView> getRows() { return rows; }
        public long getCompletedCount() { return completedCount; }
        public long getMissedCount() { return missedCount; }
        public boolean isInPast() { return inPast; }
    }
}