#include "chacha20.h"

#include <cstring>

#define ROTL(a,b) (((a) << (b)) | ((a) >> (32 - (b))))
#define QR(a, b, c, d) (             \
a += b, d ^= a, d = ROTL(d, 16), \
c += d, b ^= c, b = ROTL(b, 12), \
a += b, d ^= a, d = ROTL(d,  8), \
c += d, b ^= c, b = ROTL(b,  7))
CCEXPORT void chacha_block(uint32_t out[16], uint32_t const in[16], int rounds)
{
	int i;
	uint32_t x[16];

	for (i = 0; i < 16; ++i)
		x[i] = in[i];
	// 10 loops × 2 rounds/loop = 20 rounds
	for (i = 0; i < rounds; i += 2) {

		// Odd round
		QR(x[0], x[4], x[ 8], x[12]); // column 1
		QR(x[1], x[5], x[ 9], x[13]); // column 2
		QR(x[2], x[6], x[10], x[14]); // column 3
		QR(x[3], x[7], x[11], x[15]); // column 4
		// Even round
		QR(x[0], x[5], x[10], x[15]); // diagonal 1 (main diagonal)
		QR(x[1], x[6], x[11], x[12]); // diagonal 2
		QR(x[2], x[7], x[ 8], x[13]); // diagonal 3
		QR(x[3], x[4], x[ 9], x[14]); // diagonal 4
	}
	for (i = 0; i < 16; ++i)
		out[i] = x[i] + in[i];
}

CCEXPORT void chacha_block_8(uint8_t out[16 * 4], uint8_t const in[16 * 4], int rounds) {
	chacha_block(reinterpret_cast<uint32_t *>(out), reinterpret_cast<uint32_t const *>(in), rounds);
}

inline uint32_t hash(uint32_t x) {
	x = ((x >> 16) ^ x) * 0x45d9f3b;
	x = ((x >> 16) ^ x) * 0x45d9f3b;
	x = (x >> 16) ^ x;
	return x;
}

CCEXPORT void fuckMyShitUp(const unsigned char *keyData, unsigned char chachaTable[], size_t nBlocks) {
//	for (size_t o = 0; o < 48; o++) {
//		printf("%d ", keyData[o]);
//	}
//	puts("");
	for (size_t block_index = 0; block_index < nBlocks; block_index++) {
		unsigned char block[64];
		unsigned char* blockStart = chachaTable + (block_index * 64);
		memcpy(block, keyData, 48); // copy key data

		uint32_t nonce = hash(block_index);

		uint32_t* blockAsUint = reinterpret_cast<uint32_t*>(block+48);
		blockAsUint[0] = block_index; // 48 + 0 * 4 = 48
		blockAsUint[1] = block_index; // 48 + 1 * 4 = 52
		blockAsUint[2] = nonce; // 48 + 2 * 4 = 56
		blockAsUint[3] = nonce; // 48 + 3 * 4 = 60

		chacha_block_8(blockStart, block, 20);
	}
}