package com.amwolfstein.sistrum.player.engine

/** always native until the engine switch (T018+) */
internal object EngineFactory {
    fun create(): PlayerEngine = LegacyNativeEngine()
}
