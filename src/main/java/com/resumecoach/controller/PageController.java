package com.resumecoach.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/sign-in")
    public String signIn() {
        return "forward:/sign-in.html";
    }

    @GetMapping("/sign-up")
    public String signUp() {
        return "forward:/sign-up.html";
    }

    @GetMapping("/privacy")
    public String privacy() {
        return "forward:/privacy.html";
    }

    @GetMapping("/terms")
    public String terms() {
        return "forward:/terms.html";
    }
}
