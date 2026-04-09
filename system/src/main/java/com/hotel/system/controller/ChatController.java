package com.hotel.system.controller;

import com.hotel.system.entity.Users;
import com.hotel.system.service.ChatService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> body,
                                                    HttpSession session) {

        String message = body.get("message");

        Object loggedInUser = session.getAttribute("loggedInUser");
        Users currentUser = loggedInUser instanceof Users ? (Users) loggedInUser : null;

        String reply = chatService.reply(message, currentUser, session);

        Map<String, Object> result = new HashMap<>();
        result.put("reply", reply);

        return ResponseEntity.ok(result);
    }
}