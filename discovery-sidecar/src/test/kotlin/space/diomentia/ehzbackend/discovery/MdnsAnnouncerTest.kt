package space.diomentia.ehzbackend.discovery

import java.net.InetAddress
import kotlin.test.Test

class MdnsAnnouncerTest {

    /**
     * Smoke test only. mDNS on CI is unreliable (often Avahi holds 5353/udp,
     * or multicast is disabled). We just assert that `start()` + `close()`
     * don't throw — bind failures inside `start()` are swallowed by design.
     */
    @Test
    fun `start and close do not throw`() {
        val announcer = MdnsAnnouncer(
            ip = InetAddress.getByName("127.0.0.1"),
            port = 19081,
            contractVersion = 1,
        )
        announcer.start()
        announcer.close()
    }
}
