package org.example.pricing

import io.grpc.ServerBuilder
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class PricingServiceApplication

fun main(args: Array<String>) {
    val context = runApplication<PricingServiceApplication>(*args) {
        webApplicationType = WebApplicationType.NONE
    }

    val pricingGrpcService = context.getBean(PricingGrpcService::class.java)
    val port = context.environment.getProperty("grpc.port", Int::class.java, 6565)

    val server = ServerBuilder.forPort(port)
        .addService(pricingGrpcService)
        .build()
        .start()

    println("pricing-service gRPC server listening on port $port")
    server.awaitTermination()
}
