package org.example

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SomeApplication

fun main(args: Array<String>) {
    runApplication<SomeApplication>(*args)
}
