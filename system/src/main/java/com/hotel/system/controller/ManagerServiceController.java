package com.hotel.system.controller;

import com.hotel.system.entity.Manager;
import com.hotel.system.entity.Service;
import com.hotel.system.entity.ServiceUsage;
import com.hotel.system.entity.Users;
import com.hotel.system.entity.enums.ServiceStatus;
import com.hotel.system.repository.ServiceRepository;
import com.hotel.system.repository.ServiceUsageRepository;
import com.hotel.system.service.ManagerAccessService;
import com.hotel.system.repository.MediaStorageDirectory;
import com.hotel.system.service.MediaStorageService;
import com.hotel.system.util.StoredMedia;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@RequestMapping("/manager/service")
public class ManagerServiceController {

    private final ServiceRepository serviceRepository;
    private final ServiceUsageRepository serviceUsageRepository;
    private final ManagerAccessService managerAccessService;
    private final MediaStorageService mediaStorageService;

    public ManagerServiceController(ServiceRepository serviceRepository,
                                    ServiceUsageRepository serviceUsageRepository,
                                    ManagerAccessService managerAccessService,
                                    MediaStorageService mediaStorageService) {
        this.serviceRepository = serviceRepository;
        this.serviceUsageRepository = serviceUsageRepository;
        this.managerAccessService = managerAccessService;
        this.mediaStorageService = mediaStorageService;
    }

    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(required = false) String editService,
                            HttpSession session,
                            Model model,
                            RedirectAttributes redirectAttributes) {

        Users currentUser = managerAccessService.getLoggedInManagerUser(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập bằng tài khoản quản lý.");
            return "redirect:/login";
        }

        if (!managerAccessService.hasAccess(session, ManagerAccessService.ManagerModule.SERVICE)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền truy cập phân hệ quản lý dịch vụ.");
            return "redirect:/manager";
        }

        Manager manager = managerAccessService.getLoggedInManager(session);
        if (manager == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy hồ sơ quản lý tương ứng.");
            return "redirect:/login";
        }

        List<Service> services = serviceRepository.findAllByOrderByCreateDateDesc();
        List<ServiceUsage> serviceUsages = serviceUsageRepository.findAllByOrderByTimeDesc();

        Service editingService = null;
        if (editService != null && !editService.isBlank()) {
            editingService = serviceRepository.findById(editService).orElse(null);
        }

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("currentManager", manager);
        model.addAttribute("services", services);
        model.addAttribute("serviceUsages", serviceUsages);
        model.addAttribute("editingService", editingService);
        model.addAttribute("serviceStatuses", ServiceStatus.values());

        model.addAttribute("serviceCount", services.size());
        model.addAttribute("activeServiceCount", services.stream().filter(item -> item.getStatus() == ServiceStatus.ACTIVE).count());
        model.addAttribute("suspendedServiceCount", services.stream().filter(item -> item.getStatus() == ServiceStatus.SUSPENDED).count());
        model.addAttribute("serviceUsageCount", serviceUsages.size());
        model.addAttribute("popularServiceSummary", buildPopularServiceSummary(services, serviceUsages));
        model.addAttribute("revenueByService", buildRevenueByService(services, serviceUsages));

        return "manager/HotelServiceManager";
    }

    @PostMapping("/services/save")
    @Transactional
    public String saveService(@RequestParam(required = false) String serviceId,
                              @RequestParam String name,
                              @RequestParam(required = false) String description,
                              @RequestParam String unit,
                              @RequestParam Double basePrice,
                              @RequestParam(required = false) MultipartFile imageFile,
                              @RequestParam ServiceStatus status,
                              HttpSession session,
                              RedirectAttributes redirectAttributes) {

        if (!managerAccessService.hasAccess(session, ManagerAccessService.ManagerModule.SERVICE)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền thực hiện chức năng này.");
            return "redirect:/manager";
        }

        String trimmedName = safeTrim(name);
        String trimmedDescription = safeTrim(description);
        String trimmedUnit = safeTrim(unit);

        if (trimmedName.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Tên dịch vụ không được để trống.");
            return "redirect:/manager/service/dashboard#service-section";
        }

        if (trimmedUnit.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Đơn vị tính không được để trống.");
            return "redirect:/manager/service/dashboard#service-section";
        }

        if (basePrice == null || basePrice < 0) {
            redirectAttributes.addFlashAttribute("error", "Giá cơ bản của dịch vụ không hợp lệ.");
            return "redirect:/manager/service/dashboard#service-section";
        }

        boolean duplicated = serviceRepository.findAllByOrderByCreateDateDesc().stream()
                .anyMatch(item -> item.getName() != null
                        && item.getName().equalsIgnoreCase(trimmedName)
                        && (serviceId == null || serviceId.isBlank() || !item.getId().equals(serviceId)));

        if (duplicated) {
            redirectAttributes.addFlashAttribute("error", "Tên dịch vụ đã tồn tại.");
            return "redirect:/manager/service/dashboard#service-section";
        }

        LocalDateTime now = LocalDateTime.now();
        boolean isCreate = serviceId == null || serviceId.isBlank();
        Service service;

        if (isCreate) {
            service = new Service();
            service.setId(generateId("SER", 10));
            service.setCreateDate(now);
        } else {
            service = serviceRepository.findById(serviceId).orElse(null);
            if (service == null) {
                redirectAttributes.addFlashAttribute("error", "Không tìm thấy dịch vụ cần cập nhật.");
                return "redirect:/manager/service/dashboard#service-section";
            }
        }

        service.setName(trimmedName);
        service.setDescription(trimmedDescription.isBlank() ? null : trimmedDescription);
        service.setUnit(trimmedUnit);
        service.setBasePrice(basePrice);
        service.setStatus(status);
        service.setUpdateDate(now);

        if (imageFile != null && !imageFile.isEmpty()) {
            try {
                String previousImagePath = service.getImagePath();
                StoredMedia storedMedia = mediaStorageService.storeImage(imageFile, MediaStorageDirectory.SERVICES, service.getId());
                service.setImagePath(storedMedia.publicPath());
                if (previousImagePath != null && !previousImagePath.equals(storedMedia.publicPath())) {
                    mediaStorageService.deleteByPublicPath(previousImagePath);
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                redirectAttributes.addFlashAttribute("error", e.getMessage());
                return "redirect:/manager/service/dashboard#service-section";
            }
        }

        serviceRepository.save(service);

        redirectAttributes.addFlashAttribute("message", isCreate ? "Đã thêm dịch vụ mới." : "Đã cập nhật dịch vụ.");
        return "redirect:/manager/service/dashboard#service-section";
    }

    @PostMapping("/services/{serviceId}/delete")
    @Transactional
    public String deleteService(@PathVariable String serviceId,
                                HttpSession session,
                                RedirectAttributes redirectAttributes) {

        if (!managerAccessService.hasAccess(session, ManagerAccessService.ManagerModule.SERVICE)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền thực hiện chức năng này.");
            return "redirect:/manager";
        }

        Service service = serviceRepository.findById(serviceId).orElse(null);
        if (service == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy dịch vụ cần xóa.");
            return "redirect:/manager/service/dashboard#service-section";
        }

        long linkedUsageCount = serviceUsageRepository.countByServiceId(serviceId);
        if (linkedUsageCount > 0) {
            redirectAttributes.addFlashAttribute("error", "Không thể xóa dịch vụ khi đã có lịch sử sử dụng liên quan.");
            return "redirect:/manager/service/dashboard#service-section";
        }

        mediaStorageService.deleteByPublicPath(service.getImagePath());
        serviceRepository.deleteById(serviceId);
        redirectAttributes.addFlashAttribute("message", "Đã xóa dịch vụ.");
        return "redirect:/manager/service/dashboard#service-section";
    }

    private Map<String, Long> buildPopularServiceSummary(List<Service> services, List<ServiceUsage> usages) {
        Map<String, Long> result = new LinkedHashMap<>();

        for (Service service : services) {
            long totalCount = usages.stream()
                    .filter(item -> item.getService() != null && service.getId().equals(item.getService().getId()))
                    .mapToLong(item -> item.getCount() == null ? 0 : item.getCount())
                    .sum();

            result.put(service.getName(), totalCount);
        }

        return result;
    }

    private Map<String, Double> buildRevenueByService(List<Service> services, List<ServiceUsage> usages) {
        Map<String, Double> result = new LinkedHashMap<>();

        for (Service service : services) {
            double revenue = usages.stream()
                    .filter(item -> item.getService() != null && service.getId().equals(item.getService().getId()))
                    .mapToDouble(item -> {
                        int count = item.getCount() == null ? 0 : item.getCount();
                        double basePrice = service.getBasePrice() == null ? 0.0 : service.getBasePrice();
                        return count * basePrice;
                    })
                    .sum();

            result.put(service.getName(), revenue);
        }

        return result;
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