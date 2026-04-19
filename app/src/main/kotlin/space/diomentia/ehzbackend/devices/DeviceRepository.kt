package space.diomentia.ehzbackend.devices

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface DeviceRepository : JpaRepository<Device, UUID>
