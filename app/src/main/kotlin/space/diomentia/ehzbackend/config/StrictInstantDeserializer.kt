package space.diomentia.ehzbackend.config

import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.exc.MismatchedInputException
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Rejects numeric (epoch-second or epoch-millisecond) Instant values.
 * Only ISO-8601 strings are accepted — e.g. "2024-01-03T22:40:00Z".
 *
 * Registered via [JacksonConfig.strictInstantModule] so it overrides
 * Jackson's default JavaTimeModule deserializer for Instant.
 */
class StrictInstantDeserializer : StdDeserializer<Instant>(Instant::class.java) {

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Instant {
        if (!p.hasToken(JsonToken.VALUE_STRING)) {
            throw MismatchedInputException.from(
                p,
                Instant::class.java,
                "Instant fields must be ISO-8601 strings (e.g. \"2024-01-03T22:40:00Z\"), " +
                    "got ${p.currentToken()}",
            )
        }
        return try {
            Instant.parse(p.text)
        } catch (e: DateTimeParseException) {
            throw MismatchedInputException.from(
                p,
                Instant::class.java,
                "Invalid ISO-8601 Instant '${p.text}': ${e.message}",
            )
        }
    }

    companion object {
        val INSTANCE = StrictInstantDeserializer()
    }
}
