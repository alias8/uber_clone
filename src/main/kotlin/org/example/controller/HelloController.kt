package org.example.controller

import org.example.repository.UserRepository
import org.example.service.KafkaEventProducer
import org.example.utils.setRedisSomethingKey
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HelloController(
    private val redisTemplate: RedisTemplate<String, String>,
    private val kafkaEventProducer: KafkaEventProducer,
    private val userRepository: UserRepository
) {

    @GetMapping("/hello")
    fun health(): ResponseEntity<String> {
        val username = SecurityContextHolder.getContext().authentication.name
        userRepository.findByUsername(username) ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        setRedisSomethingKey(redisTemplate, "value1")
        kafkaEventProducer.publishRideRequested("test-ride-id")
        return ResponseEntity.status(HttpStatus.OK).body("ok")
    }
}
