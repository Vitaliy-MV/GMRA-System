package org.gmra.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping({"/", "/index"})
    public String getIndexPage(Model model) {
    	model.addAttribute("serverTimeMillis", System.currentTimeMillis());
        return "index";
    }
    @GetMapping({"/mathcore"})
    public String getMathcore(Model model) {
    	model.addAttribute("serverTimeMillis", System.currentTimeMillis());
        return "mathcore";
    }
    @GetMapping({"/mathcore.html"})
    public String getMathcoreHTML() {
        return "mathcore"; 
    }
}