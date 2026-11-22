#ifndef FOLD_CLIPBOARD_CHANNEL_H
#define FOLD_CLIPBOARD_CHANNEL_H

#include <freerdp/freerdp.h>

void clipboard_subscribe(freerdp* instance);
void clipboard_detach(freerdp* instance);

#endif
