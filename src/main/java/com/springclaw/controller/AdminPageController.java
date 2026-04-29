package com.springclaw.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 后台静态页面入口。
 */
@Controller
public class AdminPageController {

    @GetMapping({"/admin", "/admin/"})
    public String adminPage() {
        return "redirect:/admin/index.html";
    }
}
