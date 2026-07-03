package com.example.notificationdispatcher;

import org.springframework.boot.SpringApplication;

public class TestNotificationDispatcherApplication {

    public static void main(String[] args) {
        SpringApplication.from(NotificationDispatcherApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
