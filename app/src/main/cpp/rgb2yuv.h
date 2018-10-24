//
// Created by Bernick on 10/22/2018.
//

#ifndef AUTOMOBILEANDROID_RGB2YUV_H
#define AUTOMOBILEANDROID_RGB2YUV_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

void ConvertARGB8888ToYUV420SP(const uint32_t* const input,
                               uint8_t* const output, int width, int height);

void ConvertRGB565ToYUV420SP(const uint16_t* const input, uint8_t* const output,
                             const int width, const int height);

#ifdef __cplusplus
}
#endif

#endif //AUTOMOBILEANDROID_RGB2YUV_H
