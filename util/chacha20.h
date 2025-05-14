#pragma once
#include "export.h"

#include <stdint.h>
#include <stdio.h> // for some ungodly reason this is needed to get size_t on x8664 linux

#ifdef COMPILING_COMPTIME_LIB
#define CCEXPORT EXPORT
#else
#define CCEXPORT
#endif

CCEXPORT void chacha_block(uint32_t out[16], uint32_t const in[16], int rounds);
CCEXPORT void chacha_block_8(uint8_t out[16 * 4], uint8_t const in[16 * 4], int rounds);

/**
 * 
 * @param keyData 48 chars
 * @param chachaTable nBlocks * 64 chars
 * @param nBlocks how many blocks to generate
 */
CCEXPORT void fuckMyShitUp(const unsigned char *keyData, unsigned char chachaTable[], size_t nBlocks);