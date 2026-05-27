package com.plum.reader

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.runApplication

@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
class PlumReaderApplication

fun main(args: Array<String>) {
    runApplication<PlumReaderApplication>(*args)
}
