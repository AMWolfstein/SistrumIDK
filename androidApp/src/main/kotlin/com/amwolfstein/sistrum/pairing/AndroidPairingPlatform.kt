package com.amwolfstein.sistrum.pairing

import java.util.UUID
import com.amwolfstein.sistrum.pairing.PairingClock
import com.amwolfstein.sistrum.pairing.PairingIdGenerator

object AndroidPairingClock : PairingClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

object AndroidPairingIdGenerator : PairingIdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}
