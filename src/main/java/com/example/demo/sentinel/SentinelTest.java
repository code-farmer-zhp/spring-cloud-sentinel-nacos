package com.example.demo.sentinel;

import com.alibaba.csp.sentinel.annotation.SentinelResource;

import org.springframework.stereotype.Component;

@Component
public class SentinelTest {

    @SentinelResource(value = "helloWord", fallback = "fallback")
    public void helloWord() {

        System.out.println("hello");

    }

    public void fallback() {
        System.out.println("fallback");
    }
}
