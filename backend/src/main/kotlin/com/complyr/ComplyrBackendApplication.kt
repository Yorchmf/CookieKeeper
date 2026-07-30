package com.complyr

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class ComplyrBackendApplication

fun main(args: Array<String>) {
    runApplication<ComplyrBackendApplication>(*args)
}
