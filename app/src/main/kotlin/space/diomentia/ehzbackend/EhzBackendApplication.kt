package space.diomentia.ehzbackend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class EhzBackendApplication

fun main(args: Array<String>) {
    runApplication<EhzBackendApplication>(*args)
}
