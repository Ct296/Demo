package com.hotel.system.service;

import com.hotel.system.entity.Manager;
import com.hotel.system.entity.Users;
import com.hotel.system.entity.enums.ManagerType;
import com.hotel.system.entity.enums.Role;
import com.hotel.system.repository.ManagerRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ManagerAccessService {

    public enum ManagerModule {
        ROOM_PRICE,
        CUSTOMER,
        HR,
        SERVICE
    }

    private final ManagerRepository managerRepository;

    public ManagerAccessService(ManagerRepository managerRepository) {
        this.managerRepository = managerRepository;
    }

    public Users getLoggedInManagerUser(HttpSession session) {
        Object loggedInUser = session.getAttribute("loggedInUser");
        if (!(loggedInUser instanceof Users user)) {
            return null;
        }

        if (user.getRole() != Role.MANAGER) {
            return null;
        }

        return user;
    }

    public Manager getLoggedInManager(HttpSession session) {
        Users user = getLoggedInManagerUser(session);
        if (user == null) {
            return null;
        }

        return managerRepository.findById(user.getId()).orElse(null);
    }

    public boolean hasAccess(HttpSession session, ManagerModule expectedModule) {
        Manager manager = getLoggedInManager(session);
        if (manager == null || manager.getTitle() == null) {
            return false;
        }

        ManagerModule actualModule = resolveModule(manager);
        return actualModule == expectedModule;
    }

    public String resolveDashboardPath(HttpSession session) {
        Manager manager = getLoggedInManager(session);
        if (manager == null || manager.getTitle() == null) {
            return null;
        }

        ManagerModule module = resolveModule(manager);
        return moduleToPath(module);
    }

    public String moduleToPath(ManagerModule module) {
        return switch (module) {
            case ROOM_PRICE -> "/manager/room-price/dashboard";
            case CUSTOMER -> "/manager/customer/dashboard";
            case HR -> "/manager/hr/dashboard";
            case SERVICE -> "/manager/service/dashboard";
        };
    }

    public ManagerModule resolveModule(Manager manager) {
        if (manager == null || manager.getTitle() == null) {
            return ManagerModule.ROOM_PRICE;
        }

        return switch (manager.getTitle()) {
            case ROOM_PRICING_MANAGER -> ManagerModule.ROOM_PRICE;
            case CUSTOMER_MANAGER -> ManagerModule.CUSTOMER;
            case HR_MANAGER -> ManagerModule.HR;
            case SERVICE_MANAGER -> ManagerModule.SERVICE;
        };
    }

    public Optional<Manager> findManagerByUserId(String userId) {
        return managerRepository.findById(userId);
    }

    public boolean isRoomPricingManager(ManagerType managerType) {
        return managerType == ManagerType.ROOM_PRICING_MANAGER;
    }

    public boolean isCustomerManager(ManagerType managerType) {
        return managerType == ManagerType.CUSTOMER_MANAGER;
    }

    public boolean isHrManager(ManagerType managerType) {
        return managerType == ManagerType.HR_MANAGER;
    }

    public boolean isServiceManager(ManagerType managerType) {
        return managerType == ManagerType.SERVICE_MANAGER;
    }
}