#include "util.h"

static char constants[] = "\x84\x25\x8C\x08\x4D\x89\xC1\x3E\x34\x3C\x66\x9A\x9C\x8A\xFA\x4E\x76\x46\x58\x9E\x58\x1B\xB9\xB7\xF4\x67\xFC\x69\xDE\xA6\xD9\x4E\xF2\x34\x38\x23\xA7\x3D\x25\xBF\x8F\x98\xC5\xAE\xE6\x66\x43\x43\x34\x4B\xCC\x5F\x0B\x0E\x9C\x5A\x17\x64\xA9\x3F\xA3\xE6\x55";

static StringCpInfo scpF {
	.ptr = constants,
	.n = sizeof(constants) - 1
};

StringCpInfo* stringConstantPool = &scpF;

int main() {
	// printf("%zu\n", sizeof(theConsts));
	unsigned char key[48] = {};
	const char* ok = "\x67\x6F\x6F\x64\x6D\x6F\x72\x6E\x69\x6E\x67\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00";
	mempcpy(key, ok, 48);
	// unsigned char chacha[64] = {};
	// fuckMyShitUp(key, chacha, 1);
	// for (unsigned char chacha1 : chacha) {
		// printf("%u\n", chacha1);
	// }
	// puts("");
	initChachaTable(key);
	for (size_t i = 0; i < scpF.n; i++) {
		printf("%x ", static_cast<unsigned char>(scpF.ptr[i]));
	}
	puts("");
	//
	// for (size_t i = 0; i < scpF.n; i++) {
	// 	printf("%x ", static_cast<unsigned char>(scpF.ptr[i]));
	// }
	// puts("");

	// uint32_t* blockAsUint2 = reinterpret_cast<uint32_t*>(chachaTable);
	// for (size_t base = 0; base < (1024/4); base+=4) {
	// 	printf("%08x %08x %08x %08x\n", blockAsUint2[base], blockAsUint2[base+1], blockAsUint2[base+2], blockAsUint2[base+3]);
	// }
}