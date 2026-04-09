package com.hotel.system.controller;

import com.hotel.system.entity.AppliedPeriod;
import com.hotel.system.entity.Manager;
import com.hotel.system.entity.PriceRate;
import com.hotel.system.entity.Room;
import com.hotel.system.entity.RoomImage;
import com.hotel.system.entity.RoomType;
import com.hotel.system.entity.Users;
import com.hotel.system.entity.enums.RoomStatus;
import com.hotel.system.repository.AppliedPeriodRepository;
import com.hotel.system.repository.MediaStorageDirectory;
import com.hotel.system.repository.PriceRateRepository;
import com.hotel.system.repository.RoomImageRepository;
import com.hotel.system.repository.RoomRepository;
import com.hotel.system.repository.RoomTypeRepository;
import com.hotel.system.service.ManagerAccessService;
import com.hotel.system.service.MediaStorageService;
import com.hotel.system.util.MediaViewSupport;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/manager/room-price")
public class ManagerRoomPriceController {

    private final RoomTypeRepository roomTypeRepository;
    private final RoomRepository roomRepository;
    private final RoomImageRepository roomImageRepository;
    private final PriceRateRepository priceRateRepository;
    private final AppliedPeriodRepository appliedPeriodRepository;
    private final ManagerAccessService managerAccessService;
    private final MediaStorageService mediaStorageService;
    private final MediaViewSupport mediaViewSupport;

    public ManagerRoomPriceController(RoomTypeRepository roomTypeRepository,
                                      RoomRepository roomRepository,
                                      RoomImageRepository roomImageRepository,
                                      PriceRateRepository priceRateRepository,
                                      AppliedPeriodRepository appliedPeriodRepository,
                                      ManagerAccessService managerAccessService,
                                      MediaStorageService mediaStorageService,
                                      MediaViewSupport mediaViewSupport) {
        this.roomTypeRepository = roomTypeRepository;
        this.roomRepository = roomRepository;
        this.roomImageRepository = roomImageRepository;
        this.priceRateRepository = priceRateRepository;
        this.appliedPeriodRepository = appliedPeriodRepository;
        this.managerAccessService = managerAccessService;
        this.mediaStorageService = mediaStorageService;
        this.mediaViewSupport = mediaViewSupport;
    }

    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(required = false) String editRoomType,
                            @RequestParam(required = false) String editRoom,
                            @RequestParam(required = false) String editPriceRate,
                            @RequestParam(required = false) String editAppliedPeriod,
                            HttpSession session,
                            Model model,
                            RedirectAttributes redirectAttributes) {

        Users currentUser = managerAccessService.getLoggedInManagerUser(session);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("error", "Vui lòng đăng nhập bằng tài khoản quản lý.");
            return "redirect:/login";
        }

        if (!managerAccessService.hasAccess(session, ManagerAccessService.ManagerModule.ROOM_PRICE)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền truy cập phân hệ quản lý phòng và giá.");
            return "redirect:/manager";
        }

        Manager manager = managerAccessService.getLoggedInManager(session);
        if (manager == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy hồ sơ quản lý tương ứng.");
            return "redirect:/login";
        }

        List<RoomType> roomTypes = roomTypeRepository.findAllByOrderByNameAsc();
        List<Room> rooms = roomRepository.findAllByOrderByNameAsc();
        List<PriceRate> priceRates = priceRateRepository.findAllByOrderByCreateDateDesc();
        List<AppliedPeriod> appliedPeriods = appliedPeriodRepository.findAllByOrderByStartDateDesc();

        RoomType editingRoomType = null;
        Room editingRoom = null;

        if (editRoomType != null && !editRoomType.isBlank()) {
            editingRoomType = roomTypeRepository.findById(editRoomType).orElse(null);
        }
        if (editRoom != null && !editRoom.isBlank()) {
            editingRoom = roomRepository.findById(editRoom).orElse(null);
        }

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("currentManager", manager);
        model.addAttribute("roomTypes", roomTypes);
        model.addAttribute("rooms", rooms);
        model.addAttribute("priceRates", priceRates);
        model.addAttribute("appliedPeriods", appliedPeriods);

        model.addAttribute("editingRoomType", editingRoomType);
        model.addAttribute("editingRoom", editingRoom);
        model.addAttribute("editingRoomPrimaryImage", getPrimaryImage(editingRoom));
        model.addAttribute("editingRoomSecondaryImages", getSecondaryImages(editingRoom));

        model.addAttribute("roomStatuses", RoomStatus.values());

        model.addAttribute("editingPriceRate", null);
        model.addAttribute("editingAppliedPeriod", null);

        model.addAttribute("roomTypeCount", roomTypes.size());
        model.addAttribute("roomCount", rooms.size());
        model.addAttribute("priceRateCount", priceRates.size());
        model.addAttribute(
                "activeAppliedPeriodCount",
                appliedPeriodRepository.countByStartDateLessThanEqualAndEndDateGreaterThanEqual(LocalDateTime.now(), LocalDateTime.now())
        );

        model.addAttribute("roomCountByType", buildRoomCountByType(roomTypes, rooms));
        model.addAttribute("roomStatusByType", buildRoomStatusByType(roomTypes, rooms));

        return "manager/RoomPriceManager";
    }

    @PostMapping("/room-types/save")
    @Transactional
    public String saveRoomType(@RequestParam(required = false) String roomTypeId,
                               @RequestParam String name,
                               @RequestParam Integer maxCustomers,
                               @RequestParam BigDecimal area,
                               @RequestParam Double basePrice,
                               @RequestParam Double depositPercent,
                               @RequestParam(required = false) String description,
                               HttpSession session,
                               RedirectAttributes redirectAttributes) {

        if (!managerAccessService.hasAccess(session, ManagerAccessService.ManagerModule.ROOM_PRICE)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền thực hiện chức năng này.");
            return "redirect:/manager";
        }

        String trimmedName = safeTrim(name);
        String trimmedDescription = safeTrim(description);

        if (trimmedName.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Tên loại phòng không được để trống.");
            return "redirect:/manager/room-price/dashboard#room-type-section";
        }

        if (maxCustomers == null || maxCustomers < 1) {
            redirectAttributes.addFlashAttribute("error", "Sức chứa tối đa phải lớn hơn hoặc bằng 1.");
            return "redirect:/manager/room-price/dashboard#room-type-section";
        }

        if (area == null || area.compareTo(BigDecimal.ZERO) <= 0) {
            redirectAttributes.addFlashAttribute("error", "Diện tích phòng phải lớn hơn 0.");
            return "redirect:/manager/room-price/dashboard#room-type-section";
        }

        if (basePrice == null || basePrice < 0) {
            redirectAttributes.addFlashAttribute("error", "Giá cơ bản không hợp lệ.");
            return "redirect:/manager/room-price/dashboard#room-type-section";
        }

        if (depositPercent == null || depositPercent < 0 || depositPercent > 100) {
            redirectAttributes.addFlashAttribute("error", "Phần trăm tiền cọc phải từ 0 đến 100.");
            return "redirect:/manager/room-price/dashboard#room-type-section";
        }

        RoomType existingByName = roomTypeRepository.findByName(trimmedName);
        if (existingByName != null
                && (roomTypeId == null || roomTypeId.isBlank() || !existingByName.getId().equals(roomTypeId))) {
            redirectAttributes.addFlashAttribute("error", "Tên loại phòng đã tồn tại.");
            return "redirect:/manager/room-price/dashboard#room-type-section";
        }

        LocalDateTime now = LocalDateTime.now();
        boolean isCreate = roomTypeId == null || roomTypeId.isBlank();
        RoomType roomType;

        if (isCreate) {
            roomType = new RoomType();
            roomType.setId(generateId("RTY", 10));
            roomType.setCreateDate(now);
        } else {
            roomType = roomTypeRepository.findById(roomTypeId).orElse(null);
            if (roomType == null) {
                redirectAttributes.addFlashAttribute("error", "Không tìm thấy loại phòng cần cập nhật.");
                return "redirect:/manager/room-price/dashboard#room-type-section";
            }
        }

        roomType.setName(trimmedName);
        roomType.setMaxCustomers(maxCustomers);
        roomType.setArea(area);
        roomType.setBasePrice(basePrice);
        roomType.setDepositPercent(depositPercent);
        roomType.setDescription(trimmedDescription.isBlank() ? null : trimmedDescription);
        roomType.setUpdateDate(now);

        roomTypeRepository.save(roomType);

        redirectAttributes.addFlashAttribute("message", isCreate ? "Đã thêm loại phòng mới." : "Đã cập nhật loại phòng.");
        return "redirect:/manager/room-price/dashboard#room-type-section";
    }

    @PostMapping("/rooms/save")
    @Transactional
    public String saveRoom(@RequestParam(required = false) String roomId,
                           @RequestParam String name,
                           @RequestParam String location,
                           @RequestParam String roomTypeId,
                           @RequestParam RoomStatus status,
                           @RequestParam(value = "primaryImageFile", required = false) MultipartFile primaryImageFile,
                           @RequestParam(value = "secondaryImageFiles", required = false) List<MultipartFile> secondaryImageFiles,
                           HttpSession session,
                           RedirectAttributes redirectAttributes) {

        if (!managerAccessService.hasAccess(session, ManagerAccessService.ManagerModule.ROOM_PRICE)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền thực hiện chức năng này.");
            return "redirect:/manager";
        }

        String trimmedName = safeTrim(name);
        String trimmedLocation = safeTrim(location);

        if (trimmedName.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Tên phòng không được để trống.");
            return "redirect:/manager/room-price/dashboard#room-section";
        }

        if (trimmedLocation.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Vị trí phòng không được để trống.");
            return "redirect:/manager/room-price/dashboard#room-section";
        }

        if (status == null) {
            redirectAttributes.addFlashAttribute("error", "Trạng thái phòng không hợp lệ.");
            return "redirect:/manager/room-price/dashboard#room-section";
        }

        RoomType roomType = roomTypeRepository.findById(roomTypeId).orElse(null);
        if (roomType == null) {
            redirectAttributes.addFlashAttribute("error", "Loại phòng không hợp lệ.");
            return "redirect:/manager/room-price/dashboard#room-section";
        }

        boolean isCreate = roomId == null || roomId.isBlank();
        Room room;

        if (isCreate) {
            room = new Room();
            room.setId(generateId("ROM", 10));
            room.setStatus(status);
        } else {
            room = roomRepository.findById(roomId).orElse(null);
            if (room == null) {
                redirectAttributes.addFlashAttribute("error", "Không tìm thấy phòng cần cập nhật.");
                return "redirect:/manager/room-price/dashboard#room-section";
            }
        }

        room.setName(trimmedName);
        room.setLocation(trimmedLocation);
        room.setRoomType(roomType);
        room.setStatus(status);

        boolean hasPrimaryUpload = hasUploadedFile(primaryImageFile);
        List<MultipartFile> normalizedSecondaryFiles = normalizeSecondaryImageFiles(secondaryImageFiles);
        boolean hasSecondaryUpload = !normalizedSecondaryFiles.isEmpty();
        boolean roomAlreadyHasImages = !roomImageRepository.findAllByRoomOrderByCreateDateAsc(room).isEmpty();

        if (isCreate && !hasPrimaryUpload) {
            redirectAttributes.addFlashAttribute("error", "Ảnh chính của phòng không được để trống.");
            return "redirect:/manager/room-price/dashboard#room-section";
        }

        if (!isCreate && !roomAlreadyHasImages && !hasPrimaryUpload) {
            redirectAttributes.addFlashAttribute("error", "Phòng hiện chưa có ảnh chính. Vui lòng tải lên ảnh chính.");
            return "redirect:/manager/room-price/dashboard?editRoom=" + room.getId() + "#room-section";
        }

        room = roomRepository.save(room);

        try {
            if (isCreate) {
                createInitialRoomImages(room, primaryImageFile, normalizedSecondaryFiles);
                redirectAttributes.addFlashAttribute("message", "Đã thêm phòng mới kèm bộ ảnh.");
            } else {
                boolean changed = false;
                if (hasPrimaryUpload) {
                    replacePrimaryImage(room, primaryImageFile);
                    changed = true;
                }
                if (hasSecondaryUpload) {
                    addSecondaryImages(room, normalizedSecondaryFiles);
                    changed = true;
                }

                redirectAttributes.addFlashAttribute(
                        "message",
                        changed ? "Đã cập nhật thông tin phòng và bộ ảnh." : "Đã cập nhật thông tin phòng."
                );
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            if (isCreate) {
                roomRepository.delete(room);
            }
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/manager/room-price/dashboard" + (isCreate ? "#room-section" : "?editRoom=" + room.getId() + "#room-section");
        }

        return "redirect:/manager/room-price/dashboard" + (isCreate ? "#room-section" : "?editRoom=" + room.getId() + "#room-section");
    }

    @PostMapping("/rooms/{roomId}/images/{imageId}/delete")
    @Transactional
    public String deleteRoomImage(@PathVariable String roomId,
                                  @PathVariable String imageId,
                                  HttpSession session,
                                  RedirectAttributes redirectAttributes) {

        if (!managerAccessService.hasAccess(session, ManagerAccessService.ManagerModule.ROOM_PRICE)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền thực hiện chức năng này.");
            return "redirect:/manager";
        }

        Room room = roomRepository.findById(roomId).orElse(null);
        if (room == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy phòng.");
            return "redirect:/manager/room-price/dashboard#room-section";
        }

        RoomImage roomImage = roomImageRepository.findById(imageId).orElse(null);
        if (roomImage == null || roomImage.getRoom() == null || !roomId.equals(roomImage.getRoom().getId())) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy ảnh cần xóa.");
            return "redirect:/manager/room-price/dashboard?editRoom=" + roomId + "#room-section";
        }

        List<RoomImage> allImages = roomImageRepository.findAllByRoomOrderByCreateDateAsc(room);
        if (allImages.size() <= 1) {
            redirectAttributes.addFlashAttribute("error", "Không thể xóa ảnh cuối cùng của phòng.");
            return "redirect:/manager/room-price/dashboard?editRoom=" + roomId + "#room-section";
        }

        boolean deletingPrimary = Boolean.TRUE.equals(roomImage.getIsPrimary());
        String imagePath = roomImage.getImagePath();

        roomImageRepository.delete(roomImage);
        mediaStorageService.deleteByPublicPath(imagePath);

        if (deletingPrimary) {
            promoteAnotherImageAsPrimary(room);
            redirectAttributes.addFlashAttribute("message", "Đã xóa ảnh chính và tự động chuyển một ảnh khác làm ảnh chính.");
        } else {
            redirectAttributes.addFlashAttribute("message", "Đã xóa ảnh phụ.");
        }

        return "redirect:/manager/room-price/dashboard?editRoom=" + roomId + "#room-section";
    }

    @PostMapping("/price-rates/save")
    @Transactional
    public String savePriceRate(@RequestParam(required = false) String priceRateId,
                                @RequestParam String eventName,
                                @RequestParam Double surchargeAmount,
                                HttpSession session,
                                RedirectAttributes redirectAttributes) {

        if (!managerAccessService.hasAccess(session, ManagerAccessService.ManagerModule.ROOM_PRICE)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền thực hiện chức năng này.");
            return "redirect:/manager";
        }

        boolean isCreate = priceRateId == null || priceRateId.isBlank();
        if (!isCreate) {
            redirectAttributes.addFlashAttribute("error", "Sự kiện giá đã tạo thì không được chỉnh sửa. Hãy tạo sự kiện mới.");
            return "redirect:/manager/room-price/dashboard#price-rate-section";
        }

        String trimmedEventName = safeTrim(eventName);
        if (trimmedEventName.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Tên sự kiện giá không được để trống.");
            return "redirect:/manager/room-price/dashboard#price-rate-section";
        }

        if (surchargeAmount == null) {
            redirectAttributes.addFlashAttribute("error", "Mức điều chỉnh giá không hợp lệ.");
            return "redirect:/manager/room-price/dashboard#price-rate-section";
        }

        List<PriceRate> existing = priceRateRepository.findAllByOrderByCreateDateDesc();
        boolean duplicated = existing.stream()
                .anyMatch(item -> item.getEventName() != null
                        && item.getEventName().equalsIgnoreCase(trimmedEventName));

        if (duplicated) {
            redirectAttributes.addFlashAttribute("error", "Tên sự kiện giá đã tồn tại.");
            return "redirect:/manager/room-price/dashboard#price-rate-section";
        }

        LocalDateTime now = LocalDateTime.now();

        PriceRate priceRate = new PriceRate();
        priceRate.setId(generateId("PRC", 10));
        priceRate.setCreateDate(now);
        priceRate.setUpdateDate(now);
        priceRate.setEventName(trimmedEventName);
        priceRate.setSurchargeAmount(surchargeAmount);

        priceRateRepository.save(priceRate);

        redirectAttributes.addFlashAttribute("message", "Đã thêm sự kiện giá mới.");
        return "redirect:/manager/room-price/dashboard#price-rate-section";
    }

    @PostMapping("/applied-periods/save")
    @Transactional
    public String saveAppliedPeriod(@RequestParam(required = false) String appliedPeriodId,
                                    @RequestParam String priceRateId,
                                    @RequestParam String roomTypeId,
                                    @RequestParam String startDate,
                                    @RequestParam String endDate,
                                    HttpSession session,
                                    RedirectAttributes redirectAttributes) {

        if (!managerAccessService.hasAccess(session, ManagerAccessService.ManagerModule.ROOM_PRICE)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền thực hiện chức năng này.");
            return "redirect:/manager";
        }

        boolean isCreate = appliedPeriodId == null || appliedPeriodId.isBlank();
        if (!isCreate) {
            redirectAttributes.addFlashAttribute("error", "Kỳ áp dụng đã tạo thì không được chỉnh sửa. Hãy tạo kỳ áp dụng mới.");
            return "redirect:/manager/room-price/dashboard#applied-period-section";
        }

        PriceRate priceRate = priceRateRepository.findById(priceRateId).orElse(null);
        RoomType roomType = roomTypeRepository.findById(roomTypeId).orElse(null);

        if (priceRate == null || roomType == null) {
            redirectAttributes.addFlashAttribute("error", "Dữ liệu áp dụng giá không hợp lệ.");
            return "redirect:/manager/room-price/dashboard#applied-period-section";
        }

        LocalDateTime parsedStartDate;
        LocalDateTime parsedEndDate;

        try {
            parsedStartDate = LocalDateTime.parse(startDate);
            parsedEndDate = LocalDateTime.parse(endDate);
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("error", "Ngày giờ áp dụng không đúng định dạng.");
            return "redirect:/manager/room-price/dashboard#applied-period-section";
        }

        if (!parsedEndDate.isAfter(parsedStartDate)) {
            redirectAttributes.addFlashAttribute("error", "Thời điểm kết thúc phải sau thời điểm bắt đầu.");
            return "redirect:/manager/room-price/dashboard#applied-period-section";
        }

        boolean overlapped = appliedPeriodRepository.findByRoomTypeIdOrderByStartDateDesc(roomTypeId)
                .stream()
                .anyMatch(item -> overlaps(
                        parsedStartDate,
                        parsedEndDate,
                        item.getStartDate(),
                        item.getEndDate()
                ));

        if (overlapped) {
            redirectAttributes.addFlashAttribute("error", "Kỳ áp dụng bị trùng thời gian với một kỳ khác của cùng loại phòng.");
            return "redirect:/manager/room-price/dashboard#applied-period-section";
        }

        AppliedPeriod appliedPeriod = new AppliedPeriod();
        appliedPeriod.setId(generateId("APD", 10));
        appliedPeriod.setPriceRate(priceRate);
        appliedPeriod.setRoomType(roomType);
        appliedPeriod.setStartDate(parsedStartDate);
        appliedPeriod.setEndDate(parsedEndDate);

        appliedPeriodRepository.save(appliedPeriod);

        redirectAttributes.addFlashAttribute("message", "Đã thêm kỳ áp dụng giá.");
        return "redirect:/manager/room-price/dashboard#applied-period-section";
    }

    @PostMapping("/price-rates/{priceRateId}/delete")
    @Transactional
    public String deletePriceRate(@PathVariable String priceRateId,
                                  HttpSession session,
                                  RedirectAttributes redirectAttributes) {

        if (!managerAccessService.hasAccess(session, ManagerAccessService.ManagerModule.ROOM_PRICE)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền thực hiện chức năng này.");
            return "redirect:/manager";
        }

        redirectAttributes.addFlashAttribute("error", "Không được xóa sự kiện giá đã tạo. Hãy ngừng dùng và tạo dữ liệu mới.");
        return "redirect:/manager/room-price/dashboard#price-rate-section";
    }

    @PostMapping("/applied-periods/{appliedPeriodId}/delete")
    @Transactional
    public String deleteAppliedPeriod(@PathVariable String appliedPeriodId,
                                      HttpSession session,
                                      RedirectAttributes redirectAttributes) {

        if (!managerAccessService.hasAccess(session, ManagerAccessService.ManagerModule.ROOM_PRICE)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền thực hiện chức năng này.");
            return "redirect:/manager";
        }

        AppliedPeriod appliedPeriod = appliedPeriodRepository.findById(appliedPeriodId).orElse(null);
        if (appliedPeriod == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy kỳ áp dụng cần xóa.");
            return "redirect:/manager/room-price/dashboard#applied-period-section";
        }

        appliedPeriodRepository.delete(appliedPeriod);

        redirectAttributes.addFlashAttribute("message", "Đã xóa kỳ áp dụng giá.");
        return "redirect:/manager/room-price/dashboard#applied-period-section";
    }

    private boolean overlaps(LocalDateTime start1, LocalDateTime end1,
                             LocalDateTime start2, LocalDateTime end2) {
        return start1.isBefore(end2) && end1.isAfter(start2);
    }

    private void createInitialRoomImages(Room room,
                                         MultipartFile primaryImageFile,
                                         List<MultipartFile> secondaryImageFiles) {
        StoredMedia storedPrimaryImage = null;
        List<StoredMedia> storedSecondaryImages = List.of();

        try {
            storedPrimaryImage = mediaStorageService.storeImage(primaryImageFile, MediaStorageDirectory.ROOMS, room.getId() + "_primary");
            storedSecondaryImages = secondaryImageFiles.stream()
                    .map(file -> mediaStorageService.storeImage(file, MediaStorageDirectory.ROOMS, room.getId() + "_secondary"))
                    .toList();

            saveRoomImage(room, storedPrimaryImage.publicPath(), true);
            for (StoredMedia secondaryImage : storedSecondaryImages) {
                saveRoomImage(room, secondaryImage.publicPath(), false);
            }
        } catch (RuntimeException ex) {
            if (storedPrimaryImage != null) {
                mediaStorageService.deleteByPublicPath(storedPrimaryImage.publicPath());
            }
            for (StoredMedia secondaryImage : storedSecondaryImages) {
                mediaStorageService.deleteByPublicPath(secondaryImage.publicPath());
            }
            throw ex;
        }
    }

    private void replacePrimaryImage(Room room, MultipartFile primaryImageFile) {
        RoomImage existingPrimaryImage = getPrimaryImage(room);
        StoredMedia storedPrimaryImage = mediaStorageService.storeImage(primaryImageFile, MediaStorageDirectory.ROOMS, room.getId() + "_primary");

        try {
            if (existingPrimaryImage == null) {
                saveRoomImage(room, storedPrimaryImage.publicPath(), true);
            } else {
                String oldImagePath = existingPrimaryImage.getImagePath();
                existingPrimaryImage.setImagePath(storedPrimaryImage.publicPath());
                existingPrimaryImage.setIsPrimary(true);
                roomImageRepository.save(existingPrimaryImage);
                mediaStorageService.deleteByPublicPath(oldImagePath);
            }
        } catch (RuntimeException ex) {
            mediaStorageService.deleteByPublicPath(storedPrimaryImage.publicPath());
            throw ex;
        }
    }

    private void addSecondaryImages(Room room, List<MultipartFile> secondaryImageFiles) {
        List<StoredMedia> storedSecondaryImages = List.of();
        try {
            storedSecondaryImages = secondaryImageFiles.stream()
                    .map(file -> mediaStorageService.storeImage(file, MediaStorageDirectory.ROOMS, room.getId() + "_secondary"))
                    .toList();

            for (StoredMedia secondaryImage : storedSecondaryImages) {
                saveRoomImage(room, secondaryImage.publicPath(), false);
            }
        } catch (RuntimeException ex) {
            for (StoredMedia secondaryImage : storedSecondaryImages) {
                mediaStorageService.deleteByPublicPath(secondaryImage.publicPath());
            }
            throw ex;
        }
    }

    private void promoteAnotherImageAsPrimary(Room room) {
        List<RoomImage> remainingImages = roomImageRepository.findAllByRoomOrderByCreateDateAsc(room);
        boolean hasPrimary = remainingImages.stream().anyMatch(item -> Boolean.TRUE.equals(item.getIsPrimary()));
        if (hasPrimary) {
            return;
        }

        remainingImages.stream().findFirst().ifPresent(image -> {
            image.setIsPrimary(true);
            roomImageRepository.save(image);
        });
    }

    private void saveRoomImage(Room room, String imagePath, boolean isPrimary) {
        RoomImage roomImage = new RoomImage();
        roomImage.setId(generateId("RIM", 10));
        roomImage.setRoom(room);
        roomImage.setImagePath(imagePath);
        roomImage.setIsPrimary(isPrimary);
        roomImage.setCreateDate(LocalDateTime.now());
        roomImageRepository.save(roomImage);
    }

    private boolean hasUploadedFile(MultipartFile file) {
        return file != null && !file.isEmpty();
    }

    private List<MultipartFile> normalizeSecondaryImageFiles(List<MultipartFile> secondaryImageFiles) {
        if (secondaryImageFiles == null || secondaryImageFiles.isEmpty()) {
            return List.of();
        }

        return secondaryImageFiles.stream()
                .filter(file -> file != null && !file.isEmpty())
                .toList();
    }

    public String getPrimaryImagePath(Room room) {
        RoomImage primaryImage = getPrimaryImage(room);
        return primaryImage == null
                ? mediaViewSupport.getDefaultRoomPath()
                : mediaViewSupport.resolveRoomImagePath(primaryImage.getImagePath());
    }

    public RoomImage getPrimaryImage(Room room) {
        if (room == null) {
            return null;
        }

        return roomImageRepository.findFirstByRoomAndIsPrimaryTrue(room)
                .orElseGet(() -> roomImageRepository.findAllByRoomOrderByCreateDateAsc(room).stream()
                        .filter(item -> item.getImagePath() != null && !item.getImagePath().isBlank())
                        .findFirst()
                        .orElse(null));
    }

    public List<RoomImage> getSecondaryImages(Room room) {
        if (room == null) {
            return List.of();
        }

        return roomImageRepository.findAllByRoomOrderByCreateDateAsc(room).stream()
                .filter(item -> !Boolean.TRUE.equals(item.getIsPrimary()))
                .filter(item -> item.getImagePath() != null && !item.getImagePath().isBlank())
                .toList();
    }

    private Map<String, Long> buildRoomCountByType(List<RoomType> roomTypes, List<Room> rooms) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (RoomType roomType : roomTypes) {
            long count = rooms.stream()
                    .filter(room -> room.getRoomType() != null && roomType.getId().equals(room.getRoomType().getId()))
                    .count();
            result.put(roomType.getName(), count);
        }
        return result;
    }

    private Map<String, String> buildRoomStatusByType(List<RoomType> roomTypes, List<Room> rooms) {
        Map<String, String> result = new LinkedHashMap<>();

        for (RoomType roomType : roomTypes) {
            List<Room> matchedRooms = rooms.stream()
                    .filter(room -> room.getRoomType() != null && roomType.getId().equals(room.getRoomType().getId()))
                    .toList();

            Map<String, Long> grouped = matchedRooms.stream()
                    .filter(room -> room.getStatus() != null)
                    .collect(Collectors.groupingBy(
                            room -> room.getStatus().name(),
                            LinkedHashMap::new,
                            Collectors.counting()
                    ));

            if (grouped.isEmpty()) {
                result.put(roomType.getName(), "Chưa có dữ liệu trạng thái");
            } else {
                String summary = grouped.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + ": " + entry.getValue())
                        .reduce((a, b) -> a + " | " + b)
                        .orElse("Chưa có dữ liệu trạng thái");
                result.put(roomType.getName(), summary);
            }
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
