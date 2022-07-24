package com.example.demo.controller.test;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test")
public class TestController {


    @RequestMapping("/get")
    public String testGet() {
        return "test";
    }
}
