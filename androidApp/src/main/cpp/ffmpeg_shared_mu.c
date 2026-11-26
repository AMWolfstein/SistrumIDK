#include "ffmpeg_shared_mu.h"
#include <stdatomic.h>
pthread_mutex_t ffmpeg_probe_mu = PTHREAD_MUTEX_INITIALIZER;
static atomic_int playback_waiters = 0;
void sistrum_playback_probe_enter(void) { atomic_fetch_add(&playback_waiters, 1); }
void sistrum_playback_probe_leave(void) { atomic_fetch_sub(&playback_waiters, 1); }
int sistrum_playback_probe_waiting(void) { return atomic_load(&playback_waiters) > 0; }
