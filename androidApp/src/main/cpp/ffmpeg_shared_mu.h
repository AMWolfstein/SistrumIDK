#ifndef SISTRUM_FFMPEG_SHARED_MU_H
#define SISTRUM_FFMPEG_SHARED_MU_H
#include <pthread.h>
#ifdef __cplusplus
extern "C" {
#endif
extern pthread_mutex_t ffmpeg_probe_mu;
void sistrum_playback_probe_enter(void);
void sistrum_playback_probe_leave(void);
int sistrum_playback_probe_waiting(void);
#ifdef __cplusplus
}
#endif
#endif
