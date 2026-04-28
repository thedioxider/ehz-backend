package space.diomentia.ehzbackend.discovery

import kotlinx.serialization.Serializable

/**
 * Payload sent back to a UDP broadcast client that said the magic word.
 *
 * Field names are part of the wire contract; renaming any of them is a
 * [ApiContract.VERSION]-bumping change on the app side.
 */
@Serializable
data class DiscoveryReply(
    val ip: String,
    val port: Int,
    val apiBasePath: String,
    val version: Int,
)
