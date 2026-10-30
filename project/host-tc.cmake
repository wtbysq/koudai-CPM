# 宿主（Windows）工具链：只用来编译 vulkan-shaders-gen 这个小工具
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)

set(CMAKE_C_COMPILER "D:/kcpm/w64/w64devkit/bin/gcc.exe")
set(CMAKE_CXX_COMPILER "D:/kcpm/w64/w64devkit/bin/g++.exe")
set(CMAKE_MAKE_PROGRAM "D:/kcpm/android-ndk-r28b/prebuilt/windows-x86_64/bin/make.exe")

# 静态链接，避免生成的小工具运行时还缺 libstdc++/libwinpthread DLL
set(CMAKE_EXE_LINKER_FLAGS_INIT "-static")
set(CMAKE_C_FLAGS_INIT "-O2")
set(CMAKE_CXX_FLAGS_INIT "-O2")
