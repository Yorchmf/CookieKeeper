package com.complyr

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ComplyrBackendApplication

fun main(args: Array<String>) {
    runApplication<ComplyrBackendApplication>(*args)
}
