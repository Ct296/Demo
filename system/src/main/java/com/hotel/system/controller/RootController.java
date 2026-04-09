package com.hotel.system.controller;

import com.hotel.system.entity.Users;
import com.hotel.system.entity.enums.Role;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class RootController {

    @GetMapping("/")
    public String root(HttpSession session) {
        return resolveHomeRedirect(session);
    }

    @GetMapping("/home")
    public String home(HttpSession session) {
        return resolveHomeRedirect(session);
    }

    private String resolveHomeRedirect(HttpSession session) {
        Object loggedInUser = session.getAttribute("loggedInUser");

        if (loggedInUser instanceof Users user && user.getRole() != null) {
            Role role = user.getRole();

            return switch (role) {
                case ADMIN -> "redirect:/admin/dashboard";
                case MANAGER -> "redirect:/manager";
                case STAFF -> "redirect:/staff/dashboard";
                case CUSTOMER -> "redirect:/customer/home";
            };
        }

        return "redirect:/customer/home";
    }
}