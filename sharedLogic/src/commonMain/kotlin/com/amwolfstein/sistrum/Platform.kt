package com.amwolfstein.sistrum

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform