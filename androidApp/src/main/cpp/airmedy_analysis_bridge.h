#ifndef SISTRUM_AIRMEDY_ANALYSIS_BRIDGE_H
#define SISTRUM_AIRMEDY_ANALYSIS_BRIDGE_H
#ifdef __cplusplus
extern "C" {
#endif
typedef struct SistrumAnalysis SistrumAnalysis;
SistrumAnalysis *sistrum_analysis_create(void);
int sistrum_analysis_open(SistrumAnalysis *state, int fd);
int sistrum_analysis_run(SistrumAnalysis *state, double output[12]);
void sistrum_analysis_cancel(SistrumAnalysis *state);
void sistrum_analysis_destroy(SistrumAnalysis *state);
#ifdef __cplusplus
}
#endif
#endif
