package com.example.demo.controller.sentinel.command.http;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/sentinel")
public class SentinelCommandController {

    @RequestMapping("/**")
    public void command(HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        new HttpEventTask(servletRequest, servletResponse).run();
    }

}
