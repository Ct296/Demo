package com.hotel.system.controller;

import com.hotel.system.service.ManagerAccessService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ManagerController {

    private final ManagerAccessService managerAccessService;

    public ManagerController(ManagerAccessService managerAccessService) {
        this.managerAccessService = managerAccessService;
    }

    @GetMapping("/manager")
    public String managerHome(HttpSession session) {
        String dashboardPath = managerAccessService.resolveDashboardPath(session);
        if (dashboardPath == null) {
            return "redirect:/login";
        }

        return "redirect:" + dashboardPath;
    }

    @GetMapping("/manager/services")
    public String legacyManagerRoute() {
        return "redirect:/manager";
    }


    @GetMapping("/manager/profile")
    public String managerProfile() {
        return "common/OtherProfile";
    }
}