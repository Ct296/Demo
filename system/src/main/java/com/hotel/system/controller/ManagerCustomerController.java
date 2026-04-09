package com.hotel.system.controller;

import com.hotel.system.entity.Customer;
import com.hotel.system.entity.Manager;
import com.hotel.system.entity.Review;
import com.hotel.system.entity.TierCustomer;
import com.hotel.system.entity.TierHistory;
import com.hotel.system.entity.Users;
import com.hotel.system.repository.BillRepository;
import com.hotel.system.repository.CustomerRepository;
import com.hotel.system.repository.ReviewRepository;
import com.hotel.system.repository.TierCustomerRepository;
import com.hotel.system.repository.TierHistoryRepository;
import com.hotel.system.service.ManagerAccessService;
import com.hotel.system.service.ai.ReviewAiExportService;
import com.hotel.system.service.ai.ReviewAiDashboardService;

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


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@RequestMapping("/manager/customer")
public class ManagerCustomerController {

    private final CustomerRepository customerRepository;
    private final TierCustomerRepository tierCustomerRepository;
    private final TierHistoryRepository tierHistoryRepository;
    private final ReviewRepository reviewRepository;
    private final BillRepository billRepository;
    private final ManagerAccessService managerAccessService;
    private final ReviewAiExportService reviewAiExportService;
    private final ReviewAiDashboardService reviewAiDashboardService;

    public ManagerCustomerController(CustomerRepository customerRepository,
                                     TierCustomerRepository tierCustomerRepository,
                                     TierHistoryRepository tierHistoryRepository,
                                     ReviewRepository reviewRepository,
                                     BillRepository billRepository,
                                     ManagerAccessService managerAccessService, ReviewAiExportService reviewAiExportService, ReviewAiDashboardService reviewAiDashboardService) {
        this.customerRepository = customerRepository;
        this.tierCustomerRepository = tierCustomerRepository;
        this.tierHistoryRepository = tierHistoryRepository;
        this.reviewRepository = reviewRepository;
        this.billRepository = billRepository;
        this.managerAccessService = managerAccessService;
        this.reviewAiExportService = reviewAiExportService;
        this.reviewAiDashboardService = reviewAiDashboardService;
    }

    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(required = false) String editTierCustomer,
                            HttpSession session,
                            Model model,
                            RedirectAttributes redirectAttributes) {

        Users currentUser = managerAccessService.getLoggedInManagerUser(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập bằng tài khoản quản lý.");
            return "redirect:/login";
        }

        if (!managerAccessService.hasAccess(session, ManagerAccessService.ManagerModule.CUSTOMER)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền truy cập phân hệ quản lý khách hàng.");
            return "redirect:/manager";
        }

        Manager manager = managerAccessService.getLoggedInManager(session);
        if (manager == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy hồ sơ quản lý tương ứng.");
            return "redirect:/login";
        }

        List<Customer> customers = customerRepository.findAllByOrderByUserCreateDateDesc();
        List<TierCustomer> tierCustomers = tierCustomerRepository.findAllByOrderByConditionAsc();
        List<TierHistory> tierHistories = tierHistoryRepository.findAllByOrderByStartDateDesc();
        List<Review> reviews = reviewRepository.findAllByOrderByUpdateDateDesc();

        TierCustomer editingTierCustomer = null;
        if (editTierCustomer != null && !editTierCustomer.isBlank()) {
            editingTierCustomer = tierCustomerRepository.findById(editTierCustomer).orElse(null);
        }

        Map<String, TierCustomer> currentTierByCustomerId = buildCurrentTierByCustomerId(customers);
        Map<String, Double> totalSpendingByCustomerId = buildTotalSpendingByCustomerId(customers);

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("currentManager", manager);
        model.addAttribute("customers", customers);
        model.addAttribute("tierCustomers", tierCustomers);
        model.addAttribute("tierHistories", tierHistories);
        model.addAttribute("reviews", reviews);
        model.addAttribute("editingTierCustomer", editingTierCustomer);

        model.addAttribute("customerCount", customers.size());
        model.addAttribute("tierCustomerCount", tierCustomers.size());
        model.addAttribute("activeMemberCount", tierHistories.stream().filter(item -> item.getEndDate() == null).count());
        model.addAttribute("averageReviewRate", calculateAverageReviewRate(reviews));
        model.addAttribute("customerTierSummary", buildCustomerTierSummary(tierCustomers, tierHistories));
        model.addAttribute("reviewRateSummary", buildReviewRateSummary(reviews));
        model.addAttribute("currentTierByCustomerId", currentTierByCustomerId);
        model.addAttribute("totalSpendingByCustomerId", totalSpendingByCustomerId);

        ReviewAiDashboardService.ReviewAiDashboardSummary aiSummary = reviewAiDashboardService.buildSummary();

        model.addAttribute("aiExportFile", aiSummary.exportFile());
        model.addAttribute("aiTotalReviews", aiSummary.totalReviews());
        model.addAttribute("aiInserted",
                model.containsAttribute("aiLastInserted") ? model.getAttribute("aiLastInserted") : 0);
        model.addAttribute("aiUpdated",
                model.containsAttribute("aiLastUpdated") ? model.getAttribute("aiLastUpdated") : 0);
        model.addAttribute("aiSkipped",
                model.containsAttribute("aiLastSkipped") ? model.getAttribute("aiLastSkipped") : 0);
        model.addAttribute("aiExecutedAt",
                model.containsAttribute("aiLastExecutedAt") ? model.getAttribute("aiLastExecutedAt") : aiSummary.latestExecutedAt());
        model.addAttribute("aiPositiveCount", aiSummary.positiveCount());
        model.addAttribute("aiNegativeCount", aiSummary.negativeCount());
        model.addAttribute("aiNeutralCount", aiSummary.neutralCount());
        model.addAttribute("aiNegativeAspectSummary", aiSummary.negativeAspectSummary());
        model.addAttribute("aiPositiveAspectSummary", aiSummary.positiveAspectSummary());
        return "manager/CustomerManager";
    }

    @PostMapping("/tier-customers/save")
    @Transactional
    public String saveTierCustomer(@RequestParam(required = false) String tierCustomerId,
                                   @RequestParam String name,
                                   @RequestParam Double condition,
                                   @RequestParam BigDecimal discount,
                                   @RequestParam(required = false) String benefit,
                                   HttpSession session,
                                   RedirectAttributes redirectAttributes) {

        if (!managerAccessService.hasAccess(session, ManagerAccessService.ManagerModule.CUSTOMER)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền thực hiện chức năng này.");
            return "redirect:/manager";
        }

        String trimmedName = safeTrim(name);
        String trimmedBenefit = safeTrim(benefit);

        if (trimmedName.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Tên hạng khách hàng không được để trống.");
            return "redirect:/manager/customer/dashboard#tier-customer-section";
        }

        if (condition == null || condition < 0) {
            redirectAttributes.addFlashAttribute("error", "Điều kiện chi tiêu không hợp lệ.");
            return "redirect:/manager/customer/dashboard#tier-customer-section";
        }

        if (discount == null || discount.compareTo(BigDecimal.ZERO) < 0 || discount.compareTo(new BigDecimal("100")) > 0) {
            redirectAttributes.addFlashAttribute("error", "Mức giảm giá phải nằm trong khoảng 0 đến 100.");
            return "redirect:/manager/customer/dashboard#tier-customer-section";
        }

        boolean duplicated = tierCustomersNameDuplicated(trimmedName, tierCustomerId);
        if (duplicated) {
            redirectAttributes.addFlashAttribute("error", "Tên hạng khách hàng đã tồn tại.");
            return "redirect:/manager/customer/dashboard#tier-customer-section";
        }

        boolean isCreate = tierCustomerId == null || tierCustomerId.isBlank();
        TierCustomer tierCustomer;

        if (isCreate) {
            tierCustomer = new TierCustomer();
            tierCustomer.setId(generateId("TIC", 10));
        } else {
            tierCustomer = tierCustomerRepository.findById(tierCustomerId).orElse(null);
            if (tierCustomer == null) {
                redirectAttributes.addFlashAttribute("error", "Không tìm thấy hạng khách hàng cần cập nhật.");
                return "redirect:/manager/customer/dashboard#tier-customer-section";
            }
        }

        tierCustomer.setName(trimmedName);
        tierCustomer.setCondition(condition);
        tierCustomer.setBenefit(trimmedBenefit.isBlank() ? null : trimmedBenefit);
        tierCustomer.setDiscount(discount.setScale(2, RoundingMode.HALF_UP));

        tierCustomerRepository.save(tierCustomer);

        redirectAttributes.addFlashAttribute("message", isCreate ? "Đã thêm hạng khách hàng mới." : "Đã cập nhật hạng khách hàng.");
        return "redirect:/manager/customer/dashboard#tier-customer-section";
    }

    @PostMapping("/customers/{customerId}/change-tier")
    @Transactional
    public String changeCustomerTier(@PathVariable String customerId,
                                     HttpSession session,
                                     RedirectAttributes redirectAttributes) {

        if (!managerAccessService.hasAccess(session, ManagerAccessService.ManagerModule.CUSTOMER)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền thực hiện chức năng này.");
            return "redirect:/manager";
        }

        if (!customerRepository.existsById(customerId)) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy khách hàng cần xử lý.");
            return "redirect:/manager/customer/dashboard#customer-section";
        }

        redirectAttributes.addFlashAttribute(
                "error",
                "Không được đổi hạng khách hàng thủ công trong màn hình quản lý. Hệ thống sẽ tự tính theo tổng bill; nếu thật sự cần can thiệp, admin phải xử lý trực tiếp bằng SQL."
        );
        return "redirect:/manager/customer/dashboard#customer-section";

    }

    @PostMapping("/ai/export")
    public String exportReviewAiAnalysis(RedirectAttributes redirectAttributes) {
        try {
            ReviewAiExportService.ReviewAiExportSummary summary =
                    reviewAiExportService.exportLatestReviews();

            redirectAttributes.addFlashAttribute("message",
                    "Da phan tich review. Them moi: " + summary.inserted()
                            + ", cap nhat: " + summary.updated()
                            + ", bo qua: " + summary.skipped());

            redirectAttributes.addFlashAttribute("aiLastInserted", summary.inserted());
            redirectAttributes.addFlashAttribute("aiLastUpdated", summary.updated());
            redirectAttributes.addFlashAttribute("aiLastSkipped", summary.skipped());
            redirectAttributes.addFlashAttribute("aiLastExecutedAt", summary.executedAt());

        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Loi AI export: " + e.getMessage());
        }
        return "redirect:/manager/customer/dashboard#ai-section";
    }

    private BigDecimal calculateAverageReviewRate(List<Review> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        double average = reviews.stream()
                .filter(item -> item.getRate() != null)
                .mapToInt(Review::getRate)
                .average()
                .orElse(0.0);

        return BigDecimal.valueOf(average).setScale(2, RoundingMode.HALF_UP);
    }

    private Map<String, Long> buildCustomerTierSummary(List<TierCustomer> tierCustomers, List<TierHistory> tierHistories) {
        Map<String, Long> result = new LinkedHashMap<>();

        for (TierCustomer tierCustomer : tierCustomers) {
            long count = tierHistories.stream()
                    .filter(item -> item.getEndDate() == null)
                    .filter(item -> item.getTierCustomer() != null && tierCustomer.getId().equals(item.getTierCustomer().getId()))
                    .count();
            result.put(tierCustomer.getName(), count);
        }

        return result;
    }

    private Map<Integer, Long> buildReviewRateSummary(List<Review> reviews) {
        Map<Integer, Long> result = new LinkedHashMap<>();
        for (int rate = 5; rate >= 1; rate--) {
            int currentRate = rate;
            long count = reviews.stream()
                    .filter(item -> item.getRate() != null && item.getRate() == currentRate)
                    .count();
            result.put(rate, count);
        }
        return result;
    }

    private Map<String, TierCustomer> buildCurrentTierByCustomerId(List<Customer> customers) {
        Map<String, TierCustomer> result = new LinkedHashMap<>();
        for (Customer customer : customers) {
            TierHistory currentHistory = findTierHistoryEffectiveAt(customer.getId(), LocalDateTime.now());
            if (currentHistory != null) {
                result.put(customer.getId(), currentHistory.getTierCustomer());
            }
        }
        return result;
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

    private Map<String, Double> buildTotalSpendingByCustomerId(List<Customer> customers) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (Customer customer : customers) {
            result.put(customer.getId(), 0.0);
        }

        for (Object[] row : billRepository.sumTotalAmountGroupByCustomerId()) {
            if (row == null || row.length < 2 || row[0] == null) {
                continue;
            }

            String customerId = String.valueOf(row[0]);
            double totalSpending = row[1] instanceof Number ? ((Number) row[1]).doubleValue() : 0.0;
            result.put(customerId, totalSpending);
        }

        return result;
    }

    private boolean tierCustomersNameDuplicated(String name, String currentTierId) {
        return tierCustomerRepository.findAllByOrderByConditionAsc().stream()
                .anyMatch(item -> item.getName() != null
                        && item.getName().equalsIgnoreCase(name)
                        && (currentTierId == null || currentTierId.isBlank() || !item.getId().equals(currentTierId)));
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
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
