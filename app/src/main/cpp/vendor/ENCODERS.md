# Bundled audio encoders

LAME 3.100: https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz
SHA-256: ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e
Vendored encoder sources and public headers; upstream files are unmodified.
The parent CMakeLists.txt supplies the Android build configuration. LGPL license: lame/COPYING.

FLAC 1.5.0: https://downloads.xiph.org/releases/flac/flac-1.5.0.tar.xz
SHA-256: f2c1c76592a82ffff8413ba3c4a1299b6c7ab06c734dee03fd88630485c2b920
Unmodified source release, built offline with the upstream CMake project.
Only libFLAC is linked; programs, tests, examples, docs, Ogg and C++ wrappers are disabled.
Library license: flac/COPYING.Xiph (BSD). Other upstream tools retain their own licenses.

Both encoders are statically linked into libgearcam_audio.so for arm64-v8a, armeabi-v7a and x86_64.
No prebuilt third-party binaries or build-time network downloads are used.
