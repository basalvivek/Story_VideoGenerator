package com.moviemaker.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    @GetMapping("/watch/{token}")
    public String watch(@org.springframework.web.bind.annotation.PathVariable String token) {
        return "forward:/watch.html";
    }
}
